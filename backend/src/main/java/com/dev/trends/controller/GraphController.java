package com.dev.trends.controller;

import com.dev.trends.model.ArticlePreview;
import com.dev.trends.model.Edge;
import com.dev.trends.model.Node;
import com.dev.trends.repository.EdgeRepository;
import com.dev.trends.repository.NodeRepository;
import com.dev.trends.service.GraphExtractionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controller REST que expõe os endpoints do grafo de tendências.
 * Configurado para ser consumido pelo frontend na Vercel (CORS habilitado).
 */
@RestController
@CrossOrigin(origins = "*", maxAge = 3600)
public class GraphController {

    private final NodeRepository nodeRepository;
    private final EdgeRepository edgeRepository;
    private final GraphExtractionService graphExtractionService;

    /**
     * Rate limit in-memory para /api/v1/ingest: 1 request por chave a cada 5 minutos.
     * A pipeline dispara ~185 requests externos + 1 chamada LLM (até 60s) por execução,
     * então em produção (Render free tier, cold start + LLM tier grátis) o ideal é
     * manter o intervalo longo. O contador é resetado a cada restart do processo —
     * aceitável porque o caller legítimo é o workflow do GitHub Actions de 6 em 6h.
     * Map está preenchido com bare longs via AtomicLong para não depender de boxing.
     */
    private static final Duration INGEST_RATE_LIMIT_WINDOW = Duration.ofMinutes(5);
    private final ConcurrentHashMap<String, AtomicLong> lastIngestByKey = new ConcurrentHashMap<>();

