package com.dev.trends.controller;

import com.dev.trends.model.Edge;
import com.dev.trends.model.Node;
import com.dev.trends.repository.EdgeRepository;
import com.dev.trends.repository.NodeRepository;
import com.dev.trends.service.GraphExtractionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    public GraphController(
            NodeRepository nodeRepository,
            EdgeRepository edgeRepository,
            GraphExtractionService graphExtractionService) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.graphExtractionService = graphExtractionService;
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
        status.put("service", "dev-trends-graph-api");
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
            @RequestParam(defaultValue = "7") int days) {

        if (days < 1 || days > 365) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "O parâmetro 'days' deve estar entre 1 e 365."));
        }

        try {
            List<Node> nodes = nodeRepository.findNodesSince(days);
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
    // TRENDS — top nós por hype_score
    // =========================================================

    /**
     * GET /api/v1/trends
     * Retorna os top 10 nós ordenados por hype_score decrescente.
     * Usado para o painel de "tendências quentes" no frontend.
     */
    @GetMapping("/api/v1/trends")
    public ResponseEntity<Map<String, Object>> getTrends(
            @RequestParam(defaultValue = "10") int limit) {

        if (limit < 1 || limit > 100) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "O parâmetro 'limit' deve estar entre 1 e 100."));
        }

        List<Node> topNodes = nodeRepository.findTopByHypeScore(limit);

        List<Map<String, Object>> trends = topNodes.stream()
                .map(n -> {
                    Map<String, Object> trend = new LinkedHashMap<>();
                    trend.put("id", n.getId().toString());
                    trend.put("label", n.getLabel());
                    trend.put("category", n.getCategory());
                    trend.put("hypeScore", n.getHypeScore());
                    trend.put("mentionCount", n.getMentionCount());
                    trend.put("firstSeen", n.getFirstSeen() != null ? n.getFirstSeen().toString() : null);
                    return trend;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("trends", trends);
        response.put("meta", Map.of(
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
     * Protegido por header de API key básica para uso em webhooks.
     */
    @PostMapping("/api/v1/ingest")
    public ResponseEntity<Map<String, Object>> triggerIngestion(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        String configuredKey = System.getenv("INGESTION_API_KEY");
        if (configuredKey != null && !configuredKey.isBlank() &&
            (apiKey == null || !apiKey.equals(configuredKey))) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Unauthorized. Provide a valid X-API-Key header."));
        }

        try {
            var result = graphExtractionService.runIngestionPipeline();
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "nodesExtracted", result.nodes().size(),
                    "edgesExtracted", result.edges().size(),
                    "triggeredAt", Instant.now().toString()
            ));
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
    public ResponseEntity<Map<String, Object>> getNodeSummary(@PathVariable UUID id) {
        Node node = nodeRepository.findById(id).orElse(null);
        if (node == null) {
            return ResponseEntity.notFound().build();
        }

        String summary = node.getSummary();
        boolean summaryCached = summary != null && !summary.isBlank();
        if (!summaryCached) {
            summary = graphExtractionService.generateTopicSummary(
                    node.getLabel(), node.getCategory(), node.getSourceTitle(), node.getSourceUrl());
            if (summary != null && !summary.isBlank()) {
                nodeRepository.updateSummary(id, summary);
            }
        }

        String sourceUrl = node.getSourceUrl();
        String sourceTitle = node.getSourceTitle();
        String sourcePlatform = node.getSourcePlatform();
        if (sourceUrl == null || sourceUrl.isBlank()) {
            var found = graphExtractionService.findLiveSource(node.getLabel());
            if (found != null) {
                sourceUrl = found.url();
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
