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

    /**
     * Pipeline principal: busca artigos do HN → extrai grafo via LLM → persiste no Supabase.
     */
    @Transactional
    public ExtractionResult runIngestionPipeline() {
        log.info("Iniciando pipeline de ingestão do Hacker News...");

        List<String> titles = fetchHackerNewsTitles();
        if (titles.isEmpty()) {
            log.warn("Nenhum título coletado do Hacker News. Abortando pipeline.");
            return ExtractionResult.empty();
        }
        log.info("Coletados {} títulos do Hacker News.", titles.size());

        ExtractionResult extracted = callLlmForExtraction(titles);
        log.info("LLM extraiu {} nós e {} arestas.", extracted.nodes().size(), extracted.edges().size());

        int nodesCreated = persistNodes(extracted.nodes());
        int edgesCreated = persistEdges(extracted.edges());

        log.info("Pipeline concluído. Nós persistidos: {}, Arestas persistidas: {}", nodesCreated, edgesCreated);
        return new ExtractionResult(extracted.nodes(), extracted.edges());
    }

    // =========================================================
    // FASE 1: Coleta do Hacker News
    // =========================================================

    private List<String> fetchHackerNewsTitles() {
        try {
            // 1a. Busca IDs das top stories
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

            // 1b. Busca títulos em paralelo usando CompletableFuture (Java 21)
            List<CompletableFuture<String>> futures = storyIds.stream()
                    .map(id -> CompletableFuture.supplyAsync(() -> fetchItemTitle(id), httpClient.executor().orElse(Runnable::run)))
                    .toList();

            return futures.stream()
                    .map(CompletableFuture::join)
                    .filter(title -> title != null && !title.isBlank())
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Erro ao coletar artigos do Hacker News: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private String fetchItemTitle(long itemId) {
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
                    return item.get("title").asText();
                }
            }
        } catch (Exception e) {
            log.debug("Erro ao buscar item HN {}: {}", itemId, e.getMessage());
        }
        return null;
    }

    // =========================================================
    // FASE 2: Extração via LLM (OpenAI / Groq)
    // =========================================================

    private ExtractionResult callLlmForExtraction(List<String> titles) {
        if (llmApiKey == null || llmApiKey.isBlank()) {
            log.warn("Chave de API do LLM não configurada. Usando extração baseada em palavras-chave.");
            return extractByKeyword(titles);
        }

        String titlesBlock = titles.stream()
                .map(t -> "- " + t)
                .collect(Collectors.joining("\n"));

        String systemPrompt = """
                Você é um especialista em tecnologia e ecossistema de desenvolvimento de software.
                Analise os títulos de artigos técnicos fornecidos e extraia um grafo de conhecimento.
                
                REGRAS ESTRITAS:
                1. Identifique apenas tecnologias, frameworks, linguagens, ferramentas, empresas e conceitos técnicos reais.
                2. Cada nó deve ter: "label" (nome exato, max 50 chars) e "category" (uma de: Language, Framework, Tool, Platform, Concept, Company, Model).
                3. Cada aresta representa uma relação semântica real entre os conceitos.
                4. Tipos de relação permitidos: USES, COMPETES_WITH, EVOLVED_FROM, PART_OF, REPLACES, INTEGRATES_WITH, RUNS_ON, RELATED_TO.
                5. Extraia no mínimo 5 nós e 4 arestas. Máximo: 20 nós e 25 arestas.
                6. Responda APENAS com JSON válido, sem markdown, sem explicações extras.
                
                FORMATO DE RESPOSTA OBRIGATÓRIO:
                {
                  "nodes": [
                    {"label": "NomeDaTecnologia", "category": "Framework"}
                  ],
                  "edges": [
                    {"source": "NomeDaTecnologia", "target": "OutroConceito", "relation": "USES"}
                  ]
                }
                """;

        String userMessage = "Analise estes títulos de artigos do Hacker News e extraia o grafo de conceitos técnicos:\n\n" + titlesBlock;

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", llmModel,
                    "temperature", 0.1,
                    "max_tokens", 2000,
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
                return extractByKeyword(titles);
            }

            return parseLlmResponse(llmResp.body());

        } catch (Exception e) {
            log.error("Erro na chamada ao LLM: {}", e.getMessage(), e);
            return extractByKeyword(titles);
        }
    }

    private ExtractionResult parseLlmResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String content = root
                    .path("choices").get(0)
                    .path("message")
                    .path("content")
                    .asText();

            // Remove possíveis blocos de markdown se o LLM ignorou as instruções
            content = content.replaceAll("```json", "").replaceAll("```", "").trim();

            JsonNode graphJson = objectMapper.readTree(content);

            List<NodeRequest> nodes = StreamSupport.stream(
                            graphJson.path("nodes").spliterator(), false)
                    .map(n -> new NodeRequest(
                            n.path("label").asText("Unknown"),
                            n.path("category").asText("Technology")))
                    .filter(n -> !n.label().isBlank())
                    .toList();

            List<EdgeRequest> edges = StreamSupport.stream(
                            graphJson.path("edges").spliterator(), false)
                    .map(e -> new EdgeRequest(
                            e.path("source").asText(),
                            e.path("target").asText(),
                            e.path("relation").asText("RELATED_TO")))
                    .filter(e -> !e.source().isBlank() && !e.target().isBlank())
                    .toList();

            return new ExtractionResult(nodes, edges);

        } catch (Exception e) {
            log.error("Falha ao parsear resposta do LLM: {}", e.getMessage(), e);
            return ExtractionResult.empty();
        }
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
                nodeRepository.upsertNode(node.label(), node.category());
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
                    nodeRepository.upsertNode(edge.source(), "Concept");
                    sourceId = nodeRepository.findIdByLabel(edge.source()).orElse(null);
                }
                if (targetId == null) {
                    log.warn("Nó de destino '{}' não encontrado. Criando com categoria 'Concept'.", edge.target());
                    nodeRepository.upsertNode(edge.target(), "Concept");
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
