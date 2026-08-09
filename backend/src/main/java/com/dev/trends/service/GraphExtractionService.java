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
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class GraphExtractionService {

    private static final Logger log = LoggerFactory.getLogger(GraphExtractionService.class);

    private static final String HN_TOP_STORIES_URL = "https://hacker-news.firebaseio.com/v0/topstories.json";
    private static final String HN_ITEM_URL = "https://hacker-news.firebaseio.com/v0/item/%d.json";
    private static final String REDDIT_BASE = "https://www.reddit.com/r/%s/hot.json?limit=4";
    private static final String DEVTO_URL = "https://dev.to/api/articles?tag=%s&top=7&per_page=4";
    private static final String LOBSTERS_URL = "https://lobste.rs/hottest.json";
    private static final String USER_AGENT = "dev-trends-graph/1.0 (tech trends aggregator)";

    private static final List<String> REDDIT_SUBREDDITS = List.of(
            "programming", "MachineLearning", "webdev", "devops",
            "LocalLLaMA", "golang", "rust", "ExperiencedDevs", "artificial"
    );
    private static final List<String> DEVTO_TAGS = List.of("ai", "javascript", "rust", "devops", "webdev");

    private static final int MAX_HN_ARTICLES = 25;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final NodeRepository nodeRepository;
    private final EdgeRepository edgeRepository;

    @Value("${openai.api.key:${GROQ_API_KEY:}}")
    private String llmApiKey;

    @Value("${openai.api.url:https://api.groq.com/openai/v1/chat/completions}")
    private String llmApiUrl;

    @Value("${openai.model:openai/gpt-oss-20b}")
    private String llmModel;

    private static final String FALLBACK_LLM_MODEL = "qwen/qwen3.6-27b";

    public GraphExtractionService(
            NodeRepository nodeRepository,
            EdgeRepository edgeRepository,
            ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Artigo coletado de qualquer plataforma. */
    public record Article(String id, String title, String url, String discussionUrl, String platform) {}

    /**
     * Pipeline principal: busca artigos de múltiplas fontes → extrai grafo via LLM → persiste.
     */
    @Transactional
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

        int nodesCreated = persistNodes(extracted.nodes());
        int edgesCreated = persistEdges(extracted.edges());

        log.info("Pipeline concluído. Nós: {}, Arestas: {}", nodesCreated, edgesCreated);
        return new ExtractionResult(extracted.nodes(), extracted.edges());
    }

    // =========================================================
    // FASE 1: Coleta multi-fonte
    // =========================================================

    private List<Article> fetchAllArticles() {
        List<Article> all = new ArrayList<>();
        all.addAll(fetchHackerNewsArticles());
        all.addAll(fetchRedditArticles());
        all.addAll(fetchDevToArticles());
        all.addAll(fetchLobstersArticles());
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
                    return new Article(String.valueOf(itemId), title, url, discussion, "hackernews");
                }
            }
        } catch (Exception e) {
            log.debug("Erro ao buscar item HN {}: {}", itemId, e.getMessage());
        }
        return null;
    }

    private List<Article> fetchRedditArticles() {
        List<Article> articles = new ArrayList<>();
        for (String sub : REDDIT_SUBREDDITS) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(REDDIT_BASE.formatted(sub)))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) continue;

                JsonNode children = objectMapper.readTree(resp.body())
                        .path("data").path("children");

                for (JsonNode child : children) {
                    JsonNode data = child.path("data");
                    String title = data.path("title").asText("");
                    if (title.isBlank()) continue;

                    String permalink = data.path("permalink").asText("");
                    String discussion = permalink.startsWith("http")
                            ? permalink : "https://www.reddit.com" + permalink;
                    String url = data.path("url").asText(discussion);
                    String id = data.path("id").asText("");

                    articles.add(new Article(id, title, url, discussion, "reddit"));
                }
            } catch (Exception e) {
                log.debug("Erro ao coletar r/{}: {}", sub, e.getMessage());
            }
        }
        log.info("Reddit: {} artigos coletados.", articles.size());
        return articles;
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
                    articles.add(new Article(id, title, url, url, "devto"));
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
                articles.add(new Article(id, title, url, commentsUrl, "lobsters"));
            }
        } catch (Exception e) {
            log.debug("Erro ao coletar Lobsters: {}", e.getMessage());
        }
        log.info("Lobsters: {} artigos coletados.", articles.size());
        return articles;
    }

    // =========================================================
    // FASE 2: Extração via LLM
    // =========================================================

    private static final List<String> BLACKLIST = List.of(
            "mac", "macos", "linux", "windows", "unix", "pc", "computer", "software",
            "hardware", "internet", "web", "news", "show hn", "ask hn", "pdf", "article",
            "blog", "system", "file", "code", "tech", "technology", "data", "app"
    );

    /**
     * Faz uma chamada ao LLM com o modelo especificado e retorna o texto já limpo do campo
     * "content" (sem fences de markdown). Retorna null em caso de erro de rede/API.
     */
    private String requestLlmContent(String model, String systemPrompt, String userMessage) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "temperature", 0.1,
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
                return null;
            }

            JsonNode root = objectMapper.readTree(llmResp.body());
            String content = root.path("choices").get(0).path("message").path("content").asText("");
            content = content.replaceAll("```json", "").replaceAll("```", "").trim();
            log.info("Resposta bruta do LLM {} ({} chars): {}", model, content.length(),
                    content.length() > 200 ? content.substring(0, 200) + "..." : content);
            return content;

        } catch (Exception e) {
            log.error("Erro na chamada ao LLM ({}): {}", model, e.getMessage(), e);
            return null;
        }
    }

    private ExtractionResult callLlmForExtraction(List<Article> articles) {
        if (llmApiKey == null || llmApiKey.isBlank()) {
            log.warn("Chave de API do LLM não configurada. Usando extração por palavras-chave.");
            return extractByKeyword(articles);
        }

        String articlesBlock = articles.stream()
                .map(a -> "- [" + a.platform().toUpperCase() + "] TÍTULO: " + a.title()
                        + " | LINK: " + a.discussionUrl())
                .collect(Collectors.joining("\n"));

        log.debug("Artigos enviados ao LLM:\n{}", articlesBlock);

        String systemPrompt = """
                Você é um Curador Técnico Sênior especializado em Engenharia de Software e IA.
                Analise a lista de matérias/discussões de Hacker News, Reddit, Dev.to e Lobsters.
                Extraia ferramentas, linguagens, frameworks e modelos com ALTO VALOR DE APRENDIZADO.

                REGRAS OBRIGATÓRIAS:
                1. NÃO INCLUA termos genéricos (Linux, Mac, Windows, Software, Hardware, Web, Computer, Article, PDF).
                2. Associe cada nó ao "sourceUrl" e "sourceTitle" do artigo correspondente.
                3. Inclua "sourcePlatform" com o valor exato da plataforma: hackernews, reddit, devto ou lobsters.

                Responda APENAS com JSON válido no formato:
                {
                  "nodes": [
                    {
                      "label": "LangGraph",
                      "category": "Framework",
                      "sourceUrl": "https://...",
                      "sourceTitle": "Título do post original",
                      "sourcePlatform": "reddit"
                    }
                  ],
                  "edges": [
                    {"source": "LangGraph", "target": "LangChain", "relation": "PART_OF"}
                  ]
                }
                """;

        String userMessage = "Analise estas matérias e extraia o grafo de conhecimento:\n\n" + articlesBlock;

        String content = requestLlmContent(llmModel, systemPrompt, userMessage);

        if (content == null || !content.trim().startsWith("{")) {
            log.warn("Modelo {} não retornou JSON válido (resposta: '{}'). Tentando modelo de fallback {}.",
                    llmModel, content, FALLBACK_LLM_MODEL);
            content = requestLlmContent(FALLBACK_LLM_MODEL, systemPrompt, userMessage);
        }

        if (content == null || !content.trim().startsWith("{")) {
            log.error("Nenhum modelo retornou JSON válido. Caindo para extração por palavras-chave. Última resposta: '{}'", content);
            return extractByKeyword(articles);
        }

        return parseLlmResponse(content, articles);
    }

    /**
     * Encontra o artigo original cujo título menciona o label extraído.
     * Usado para atribuir fonte real de forma confiável, sem depender do LLM reproduzir URLs.
     */
    private Article findBestMatchingArticle(String label, List<Article> articles) {
        String needle = label.toLowerCase();
        return articles.stream()
                .filter(a -> a.title().toLowerCase().contains(needle))
                .findFirst()
                .orElse(null);
    }

    private ExtractionResult parseLlmResponse(String content, List<Article> articles) {
        try {
            JsonNode graphJson = objectMapper.readTree(content);

            // Mapa de URLs por plataforma para fallback
            Map<String, Article> urlIndex = new LinkedHashMap<>();
            for (Article a : articles) {
                urlIndex.put(a.discussionUrl(), a);
            }

            List<NodeRequest> nodes = StreamSupport.stream(
                            graphJson.path("nodes").spliterator(), false)
                    .map(n -> {
                        String label = n.path("label").asText("Unknown").trim();
                        String category = n.path("category").asText("Technology").trim();
                        String summary = n.has("summary") && !n.path("summary").asText("").trim().isBlank() 
                                ? n.path("summary").asText().trim() : null;
                        String sourceUrl = n.path("sourceUrl").asText("").trim();
                        String sourceTitle = n.path("sourceTitle").asText("").trim();
                        String sourcePlatform = n.path("sourcePlatform").asText("").trim();

                        // Não confia cegamente na URL que o LLM devolveu — só aceita se ela existir de fato
                        // entre os artigos coletados (evita URL alucinada).
                        if (!sourceUrl.isBlank() && !urlIndex.containsKey(sourceUrl)) {
                            sourceUrl = "";
                        }

                        if (sourceUrl.isBlank()) {
                            Article match = findBestMatchingArticle(label, articles);
                            if (match != null) {
                                sourceUrl = match.discussionUrl();
                                sourceTitle = match.title();
                                sourcePlatform = match.platform();
                            }
                        }

                        if (sourcePlatform.isBlank()) {
                            sourcePlatform = detectPlatformFromUrl(sourceUrl);
                        }
                        if (sourceTitle.isBlank()) {
                            sourceTitle = "Discussão na comunidade dev";
                        }

                        return new NodeRequest(label, category, summary, sourceUrl, sourceTitle, sourcePlatform);
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

            return new ExtractionResult(nodes, edges);

        } catch (Exception e) {
            log.error("Falha ao parsear resposta do LLM: {}", e.getMessage(), e);
            return ExtractionResult.empty();
        }
    }

    /**
     * Gera um resumo técnico e específico em português para UMA tecnologia sob demanda.
     */
    public String generateTopicSummary(String label, String category, String sourceTitle, String sourceUrl) {
        if (llmApiKey == null || llmApiKey.isBlank()) {
            log.warn("Chave de API do LLM não configurada. Não é possível gerar resumo sob demanda.");
            return null;
        }

        String system = """
                Explique tecnicamente UMA tecnologia específica em 2-3 frases em português.
                Diga o que ela É e o que ela FAZ — não fale sobre "tendências" ou "discussões recentes".

                RUIM (não faça isso): "React é uma tecnologia em ascensão no ecossistema dev, com discussões recentes na bolha de desenvolvimento."

                BOM: "React é uma biblioteca JavaScript para construir interfaces declarativas baseadas em componentes, usando um DOM virtual para otimizar re-renderizações."
                """;
        String user = "Tecnologia: %s\nCategoria: %s\nContexto (artigo que a mencionou): \"%s\" (%s)"
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

    private String detectPlatformFromUrl(String url) {
        if (url == null || url.isBlank()) return "web";
        if (url.contains("reddit.com")) return "reddit";
        if (url.contains("news.ycombinator.com")) return "hackernews";
        if (url.contains("dev.to")) return "devto";
        if (url.contains("lobste.rs")) return "lobsters";
        return "web";
    }

    private String buildDefaultSummary(String label, String category) {
        return switch (category.toLowerCase()) {
            case "framework" -> label + " é um framework em alta focado em escalabilidade, arquitetura limpa e produtividade.";
            case "tool", "platform" -> label + " é uma ferramenta/plataforma essencial para otimização de workflow, infraestrutura e devops.";
            case "model" -> label + " é um modelo de Inteligência Artificial emergente com capacidades avançadas de raciocínio e geração.";
            case "language" -> label + " é uma linguagem/runtime moderna com foco em performance, segurança de memória e concorrência.";
            default -> label + " é um conceito/tecnologia em destaque nas discussões recentes de engenharia de software.";
        };
    }

    private boolean isBlacklisted(String label) {
        if (label == null || label.isBlank()) return true;
        String lower = label.toLowerCase().trim();
        return BLACKLIST.stream().anyMatch(b -> lower.equals(b) || lower.startsWith(b + " ") || lower.endsWith(" " + b));
    }

    private ExtractionResult extractByKeyword(List<Article> articles) {
        List<String> titles = articles.stream().map(Article::title).toList();
        List<String> techKeywords = List.of(
                "AI", "LLM", "GPT", "Claude", "Gemini", "Llama", "Python", "Java", "Rust",
                "TypeScript", "React", "Next.js", "Kubernetes", "Docker", "AWS", "GCP",
                "PostgreSQL", "Redis", "GraphQL", "WebAssembly", "Deno", "Bun", "Vite",
                "LangChain", "LangGraph", "OpenAI", "Anthropic", "Groq", "Mistral",
                "RAG", "Vector Database", "Embedding", "Agent", "MCP", "Spring Boot"
        );

        Map<String, Long> mentionCounts = techKeywords.stream()
                .filter(kw -> titles.stream().anyMatch(t -> t.toLowerCase().contains(kw.toLowerCase())))
                .collect(Collectors.groupingBy(kw -> kw, Collectors.counting()));

        List<NodeRequest> nodes = mentionCounts.entrySet().stream()
                .map(e -> {
                    Article match = findBestMatchingArticle(e.getKey(), articles);
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

        return new ExtractionResult(nodes, edges);
    }

    private String categorize(String term) {
        return switch (term.toLowerCase()) {
            case "python", "java", "rust", "typescript", "deno", "bun" -> "Language";
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

    private int persistNodes(List<NodeRequest> nodes) {
        int count = 0;
        for (NodeRequest node : nodes) {
            try {
                nodeRepository.upsertNode(node.label(), node.category(), node.summary(),
                        node.sourceUrl(), node.sourceTitle(), node.sourcePlatform());
                count++;
            } catch (Exception e) {
                log.warn("Falha ao persistir nó '{}': {}", node.label(), e.getMessage());
            }
        }
        return count;
    }

    private int persistEdges(List<EdgeRequest> edges) {
        int count = 0;
        for (EdgeRequest edge : edges) {
            try {
                UUID sourceId = nodeRepository.findIdByLabel(edge.source()).orElse(null);
                UUID targetId = nodeRepository.findIdByLabel(edge.target()).orElse(null);

                if (sourceId == null) {
                    nodeRepository.upsertNode(edge.source(), "Concept", "Conceito em destaque no ecossistema.", null, null, null);
                    sourceId = nodeRepository.findIdByLabel(edge.source()).orElse(null);
                }
                if (targetId == null) {
                    nodeRepository.upsertNode(edge.target(), "Concept", "Conceito em destaque no ecossistema.", null, null, null);
                    targetId = nodeRepository.findIdByLabel(edge.target()).orElse(null);
                }

                if (sourceId != null && targetId != null && !sourceId.equals(targetId)) {
                    edgeRepository.upsertEdge(sourceId, targetId, edge.relation());
                    count++;
                }
            } catch (Exception e) {
                log.warn("Falha ao persistir aresta '{}→{}': {}", edge.source(), edge.target(), e.getMessage());
            }
        }
        return count;
    }
}