    public GraphController(
            NodeRepository nodeRepository,
            EdgeRepository edgeRepository,
            GraphExtractionService graphExtractionService) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.graphExtractionService = graphExtractionService;
    }

    /** Idioma dos resumos: "pt" para português, qualquer outro valor cai no inglês (padrão). */
    private static String normalizeLang(String lang) {
        return lang != null && lang.toLowerCase().startsWith("pt") ? "pt" : "en";
    }

    // =========================================================
    // HEALTH CHECK — exigido pelo Render para verificação de saúde
    // =========================================================

    /**
     * GET /health
     * Verifica se o serviço está operacional. O Render usa este endpoint
     * para decidir se o container passou no health check de startup.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", "reticle-api");
        status.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(status);
    }

    // =========================================================
    // GRAPH DATA — dados principais para o React Flow
    // =========================================================

    /**
     * GET /api/v1/graph?days=7
     * Retorna o grafo completo (nós + arestas) filtrado pelos últimos N dias.
     * Formato compatível com React Flow:
     * {
     *   "nodes": [{ "id": "uuid", "label": "...", "category": "...", "hypeScore": 5.0 }],
     *   "edges": [{ "id": "uuid", "source": "uuid", "target": "uuid", "label": "USES", "weight": 3 }]
     * }
     */
    @GetMapping("/api/v1/graph")
    public ResponseEntity<Map<String, Object>> getGraph(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "en") String lang) {

        if (days < 1 || days > 365) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "O parâmetro 'days' deve estar entre 1 e 365."));
        }

        try {
            List<Node> nodes = nodeRepository.findNodesSince(days, normalizeLang(lang));
            List<Edge> edges = edgeRepository.findEdgesSince(days);

            // Formata nós no padrão React Flow
            List<Map<String, Object>> nodeList = nodes.stream()
                    .map(n -> {
                        Map<String, Object> node = new LinkedHashMap<>();
                        node.put("id", n.getId().toString());
                        node.put("label", n.getLabel());
                        node.put("category", n.getCategory());
                        node.put("hypeScore", n.getHypeScore());
                        node.put("mentionCount", n.getMentionCount());
                        node.put("summary", n.getSummary());
                        node.put("sourceUrl", n.getSourceUrl());
                        node.put("sourceTitle", n.getSourceTitle());
                        node.put("sourcePlatform", n.getSourcePlatform());
                        node.put("firstSeen", n.getFirstSeen() != null ? n.getFirstSeen().toString() : null);
                        node.put("lastSeen", n.getLastSeen() != null ? n.getLastSeen().toString() : null);
                        return node;
                    })
                    .toList();

            // Formata arestas no padrão React Flow
            List<Map<String, Object>> edgeList = edges.stream()
                    .map(e -> {
                        Map<String, Object> edge = new LinkedHashMap<>();
                        edge.put("id", e.getId().toString());
                        edge.put("source", e.getSourceNodeId().toString());
                        edge.put("target", e.getTargetNodeId().toString());
                        edge.put("sourceLabel", e.getSourceLabel());
                        edge.put("targetLabel", e.getTargetLabel());
                        edge.put("label", e.getRelationType());
                        edge.put("relationType", e.getRelationType());
                        edge.put("weight", e.getWeight());
                        return edge;
                    })
                    .toList();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("nodes", nodeList);
            response.put("edges", edgeList);
            response.put("meta", Map.of(
                    "days", days,
                    "nodeCount", nodeList.size(),
                    "edgeCount", edgeList.size(),
                    "generatedAt", Instant.now().toString()
            ));

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Falha ao consultar banco de dados Supabase.",
                    "message", e.getMessage() != null ? e.getMessage() : "Erro interno no servidor",
                    "nodes", List.of(),
                    "edges", List.of()
            ));
        }
    }

    // =========================================================
    // ARTICLES — feed de notícias por tópico
    // =========================================================

    /**
     * GET /api/v1/articles?days=7&limit=100
     * Retorna os artigos mais recentes coletados, cada um linkado ao tópico que menciona,
     * para o feed de notícias (agrupado por categoria no frontend).
     */
    @GetMapping("/api/v1/articles")
    public ResponseEntity<Map<String, Object>> getArticles(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "100") int limit) {

        if (days < 1 || days > 365) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "O parâmetro 'days' deve estar entre 1 e 365."));
        }
        if (limit < 1 || limit > 300) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "O parâmetro 'limit' deve estar entre 1 e 300."));
        }

        List<ArticlePreview> articles = nodeRepository.findRecentArticles(days, limit);

        List<Map<String, Object>> articleList = articles.stream()
                .map(a -> {
                    Map<String, Object> article = new LinkedHashMap<>();
                    article.put("title", a.title());
                    article.put("url", a.url());
                    article.put("platform", a.platform());
                    article.put("publishedAt", a.publishedAt() != null ? a.publishedAt().toString() : null);
                    article.put("createdAt", a.createdAt() != null ? a.createdAt().toString() : null);
                    article.put("nodeLabel", a.nodeLabel());
                    article.put("nodeCategory", a.nodeCategory());
                    return article;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("articles", articleList);
        response.put("meta", Map.of(
                "days", days,
                "count", articleList.size(),
                "generatedAt", Instant.now().toString()
        ));

        return ResponseEntity.ok(response);
    }

    // =========================================================
    // TRENDS — top nós por hype_score
    // =========================================================

    /**
     * GET /api/v1/trends?days=7&limit=10
     * Retorna os top N nós (default 10) ordenados por hype_score decrescente,
     * filtrados por atividade recente (default 7 dias). Sem o filtro de
     * recência, a métrica era cumulativa e o "tendências quentes" nunca
     * perdia o card que entrou em alta uma vez.
     */
    @GetMapping("/api/v1/trends")
    public ResponseEntity<Map<String, Object>> getTrends(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "10") int limit) {

        if (days < 1 || days > 365) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "O parâmetro 'days' deve estar entre 1 e 365."));
        }
        if (limit < 1 || limit > 100) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "O parâmetro 'limit' deve estar entre 1 e 100."));
        }

        List<Node> topNodes = nodeRepository.findTopByHypeScore(days, limit);

        List<Map<String, Object>> trends = topNodes.stream()
                .map(n -> {
                    Map<String, Object> trend = new LinkedHashMap<>();
                    trend.put("id", n.getId().toString());
                    trend.put("label", n.getLabel());
                    trend.put("category", n.getCategory());
                    trend.put("hypeScore", n.getHypeScore());
                    trend.put("mentionCount", n.getMentionCount());
                    trend.put("firstSeen", n.getFirstSeen() != null ? n.getFirstSeen().toString() : null);
                    trend.put("lastSeen", n.getLastSeen() != null ? n.getLastSeen().toString() : null);
                    return trend;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("trends", trends);
        response.put("meta", Map.of(
                "days", days,
                "limit", limit,
                "count", trends.size(),
                "generatedAt", Instant.now().toString()
        ));

        return ResponseEntity.ok(response);
    }

    // =========================================================
    // INGESTÃO MANUAL — endpoint para acionar o pipeline via HTTP
    // =========================================================

    /**
     * POST /api/v1/ingest
     * Aciona manualmente o pipeline de ingestão multi-fonte.
     *
     * Falha FECHADA: o endpoint só aceita request se INGESTION_API_KEY estiver
     * configurado no ambiente. Sem chave, /ingest é um convite aberto para que
     * qualquer um dispare a pipeline (~185 HTTP + 1 LLM) e gaste o budget da
     * chave. Antes a autenticação era silenciosamente pulada quando a env var
     * estava ausente.
     *
     * Rate limit: 1 request por chave a cada 5 min, em memória. Chave do
     * caller é o X-API-Key fornecido (anônimo vira "anonymous" — o que vai
     * ser rejeitado pelo fail-closed na prática).
     */
    @PostMapping("/api/v1/ingest")
    public ResponseEntity<Map<String, Object>> triggerIngestion(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        String configuredKey = System.getenv("INGESTION_API_KEY");
        if (configuredKey == null || configuredKey.isBlank()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Ingestion is not configured. Set INGESTION_API_KEY on the service."));
        }
        if (apiKey == null || !apiKey.equals(configuredKey)) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Unauthorized. Provide a valid X-API-Key header."));
        }

        long now = System.currentTimeMillis();
        long windowMs = INGEST_RATE_LIMIT_WINDOW.toMillis();
        AtomicLong last = lastIngestByKey.computeIfAbsent(apiKey, k -> new AtomicLong(0));
        long lastTs = last.get();
        if (lastTs != 0 && now - lastTs < windowMs) {
            long retryAfterSec = (windowMs - (now - lastTs)) / 1000;
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(retryAfterSec))
                    .body(Map.of(
                            "error", "Rate limit exceeded. Try again later.",
                            "retryAfterSeconds", retryAfterSec
                    ));
        }
        last.set(now);

        try {
            var result = graphExtractionService.runIngestionPipeline();

            // Sem isto a rodada responde "success" mesmo quando o LLM falhou e a extração caiu
            // na lista fixa de palavras-chave — foi assim que a curadoria ficou dias parada
            // sem ninguém perceber. llmError agora viaja dentro do ExtractionResult,
            // então requests concorrentes não se sobrescrevem.
            String llmError = result.llmError();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", llmError == null ? "success" : "degraded");
            body.put("extraction", llmError == null ? "llm" : "keyword-fallback");
            if (llmError != null) {
                body.put("llmError", llmError);
            }
            body.put("nodesExtracted", result.nodes().size());
            body.put("edgesExtracted", result.edges().size());
            body.put("triggeredAt", Instant.now().toString());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // =========================================================
    // NODE SUMMARY — resumo técnico por demanda
    // =========================================================

    /**
     * GET /api/v1/nodes/{id}/summary
     * Retorna ou gera dinamicamente o resumo técnico de um nó específico.
     * Se o nó não tiver sourceUrl, busca ao vivo via HN Algolia e persiste o resultado.
     */
    @GetMapping("/api/v1/nodes/{id}/summary")
    public ResponseEntity<Map<String, Object>> getNodeSummary(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "en") String lang) {

        String language = normalizeLang(lang);
        Node node = nodeRepository.findById(id, language).orElse(null);
        if (node == null) {
            return ResponseEntity.notFound().build();
        }

        String summary = node.getSummary();
        boolean summaryCached = summary != null && !summary.isBlank();
        if (!summaryCached) {
            summary = graphExtractionService.generateTopicSummary(
                    node.getLabel(), node.getCategory(), node.getSourceTitle(), node.getSourceUrl(), language);
            if (summary != null && !summary.isBlank()) {
                nodeRepository.updateSummary(id, summary, language);
            }
        }

        String sourceUrl = node.getSourceUrl();
        String sourceTitle = node.getSourceTitle();
        String sourcePlatform = node.getSourcePlatform();
        if (sourceUrl == null || sourceUrl.isBlank()) {
            var found = graphExtractionService.findLiveSource(node.getLabel());
            if (found != null) {
                // discussionUrl, não url: a plataforma gravada é "hackernews" e o frontend
                // confia nela para o selo da fonte. Gravar o link externo da matéria fazia o
                // card dizer "Hacker News" apontando para o site original (ex.: um blog de
                // fotos históricas), como se o tópico tivesse saído de lá.
                sourceUrl = found.discussionUrl();
                sourceTitle = found.title();
                sourcePlatform = found.platform();
                nodeRepository.updateSource(id, sourceUrl, sourceTitle, sourcePlatform);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", summary == null ? "" : summary);
        body.put("cached", summaryCached);
        body.put("sourceUrl", sourceUrl == null ? "" : sourceUrl);
        body.put("sourceTitle", sourceTitle == null ? "" : sourceTitle);
        body.put("sourcePlatform", sourcePlatform == null ? "" : sourcePlatform);
        return ResponseEntity.ok(body);
    }
}
