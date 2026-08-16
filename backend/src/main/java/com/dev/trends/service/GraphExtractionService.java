package com.dev.trends.service;

import com.dev.trends.model.EdgeRequest;
import com.dev.trends.model.ExtractionResult;
import com.dev.trends.model.NodeRequest;
import com.dev.trends.repository.EdgeRepository;
import com.dev.trends.repository.NodeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

@Service
public class GraphExtractionService {

    private static final Logger log = LoggerFactory.getLogger(GraphExtractionService.class);

    private static final String HN_TOP_STORIES_URL = "https://hacker-news.firebaseio.com/v0/topstories.json";
    private static final String HN_ITEM_URL = "https://hacker-news.firebaseio.com/v0/item/%d.json";
    private static final String DEVTO_URL = "https://dev.to/api/articles?tag=%s&top=7&per_page=12";
    private static final String LOBSTERS_URL = "https://lobste.rs/hottest.json";
    private static final String USER_AGENT = "reticle/1.0 (tech trends aggregator)";

    private static final List<String> DEVTO_TAGS = List.of("ai", "javascript", "rust", "devops", "webdev");

    private static final String STACKOVERFLOW_URL =
            "https://api.stackexchange.com/2.3/questions?order=desc&sort=activity&tagged=%s&site=stackoverflow&pagesize=12&filter=default";
    private static final List<String> STACKOVERFLOW_TAGS =
            List.of("python", "javascript", "typescript", "docker", "kubernetes");

    private static final int MAX_HN_ARTICLES = 60;

    /**
     * Quantos artigos vão no prompt de extração. O tier free da Groq limita a 8000 tokens por
     * minuto e conta input + max_tokens como reservados, então o request inteiro tem que caber
     * nesse teto: ~20 tokens por artigo aqui dá ~2,7k de entrada, que com max_tokens deixa ~6,7k.
     * Coletamos mais artigos do que isso de propósito — o feed de notícias usa a lista completa,
     * só a curadoria do grafo é que enxerga a amostra.
     */
    private static final int MAX_PROMPT_ARTICLES = 120;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final NodeRepository nodeRepository;
    private final EdgeRepository edgeRepository;
    /**
     * Usado para delimitar transações SÓ em volta da persistência (persistNodes/persistEdges).
     * O pipeline inteiro não roda dentro de uma transação: as chamadas HTTP (~185) e o request
     * síncrono ao LLM acontecem sem segurar uma conexão do pool (que tem só 3 slots no Render
     * free tier). Cada upsert individual já é atômico via ON CONFLICT; nós e arestas pertencem
     * a agregados independentes, então não precisaríamos de uma transação "global" — só queremos
     * que cada upsert pegue e libere o pool rapidamente.
     */
    private final TransactionTemplate transactionTemplate;

    @Value("${openai.api.key:${GROQ_API_KEY:}}")
    private String llmApiKey;

    @Value("${openai.api.url:https://api.groq.com/openai/v1/chat/completions}")
    private String llmApiUrl;

    @Value("${openai.model:openai/gpt-oss-20b}")
    private String llmModel;

