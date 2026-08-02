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
    private static final int MAX_ARTICLES = 30;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final NodeRepository nodeRepository;
    private final EdgeRepository edgeRepository;

    @Value("${openai.api.key:${GROQ_API_KEY:}}")
    private String llmApiKey;

    @Value("${openai.api.url:https://api.groq.com/openai/v1/chat/completions}")
    private String llmApiUrl;

    @Value("${openai.model:llama-3.1-8b-instant}")
    private String llmModel;

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

    // Record para armazenar dados completos da matéria do HN
    public record HnArticle(long id, String title, String url, String hnUrl) {}

    /**
     * Pipeline principal: busca artigos do HN → extrai grafo via LLM → persiste no Supabase.
     */
    @Transactional
    public ExtractionResult runIngestionPipeline() {
        log.info("Iniciando pipeline de ingestão do Hacker News com resumos específicos...");

        List<HnArticle> articles = fetchHackerNewsArticles();
        if (articles.isEmpty()) {
            log.warn("Nenhum artigo coletado do Hacker News. Abortando pipeline.");
            return ExtractionResult.empty();
        }
        log.info("Coletados {} artigos com links do Hacker News.", articles.size());

        ExtractionResult extracted = callLlmForExtraction(articles);
        log.info("LLM extraiu {} nós específicos e {} arestas.", extracted.nodes().size(), extracted.edges().size());

        int nodesCreated = persistNodes(extracted.nodes());
        int edgesCreated = persistEdges(extracted.edges());

        log.info("Pipeline concluído. Nós persistidos: {}, Arestas persistidas: {}", nodesCreated, edgesCreated);
        return new ExtractionResult(extracted.nodes(), extracted.edges());
    }

    // =========================================================
    // FASE 1: Coleta do Hacker News (Com URLs reais)
    // =========================================================

    private List<HnArticle> fetchHackerNewsArticles() {
        try {
            HttpRequest topStoriesReq = HttpRequest.newBuilder()
                    .uri(URI.create(HN_TOP_STORIES_URL))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> topStoriesResp = httpClient.send(
                    topStoriesReq, HttpResponse.BodyHandlers.ofString());

            if (topStoriesResp.statusCode() != 200) {
                log.error("Falha ao buscar top stories do HN. Status: {}", topStoriesResp.statusCode());
                return List.of();
            }

            JsonNode idsNode = objectMapper.readTree(topStoriesResp.body());
            List<Long> storyIds = new ArrayList<>();
            for (int i = 0; i < Math.min(MAX_ARTICLES, idsNode.size()); i++) {
                storyIds.add(idsNode.get(i).asLong());
            }

            List<CompletableFuture<HnArticle>> futures = storyIds.stream()
                    .map(id -> CompletableFuture.supplyAsync(() -> fetchItemArticle(id), httpClient.executor().orElse(Runnable::run)))
                    .toList();

            return futures.stream()
                    .map(CompletableFuture::join)
                    .filter(article -> article != null && article.title() != null && !article.title().isBlank())
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Erro ao coletar artigos do Hacker News: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private HnArticle fetchItemArticle(long itemId) {
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
                    String articleUrl = item.has("url") ? item.get("url").asText() : "https://news.ycombinator.com/item?id=" + itemId;
                    String hnDiscussionUrl = "https://news.ycombinator.com/item?id=" + itemId;
                    return new HnArticle(itemId, title, articleUrl, hnDiscussionUrl);
                }
            }
        } catch (Exception e) {
            log.debug("Erro ao buscar item HN {}: {}", itemId, e.getMessage());
        }
        return null;
    }

    // =========================================================
    // FASE 2: Extração via LLM (Resumos Específicos & Únicos)
    // =========================================================

    // Lista de termos genéricos banidos para garantir foco em materiais de estudo
    private static final List<String> BLACKLIST = List.of(
            "mac", "macos", "linux", "windows", "unix", "pc", "computer", "software",
            "hardware", "internet", "web", "news", "show hn", "ask hn", "pdf", "article",
            "blog", "system", "file", "code", "tech", "technology", "data", "app"
    );

    private ExtractionResult callLlmForExtraction(List<HnArticle> articles) {
        if (llmApiKey == null || llmApiKey.isBlank()) {
            log.warn("Chave de API do LLM não configurada. Usando extração baseada em palavras-chave.");
            return extractByKeyword(articles.stream().map(HnArticle::title).toList());
        }

        String articlesBlock = articles.stream()
                .map(a -> "- TÍTULO: " + a.title() + " | LINK: " + a.hnUrl())
                .collect(Collectors.joining("\n"));

        String systemPrompt = """
                Você é um Curador Técnico Sênior especializado em Engenharia de Software e IA.
                Analise a lista de matérias/discussões fornecidas e extraia as principais ferramentas, linguagens, frameworks e modelos com ALTO VALOR DE APRENDIZADO.
                
                REGRAS OBRIGATÓRIAS DE CONTEÚDO:
                1. NÃO INCLUA termos genéricos (Linux, Mac, Windows, Software, Hardware, Web, Computer, Article, PDF).
                2. CADA NÓ DEVE TER UM "summary" ÚNICO, TÉCNICO E ESPECÍFICO (1 a 2 frases em Português) explicando exatamente O QUE É essa tecnologia, O QUE ELA FAZ e POR QUE VALE A PENA ESTUDAR.
                3. NUNCA REPITA o mesmo resumo para tecnologias diferentes! Cada tecnologia deve ter seu próprio resumo explicativo.
                4. Associe cada nó ao "sourceUrl" do artigo correspondente.
                
                EXEMPLO DE RESPOSTA ESPERADA:
                {
                  "nodes": [
                    {
                      "label": "LangGraph",
                      "category": "Framework",
                      "summary": "Framework em Python e TypeScript para criar agentes de IA com estado persistente e fluxos cíclicos avançados.",
                      "sourceUrl": "https://news.ycombinator.com/item?id=39123456",
                      "sourceTitle": "LangGraph v0.2 Release"
                    },
                    {
                      "label": "vLLM",
                      "category": "Tool",
                      "summary": "Biblioteca de alta performance para inferência e servimento de LLMs com gerenciamento otimizado de memória via PagedAttention.",
                      "sourceUrl": "https://news.ycombinator.com/item?id=39876543",
                      "sourceTitle": "vLLM Fast LLM Serving"
                    }
                  ],
                  "edges": [
                    {"source": "LangGraph", "target": "vLLM", "relation": "INTEGRATES_WITH"}
                  ]
                }
                """;

        String userMessage = "Analise estas matérias do Hacker News e extraia o grafo de conhecimento com resumos únicos e específicos para estudo:\n\n" + articlesBlock;

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", llmModel,
                    "temperature", 0.1,
                    "max_tokens", 2500,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userMessage)
                    )
            );

            String bodyJson = objectMapper.writeValueAsString(requestBody);

            HttpRequest llmReq = HttpRequest.newBuilder()
                    .uri(URI.create(llmApiUrl))
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + llmApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> llmResp = httpClient.send(llmReq, HttpResponse.BodyHandlers.ofString());

            if (llmResp.statusCode() != 200) {
                log.error("LLM API retornou status {}. Body: {}", llmResp.statusCode(), llmResp.body());
                return extractByKeyword(articles.stream().map(HnArticle::title).toList());
            }

            return parseLlmResponse(llmResp.body(), articles);

        } catch (Exception e) {
            log.error("Erro na chamada ao LLM: {}", e.getMessage(), e);
            return extractByKeyword(articles.stream().map(HnArticle::title).toList());
        }
    }

    private ExtractionResult parseLlmResponse(String responseBody, List<HnArticle> articles) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String content = root
                    .path("choices").get(0)
                    .path("message")
                    .path("content")
                    .asText();

            content = content.replaceAll("```json", "").replaceAll("```", "").trim();

            JsonNode graphJson = objectMapper.readTree(content);

            List<NodeRequest> nodes = StreamSupport.stream(
                            graphJson.path("nodes").spliterator(), false)
                    .map(n -> {
                        String label = n.path("label").asText("Unknown").trim();
                        String category = n.path("category").asText("Technology").trim();
                        String summary = n.path("summary").asText("").trim();
                        String sourceUrl = n.path("sourceUrl").asText("").trim();
                        String sourceTitle = n.path("sourceTitle").asText("Discussão no Hacker News").trim();

                        if (summary.isBlank() || summary.length() < 15) {
                            summary = buildDefaultSummary(label, category);
                        }
                        if (sourceUrl.isBlank()) {
                            sourceUrl = articles.isEmpty() ? "https://news.ycombinator.com" : articles.get(0).hnUrl();
                        }

                        return new NodeRequest(label, category, summary, sourceUrl, sourceTitle);
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

    /**
     * Fallback: extração simples por palavras-chave quando o LLM não está disponível.
     */
    private ExtractionResult extractByKeyword(List<String> titles) {
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
                .map(e -> new NodeRequest(e.getKey(), categorize(e.getKey())))
                .toList();

        // Cria arestas básicas para nós que co-ocorrem nos mesmos títulos
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
    // FASE 3: Persistência no Supabase via Spring Data
    // =========================================================

    private int persistNodes(List<NodeRequest> nodes) {
        int count = 0;
        for (NodeRequest node : nodes) {
            try {
                nodeRepository.upsertNode(node.label(), node.category(), node.summary(), node.sourceUrl(), node.sourceTitle());
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
                    log.warn("Nó de origem '{}' não encontrado. Criando com categoria 'Concept'.", edge.source());
                    nodeRepository.upsertNode(edge.source(), "Concept", "Conceito em destaque no ecossistema.", null, null);
                    sourceId = nodeRepository.findIdByLabel(edge.source()).orElse(null);
                }
                if (targetId == null) {
                    log.warn("Nó de destino '{}' não encontrado. Criando com categoria 'Concept'.", edge.target());
                    nodeRepository.upsertNode(edge.target(), "Concept", "Conceito em destaque no ecossistema.", null, null);
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