    public GraphExtractionService(
            NodeRepository nodeRepository,
            EdgeRepository edgeRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Artigo coletado de qualquer plataforma. */
    public record Article(String id, String title, String url, String discussionUrl, String platform, Instant publishedAt) {}

    /**
     * Teto de idade dos artigos no feed de notícias e no prompt do LLM. Itens mais antigos
     * que isso são descartados na coleta — protege contra Lobsters "hottest" (all-time) e
     * StackOverflow "activity" (perguntas antigas com comentário recente) trazendo conteúdo
     * de anos atrás pra home, o que fere o propósito do site de mostrar o que está em alta.
     */
    private static final Duration MAX_ARTICLE_AGE = Duration.ofDays(30);

    /**
     * Pipeline principal: busca artigos de múltiplas fontes → extrai grafo via LLM → persiste.
     * Sem {@code @Transactional} aqui de propósito: o pipeline inclui ~185 requests HTTP e
     * uma chamada síncrona ao LLM (até 60s). Manter a transação nesse intervalo monopolizaria
     * uma conexão do Hikari (pool de 3) e estouraria o limite em qualquer pareamento.
     * A persistência é envolvida em uma transação curta em persistNodes/persistEdges.
     */
    public ExtractionResult runIngestionPipeline() {
        log.info("Iniciando pipeline de ingestão multi-fonte...");

        List<Article> articles = fetchAllArticles();
        if (articles.isEmpty()) {
            log.warn("Nenhum artigo coletado. Abortando pipeline.");
            return ExtractionResult.empty();
        }
        log.info("Coletados {} artigos de {} fontes.", articles.size(),
                articles.stream().map(Article::platform).distinct().count());

        ExtractionResult extracted = callLlmForExtraction(articles);
        log.info("LLM extraiu {} nós e {} arestas.", extracted.nodes().size(), extracted.edges().size());

        int nodesCreated = persistNodes(extracted.nodes(), articles);
        int edgesCreated = persistEdges(extracted.edges(), articles);

        log.info("Pipeline concluído. Nós: {}, Arestas: {}", nodesCreated, edgesCreated);
        return extracted;
    }

    // =========================================================
    // FASE 1: Coleta multi-fonte
    // =========================================================

    private List<Article> fetchAllArticles() {
        List<Article> all = new ArrayList<>();
        all.addAll(fetchHackerNewsArticles());
        all.addAll(fetchDevToArticles());
        all.addAll(fetchLobstersArticles());
        all.addAll(fetchStackOverflowArticles());
        return all;
    }

    private List<Article> fetchHackerNewsArticles() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(HN_TOP_STORIES_URL))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return List.of();

            JsonNode idsNode = objectMapper.readTree(resp.body());
            List<Long> storyIds = new ArrayList<>();
            for (int i = 0; i < Math.min(MAX_HN_ARTICLES, idsNode.size()); i++) {
                storyIds.add(idsNode.get(i).asLong());
            }

            return storyIds.stream()
                    .map(id -> CompletableFuture.supplyAsync(() -> fetchHnItem(id),
                            httpClient.executor().orElse(Runnable::run)))
                    .map(CompletableFuture::join)
                    .filter(a -> a != null)
                    .filter(a -> !isOlderThanCeiling(a.publishedAt()))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Erro ao coletar Hacker News: {}", e.getMessage());
            return List.of();
        }
    }

    private Article fetchHnItem(long itemId) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(HN_ITEM_URL.formatted(itemId)))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode item = objectMapper.readTree(resp.body());
                if (item.has("title")) {
                    String title = item.get("title").asText();
                    String url = item.has("url") ? item.get("url").asText()
                            : "https://news.ycombinator.com/item?id=" + itemId;
                    String discussion = "https://news.ycombinator.com/item?id=" + itemId;
                    // Campo "time" da HN é epoch em segundos. Sem ele (item removido, etc.)
                    // deixamos null e o filtro de idade na camada de cima ainda roda.
                    Instant publishedAt = item.has("time") && item.get("time").isNumber()
                            ? Instant.ofEpochSecond(item.get("time").asLong())
                            : null;
                    return new Article(String.valueOf(itemId), title, url, discussion, "hackernews", publishedAt);
                }
            }
        } catch (Exception e) {
            log.debug("Erro ao buscar item HN {}: {}", itemId, e.getMessage());
        }
        return null;
    }

    /**
     * Verdadeiro se `publishedAt` é mais antigo que MAX_ARTICLE_AGE. Quando a data veio nula
     * (campo ausente na fonte, parse quebrado, etc.) aceitamos o artigo — o teto rígido na
     * query do feed (NodeRepository) ainda corta o pior do backfill.
     */
    private boolean isOlderThanCeiling(Instant publishedAt) {
        if (publishedAt == null) return false;
        return publishedAt.isBefore(Instant.now().minus(MAX_ARTICLE_AGE));
    }

    private List<Article> fetchDevToArticles() {
        List<Article> articles = new ArrayList<>();
        for (String tag : DEVTO_TAGS) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(DEVTO_URL.formatted(tag)))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) continue;

                JsonNode items = objectMapper.readTree(resp.body());
                if (!items.isArray()) continue;

                for (JsonNode item : items) {
                    String title = item.path("title").asText("");
                    if (title.isBlank()) continue;

                    String url = item.path("url").asText("");
                    String id = String.valueOf(item.path("id").asLong());
                    // Dev.to devolve ISO 8601 em "published_at"; às vezes pode estar ausente
                    // para rascunhos antigos. Parse tolerante — falha vira null.
                    Instant publishedAt = parseInstantOrNull(item.path("published_at").asText(""));
                    if (isOlderThanCeiling(publishedAt)) continue;
                    articles.add(new Article(id, title, url, url, "devto", publishedAt));
                }
            } catch (Exception e) {
                log.debug("Erro ao coletar Dev.to tag {}: {}", tag, e.getMessage());
            }
        }
        log.info("Dev.to: {} artigos coletados.", articles.size());
        return articles;
    }

    private List<Article> fetchLobstersArticles() {
        List<Article> articles = new ArrayList<>();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(LOBSTERS_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return List.of();

            JsonNode items = objectMapper.readTree(resp.body());
            if (!items.isArray()) return List.of();

            for (JsonNode item : items) {
                String title = item.path("title").asText("");
                if (title.isBlank()) continue;

                String url = item.path("url").asText("");
                String commentsUrl = item.path("comments_url").asText(url);
                String id = item.path("short_id").asText("");
                // /hottest.json é "all-time hottest", então sem este filtro a home fica
                // cheia de itens de anos atrás que viralizaram uma vez e nunca mais.
                Instant publishedAt = parseInstantOrNull(item.path("created_at").asText(""));
                if (isOlderThanCeiling(publishedAt)) continue;
                articles.add(new Article(id, title, url, commentsUrl, "lobsters", publishedAt));
            }
        } catch (Exception e) {
            log.debug("Erro ao coletar Lobsters: {}", e.getMessage());
        }
        log.info("Lobsters: {} artigos coletados.", articles.size());
        return articles;
    }

    private List<Article> fetchStackOverflowArticles() {
        List<Article> articles = new ArrayList<>();
        for (String tag : STACKOVERFLOW_TAGS) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(STACKOVERFLOW_URL.formatted(tag)))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();

                HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() != 200) continue;

                String body = decodeStackExchangeBody(resp.body());
                JsonNode items = objectMapper.readTree(body).path("items");
                if (!items.isArray()) continue;

                for (JsonNode item : items) {
                    String title = item.path("title").asText("");
                    if (title.isBlank()) continue;

                    String link = item.path("link").asText("");
                    String id = String.valueOf(item.path("question_id").asLong());
                    // SO devolve epoch em segundos em "creation_date". Com sort=activity, sem
                    // este filtro pegamos perguntas de anos atrás que só receberam um comentário.
                    Instant publishedAt = item.has("creation_date") && item.get("creation_date").isNumber()
                            ? Instant.ofEpochSecond(item.get("creation_date").asLong())
                            : null;
                    if (isOlderThanCeiling(publishedAt)) continue;
                    articles.add(new Article(id, title, link, link, "stackoverflow", publishedAt));
                }
            } catch (Exception e) {
                log.debug("Erro ao coletar StackOverflow tag {}: {}", tag, e.getMessage());
            }
        }
        log.info("StackOverflow: {} artigos coletados.", articles.size());
        return articles;
    }

    /**
     * Tenta parsear uma string ISO 8601 (ou vazia) em Instant. Retorna null em qualquer falha —
     * usada para campos de data opcionais nas respostas das fontes.
     */
    private static Instant parseInstantOrNull(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Instant.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A API do Stack Exchange sempre comprime a resposta em gzip, mesmo sem pedir — precisa
     * descomprimir manualmente antes de fazer o parse do JSON.
     */
    private String decodeStackExchangeBody(byte[] raw) throws IOException {
        try (var gzipStream = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return new String(gzipStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (ZipException e) {
            return new String(raw, StandardCharsets.UTF_8);
        }
    }

    // =========================================================
    // FASE 2: Extração via LLM
    // =========================================================

    private static final List<String> BLACKLIST = NodeRepository.BLACKLIST;

    /** Resultado da chamada ao LLM. `content` é null em caso de falha; `error`
     *  descreve a falha (null em caso de sucesso). Equivalente a um Result<String, String>
     *  para evitar um campo mutável compartilhado entre requests. */
    private record LlmCallResult(String content, String error) {
        boolean failed() { return content == null; }
    }

    /**
     * Faz uma chamada ao LLM e devolve o conteúdo já limpo. Em caso de falha, devolve
     * o motivo no campo `error` (per-call) — não escreve em campo de instância, então
     * requests concorrentes não se sobrescrevem.
     */
    private LlmCallResult requestLlmContent(String model, String systemPrompt, String userMessage) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "temperature", 0.1,
                    // Teto de saída + entrada tem que caber nos 8000 tokens/minuto do tier free
                    // da Groq, que reserva os dois contra o mesmo limite (413 rate_limit_exceeded
                    // se estourar). Com ~2,7k de entrada, isto deixa o request em ~6,7k.
                    "max_tokens", 4000,
                    "reasoning_effort", "low",
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userMessage)
                    )
            );

            HttpRequest llmReq = HttpRequest.newBuilder()
                    .uri(URI.create(llmApiUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + llmApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> llmResp = httpClient.send(llmReq, HttpResponse.BodyHandlers.ofString());

            if (llmResp.statusCode() != 200) {
                log.error("LLM API ({}) status {}. Body: {}", model, llmResp.statusCode(), llmResp.body());
                String body = llmResp.body();
                String error = "HTTP " + llmResp.statusCode() + " em " + model + ": "
                        + (body.length() > 400 ? body.substring(0, 400) : body);
                return new LlmCallResult(null, error);
            }

            JsonNode root = objectMapper.readTree(llmResp.body());
            String content = root.path("choices").get(0).path("message").path("content").asText("");
            content = content.replaceAll("```json", "").replaceAll("```", "").trim();
            log.info("Resposta bruta do LLM {} ({} chars): {}", model, content.length(),
                    content.length() > 200 ? content.substring(0, 200) + "..." : content);
            return new LlmCallResult(content, null);

        } catch (Exception e) {
            log.error("Erro na chamada ao LLM ({}): {}", model, e.getMessage(), e);
            return new LlmCallResult(null, model + ": " + e);
        }
    }

    /**
     * Amostra espaçada da lista coletada, para o prompt caber no limite de tokens por minuto
     * sem perder a mistura de fontes — elas chegam concatenadas (HN, Dev.to, Lobsters, SO),
     * então pegar os primeiros N deixaria as últimas fontes de fora da curadoria.
     */
    List<Article> sampleForPrompt(List<Article> articles) {
        if (articles.size() <= MAX_PROMPT_ARTICLES) return articles;

        List<Article> sample = new ArrayList<>(MAX_PROMPT_ARTICLES);
        double step = (double) articles.size() / MAX_PROMPT_ARTICLES;
        for (int i = 0; i < MAX_PROMPT_ARTICLES; i++) {
            sample.add(articles.get((int) (i * step)));
        }
        return sample;
    }

    private ExtractionResult callLlmForExtraction(List<Article> articles) {
        if (llmApiKey == null || llmApiKey.isBlank()) {
            log.warn("Chave de API do LLM não configurada. Usando extração por palavras-chave.");
            return extractByKeyword(articles, "GROQ_API_KEY ausente na configuração do serviço");
        }

        // Sem os links: a fonte de cada nó é resolvida localmente em parseLlmResponse, então
        // mandar URLs (e pedi-las de volta) só queimaria tokens de entrada e saída à toa.
        String articlesBlock = sampleForPrompt(articles).stream()
                .map(a -> "- [" + a.platform().toUpperCase() + "] " + a.title())
                .collect(Collectors.joining("\n"));

        log.debug("Artigos enviados ao LLM:\n{}", articlesBlock);

        String systemPrompt = """
                Você é um Curador Técnico Sênior especializado em Engenharia de Software e IA.
                Analise a lista de matérias/discussões de Hacker News, Reddit, Dev.to e Lobsters.
                Extraia ferramentas, linguagens, frameworks e modelos com ALTO VALOR DE APRENDIZADO.

                REGRAS OBRIGATÓRIAS:
                1. NÃO INCLUA termos genéricos (Linux, Mac, Windows, Software, Hardware, Web, Computer, Article, PDF).
                2. Use o label exatamente como ele aparece escrito no título da matéria.
                3. A lista contém matérias NÃO TÉCNICAS (história, política, saúde, drogas,
                   curiosidades, cultura pop) — o Hacker News publica isso na home. IGNORE essas
                   matérias por completo. Só entram no grafo ferramentas, linguagens, frameworks,
                   modelos e conceitos de engenharia de software e IA. Na dúvida, deixe de fora.

                Responda APENAS com JSON válido no formato:
                {
                  "nodes": [
                    {"label": "LangGraph", "category": "Framework"}
                  ],
                  "edges": [
                    {"source": "LangGraph", "target": "LangChain", "relation": "PART_OF"}
                  ]
                }
                """;

        String userMessage = "Analise estas matérias e extraia o grafo de conhecimento:\n\n" + articlesBlock;

        LlmCallResult llmCall = requestLlmContent(llmModel, systemPrompt, userMessage);

        if (llmCall.failed() || !llmCall.content().trim().startsWith("{")) {
            log.error("Modelo {} não retornou JSON válido. Caindo para extração por palavras-chave. Resposta: '{}'",
                    llmModel, llmCall.content());
            String error = llmCall.error() != null
                    ? llmCall.error()
                    : "resposta sem JSON de " + llmModel;
            return extractByKeyword(articles, error);
        }

        return parseLlmResponse(llmCall.content(), articles);
    }

    /**
     * Cache de Patterns compilados por palavra. {@link #containsWord} roda em hot path
     * (uma vez por nó por artigo em {@code parseLlmResponse}, mais vezes em
     * {@code persistNodeArticles} e {@code findLiveSource}); recompilar o regex a cada
     * chamada virou o custo dominante dessa etapa. Cachear é transparante: o matcher
     * resultante é byte-a-byte igual ao original.
     */
    private static final ConcurrentHashMap<String, Pattern> WORD_PATTERNS = new ConcurrentHashMap<>();

    /**
     * Checa se `word` aparece em `text` como palavra inteira (não como substring dentro de
     * outra palavra). Evita que "Java" case dentro de "JavaScript" e vice-versa.
     */
    private boolean containsWord(String text, String word) {
        if (text == null || word == null || word.isBlank()) return false;
        Pattern pattern = WORD_PATTERNS.computeIfAbsent(word,
                w -> Pattern.compile("\\b" + Pattern.quote(w) + "\\b", Pattern.CASE_INSENSITIVE));
        return pattern.matcher(text).find();
    }

    /**
     * Índice pré-computado de label normalizado → artigo original. Permite que
     * {@link #findBestMatchingArticle} rode em O(1) em vez de varrer a lista inteira
     * de artigos a cada nó/aresta processado. Preserva "primeiro match vence" percorrendo
     * a lista de artigos em ordem original na etapa de construção do índice.
     */
    private Map<String, Article> indexArticlesByLabel(List<Article> articles) {
        Map<String, Article> index = new HashMap<>();
        if (articles == null) return index;
        for (Article article : articles) {
            if (article == null || article.title() == null) continue;
            String[] words = article.title().split("\\s+");
            for (String word : words) {
                String trimmed = word.toLowerCase();
                index.putIfAbsent(trimmed, article);
            }
        }
        return index;
    }

    /**
     * Versão indexada de {@link #findBestMatchingArticle}. Procura no mapa pré-computado
     * (em vez de iterar a lista) — equivalente em semântica quando o índice foi
     * construído sobre a mesma lista.
     */
    private Article findBestMatchingArticleFromIndex(String label, Map<String, Article> index) {
        if (label == null || label.isBlank() || index.isEmpty()) return null;
        String[] words = label.split("\\s+");
        for (String word : words) {
            Article match = index.get(word.toLowerCase());
            if (match != null) return match;
        }
        return null;
    }

    /**
     * Encontra o artigo original cujo título menciona o label extraído.
     * Usado para atribuir fonte real de forma confiável, sem depender do LLM reproduzir URLs.
     */
    private Article findBestMatchingArticle(String label, List<Article> articles) {
        return articles.stream()
                .filter(a -> containsWord(a.title(), label))
                .findFirst()
                .orElse(null);
    }

    /**
     * Busca uma fonte real ao vivo pra um tópico que não tem sourceUrl salvo, usando a API
     * pública de busca do Hacker News (Algolia). Usado sob demanda, no clique do usuário —
     * não depende do tópico ter aparecido por acaso num lote de ingestão.
     */
    public Article findLiveSource(String label) {
        try {
            String query = URLEncoder.encode(label, StandardCharsets.UTF_8);
            // hitsPerPage > 1 porque a relevância do Algolia não é palavra-inteira: uma busca
            // por "Java" costuma trazer resultados de "JavaScript" no topo. Filtramos abaixo.
            String url = "https://hn.algolia.com/api/v1/search?query=" + query
                    + "&tags=story&hitsPerPage=20";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;

            JsonNode hits = objectMapper.readTree(resp.body()).path("hits");
            if (!hits.isArray() || hits.isEmpty()) return null;

            for (JsonNode hit : hits) {
                String title = hit.path("title").asText("");
                if (title.isBlank() || !containsWord(title, label)) continue;

                String objectId = hit.path("objectID").asText("");
                String discussion = "https://news.ycombinator.com/item?id=" + objectId;
                String articleUrl = hit.path("url").asText(discussion);
                // Algolia devolve "created_at" como ISO 8601.
                Instant publishedAt = parseInstantOrNull(hit.path("created_at").asText(""));

                return new Article(objectId, title, articleUrl, discussion, "hackernews", publishedAt);
            }
            return null;
        } catch (Exception e) {
            log.debug("Erro ao buscar fonte ao vivo para '{}': {}", label, e.getMessage());
            return null;
        }
    }

    private ExtractionResult parseLlmResponse(String content, List<Article> articles) {
        try {
            JsonNode graphJson = objectMapper.readTree(content);

            Map<String, Article> articleIndex = indexArticlesByLabel(articles);

            List<NodeRequest> nodes = StreamSupport.stream(
                            graphJson.path("nodes").spliterator(), false)
                    .map(n -> {
                        String label = n.path("label").asText("Unknown").trim();
                        String category = n.path("category").asText("Technology").trim();

                        // A fonte vem do lote coletado, não do LLM: a URL que ele devolvia era
                        // descartada quando não batia com um artigo real (alucinação), e o resto
                        // dos campos já saía daqui. Sem fonte, o frontend busca uma sob demanda.
                        Article match = findBestMatchingArticleFromIndex(label, articleIndex);
                        String sourceUrl = match != null ? match.discussionUrl() : "";
                        String sourceTitle = match != null ? match.title() : "Discussão na comunidade dev";
                        String sourcePlatform = match != null ? match.platform() : "web";

                        return new NodeRequest(label, category, null, sourceUrl, sourceTitle, sourcePlatform);
                    })
                    .filter(n -> !n.label().isBlank())
                    .filter(n -> !isBlacklisted(n.label()))
                    .toList();

            List<EdgeRequest> edges = StreamSupport.stream(
                            graphJson.path("edges").spliterator(), false)
                    .map(e -> new EdgeRequest(
                            e.path("source").asText().trim(),
                            e.path("target").asText().trim(),
                            e.path("relation").asText("RELATED_TO").trim()))
                    .filter(e -> !e.source().isBlank() && !e.target().isBlank())
                    .filter(e -> !isBlacklisted(e.source()) && !isBlacklisted(e.target()))
                    .toList();

            return new ExtractionResult(nodes, edges, null);

        } catch (Exception e) {
            log.error("Falha ao parsear resposta do LLM: {}", e.getMessage(), e);
            return ExtractionResult.withError("falha ao parsear JSON do LLM: " + e.getMessage());
        }
    }

    private static final String SUMMARY_PROMPT_EN = """
            Explain ONE specific technology technically, in 2-3 sentences, in English.
            Say what it IS and what it DOES — do not talk about "trends" or "recent discussions".

            BAD (do not do this): "React is a rising technology in the dev ecosystem, with recent discussions in the developer bubble."

            GOOD: "React is a JavaScript library for building declarative component-based interfaces, using a virtual DOM to optimize re-renders."
            """;

    private static final String SUMMARY_PROMPT_PT = """
            Explique tecnicamente UMA tecnologia específica em 2-3 frases em português.
            Diga o que ela É e o que ela FAZ — não fale sobre "tendências" ou "discussões recentes".

            RUIM (não faça isso): "React é uma tecnologia em ascensão no ecossistema dev, com discussões recentes na bolha de desenvolvimento."

            BOM: "React é uma biblioteca JavaScript para construir interfaces declarativas baseadas em componentes, usando um DOM virtual para otimizar re-renderizações."
            """;

    /**
     * Gera um resumo técnico e específico para UMA tecnologia sob demanda, no idioma pedido
     * ("pt" para português, qualquer outro valor cai no inglês, que é o padrão da UI).
     */
    public String generateTopicSummary(String label, String category, String sourceTitle, String sourceUrl, String lang) {
        if (llmApiKey == null || llmApiKey.isBlank()) {
            log.warn("Chave de API do LLM não configurada. Não é possível gerar resumo sob demanda.");
            return null;
        }

        boolean pt = "pt".equals(lang);
        String system = pt ? SUMMARY_PROMPT_PT : SUMMARY_PROMPT_EN;
        String user = (pt ? "Tecnologia: %s\nCategoria: %s\nContexto (artigo que a mencionou): \"%s\" (%s)"
                          : "Technology: %s\nCategory: %s\nContext (article that mentioned it): \"%s\" (%s)")
                .formatted(label, category, sourceTitle != null ? sourceTitle : "", sourceUrl != null ? sourceUrl : "");

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", llmModel,
                    "temperature", 0.4,
                    "max_tokens", 400,
                    "reasoning_effort", "low",
                    "messages", List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user)
                    )
            );

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(llmApiUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + llmApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                String content = root.path("choices").get(0).path("message").path("content").asText("");
                content = content.replaceAll("```markdown", "").replaceAll("```", "").trim();
                if (!content.isBlank()) {
                    return content;
                }
            } else {
                log.error("Erro ao gerar resumo para {}: status {}", label, resp.statusCode());
            }
        } catch (Exception e) {
            log.error("Erro ao gerar resumo para {}: {}", label, e.getMessage());
        }
        return null;
    }

    private boolean isBlacklisted(String label) {
        if (label == null || label.isBlank()) return true;
        String lower = label.toLowerCase().trim();
        return BLACKLIST.stream().anyMatch(b -> lower.equals(b) || lower.startsWith(b + " ") || lower.endsWith(" " + b));
    }

    private ExtractionResult extractByKeyword(List<Article> articles, String llmError) {
        List<String> titles = articles.stream().map(Article::title).toList();
        List<String> techKeywords = List.of(
                "AI", "LLM", "GPT", "Claude", "Gemini", "Llama", "Python", "Java", "JavaScript", "Rust",
                "TypeScript", "React", "Next.js", "Kubernetes", "Docker", "AWS", "GCP",
                "PostgreSQL", "Redis", "GraphQL", "WebAssembly", "Deno", "Bun", "Vite",
                "LangChain", "LangGraph", "OpenAI", "Anthropic", "Groq", "Mistral",
                "RAG", "Vector Database", "Embedding", "Agent", "MCP", "Spring Boot"
        );

        Map<String, Article> articleIndex = indexArticlesByLabel(articles);

        Map<String, Long> mentionCounts = techKeywords.stream()
                .filter(kw -> titles.stream().anyMatch(t -> containsWord(t, kw)))
                .collect(Collectors.groupingBy(kw -> kw, Collectors.counting()));

        List<NodeRequest> nodes = mentionCounts.entrySet().stream()
                .map(e -> {
                    Article match = findBestMatchingArticleFromIndex(e.getKey(), articleIndex);
                    if (match != null) {
                        return new NodeRequest(e.getKey(), categorize(e.getKey()), null,
                                match.discussionUrl(), match.title(), match.platform());
                    }
                    return new NodeRequest(e.getKey(), categorize(e.getKey()));
                })
                .toList();

        List<EdgeRequest> edges = new ArrayList<>();
        List<String> mentionedTerms = new ArrayList<>(mentionCounts.keySet());
        for (int i = 0; i < Math.min(mentionedTerms.size() - 1, 15); i++) {
            edges.add(new EdgeRequest(mentionedTerms.get(i), mentionedTerms.get(i + 1), "RELATED_TO"));
        }

        return new ExtractionResult(nodes, edges, llmError);
    }

    private String categorize(String term) {
        return switch (term.toLowerCase()) {
            case "python", "java", "javascript", "rust", "typescript", "deno", "bun" -> "Language";
            case "react", "next.js", "vite", "spring boot", "langchain", "langgraph" -> "Framework";
            case "docker", "kubernetes", "redis", "postgresql", "graphql" -> "Tool";
            case "aws", "gcp", "vercel", "supabase" -> "Platform";
            case "openai", "anthropic", "groq", "mistral" -> "Company";
            case "gpt", "claude", "gemini", "llama" -> "Model";
            default -> "Concept";
        };
    }

    // =========================================================
    // FASE 3: Persistência
    // =========================================================

    private int persistNodes(List<NodeRequest> nodes, List<Article> articles) {
        return transactionTemplate.execute(status -> {
            int count = 0;
            for (NodeRequest node : nodes) {
                try {
                    UUID nodeId = nodeRepository.upsertNode(node.label(), node.category(), node.summary(),
                            node.sourceUrl(), node.sourceTitle(), node.sourcePlatform());
                    persistNodeArticles(nodeId, node.label(), articles);
                    count++;
                } catch (Exception e) {
                    log.warn("Falha ao persistir nó '{}': {}", node.label(), e.getMessage());
                }
            }
            return count;
        });
    }

    /**
     * Linka ao tópico todos os artigos do lote atual cujo título o menciona (não só o primeiro),
     * para alimentar o feed de notícias. Limita a 20 por tópico por rodada de ingestão.
     */
    private void persistNodeArticles(UUID nodeId, String label, List<Article> articles) {
        if (nodeId == null) return;
        articles.stream()
                .filter(a -> containsWord(a.title(), label))
                .limit(20)
                .forEach(a -> {
                    try {
                        nodeRepository.insertArticle(nodeId, a.title(), a.discussionUrl(), a.platform(), a.publishedAt());
                    } catch (Exception e) {
                        log.debug("Falha ao persistir artigo para '{}': {}", label, e.getMessage());
                    }
                });
    }

    private int persistEdges(List<EdgeRequest> edges, List<Article> articles) {
        return transactionTemplate.execute(status -> {
            // Coleta todas as labels referenciadas pelas arestas e resolve os IDs existentes
            // em uma única query batch. Antes era 2 SELECTs por aresta — para 50 arestas são
            // 100 round-trips ao Postgres, agora é 1.
            Set<String> referencedLabels = new HashSet<>();
            for (EdgeRequest edge : edges) {
                if (edge.source() != null && !edge.source().isBlank()) referencedLabels.add(edge.source());
                if (edge.target() != null && !edge.target().isBlank()) referencedLabels.add(edge.target());
            }
            Map<String, UUID> existingIds = nodeRepository.findIdsByLabels(referencedLabels);

            int count = 0;
            for (EdgeRequest edge : edges) {
                try {
                    UUID sourceId = lookupOrCreate(edge.source(), existingIds, articles);
                    UUID targetId = lookupOrCreate(edge.target(), existingIds, articles);

                    if (sourceId != null && targetId != null && !sourceId.equals(targetId)) {
                        edgeRepository.upsertEdge(sourceId, targetId, edge.relation());
                        count++;
                    }
                } catch (Exception e) {
                    log.warn("Falha ao persistir aresta '{}→{}': {}", edge.source(), edge.target(), e.getMessage());
                }
            }
            return count;
        });
    }

    /**
     * Resolve o UUID de um label a partir do mapa pré-carregado; quando ausente, cria um
     * nó órfão e popula o mapa para que chamadas subsequentes com o mesmo label não
     * repitam o trabalho.
     */
    private UUID lookupOrCreate(String label, Map<String, UUID> cache, List<Article> articles) {
        if (label == null || label.isBlank()) return null;
        String key = label.toLowerCase();
        UUID existing = cache.get(key);
        if (existing != null) return existing;

        UUID created = createOrphanNode(label, articles);
        if (created != null) {
            cache.put(key, created);
        }
        return created;
    }

    /**
     * Cria um nó referenciado só numa aresta (o LLM não o incluiu em `nodes`). Tenta achar a
     * fonte real no mesmo lote de artigos já coletado; deixa o resumo em branco (em vez de um
     * texto genérico fixo) para que o frontend ainda dispare a busca de fonte sob demanda depois.
     */
    private UUID createOrphanNode(String label, List<Article> articles) {
        Article match = findBestMatchingArticle(label, articles);
        String sourceUrl = match != null ? match.discussionUrl() : null;
        String sourceTitle = match != null ? match.title() : null;
        String sourcePlatform = match != null ? match.platform() : null;
        UUID nodeId = nodeRepository.upsertNode(label, "Concept", null, sourceUrl, sourceTitle, sourcePlatform);
        persistNodeArticles(nodeId, label, articles);
        return nodeId;
    }
}
