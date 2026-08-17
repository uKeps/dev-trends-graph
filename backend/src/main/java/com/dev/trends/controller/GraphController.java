package com.dev.trends.controller;

import com.dev.trends.model.ArticlePreview;
import com.dev.trends.model.Node;
import com.dev.trends.repository.EdgeRepository;
import com.dev.trends.repository.NodeRepository;
import com.dev.trends.service.GraphExtractionService;
import com.dev.trends.service.GraphQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * REST controller exposing the trend-graph endpoints.
 * Configured to be consumed by the frontend on Vercel (CORS enabled).
 */
@RestController
@CrossOrigin(origins = "*", maxAge = 3600)
public class GraphController {

    private final NodeRepository nodeRepository;
    private final EdgeRepository edgeRepository;
    private final GraphExtractionService graphExtractionService;
    private final GraphQueryService graphQueryService;
    private final JdbcTemplate jdbc;

    /**
     * In-memory rate limit for /api/v1/ingest: 1 request per key every 5 minutes.
     * The pipeline fires ~185 external requests plus one LLM call (up to 60s) per
     * execution, so in production (Render free tier, cold start + free LLM tier) the
     * safe play is to keep the interval long. The counter resets on every process
     * restart — acceptable because the legitimate caller is the GitHub Actions
     * workflow that runs every 6 hours. The map is populated with bare longs via
     * AtomicLong to avoid boxing.
     *
     * <p>{@link #INGEST_RATE_LIMIT_MAX_KEYS} caps the map size: the legitimate caller
     * is unique (GitHub Actions), so in normal operation the map has a single entry.
     * This guards against keys invented by scanners/fuzzers that could grow the map
     * without bound — once the cap is hit we clear everything (the rate limit
     * resets on restart anyway).
     */
    private static final Duration INGEST_RATE_LIMIT_WINDOW = Duration.ofMinutes(5);
    private static final int INGEST_RATE_LIMIT_MAX_KEYS = 64;
    private final ConcurrentHashMap<String, AtomicLong> lastIngestByKey = new ConcurrentHashMap<>();

    public GraphController(
            NodeRepository nodeRepository,
            EdgeRepository edgeRepository,
            GraphExtractionService graphExtractionService,
            GraphQueryService graphQueryService,
            JdbcTemplate jdbc) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.graphExtractionService = graphExtractionService;
        this.graphQueryService = graphQueryService;
        this.jdbc = jdbc;
    }

    /** Summary language: "pt" for Portuguese, anything else falls back to English (default). */
    private static String normalizeLang(String lang) {
        return lang != null && lang.toLowerCase().startsWith("pt") ? "pt" : "en";
    }

    // =========================================================
    // HEALTH CHECK — required by Render for liveness checks.
    // =========================================================

    /**
     * GET /health
     * Checks whether the service is operational. Render uses this endpoint to decide
     * whether the container passed its startup health check. Beyond the "I'm running"
     * signal, it issues a SELECT 1 against the database — without that, the health
     * endpoint would return 200 even when Supabase was unavailable, leaving the UI
     * stuck in a loading loop with no sign of failure.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, String> components = new LinkedHashMap<>();
        boolean dbUp = checkDatabase(components);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbUp ? "UP" : "DOWN");
        body.put("service", "reticle-api");
        body.put("timestamp", Instant.now().toString());
        body.put("components", components);
        return ResponseEntity.status(dbUp ? 200 : 503).body(body);
    }

    private boolean checkDatabase(Map<String, String> components) {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            components.put("database", "UP");
            return Integer.valueOf(1).equals(one);
        } catch (Exception e) {
            components.put("database", "DOWN: " + e.getMessage());
            return false;
        }
    }

    // =========================================================
    // GRAPH DATA — main payload for React Flow.
    // =========================================================

    /**
     * GET /api/v1/graph?days=7
     * Returns the full graph (nodes + edges) filtered by the last N days.
     * Shape is React Flow compatible:
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
                    .body(Map.of("error", "Query parameter 'days' must be between 1 and 365."));
        }

        try {
            // The cache is on the service layer; validation here ensures bad
            // requests never poison the cache.
            Map<String, Object> response = graphQueryService.loadGraph(days, normalizeLang(lang));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to query the database.",
                    "message", e.getMessage() != null ? e.getMessage() : "Internal server error",
                    "nodes", List.of(),
                    "edges", List.of()
            ));
        }
    }

    // =========================================================
    // ARTICLES — news feed grouped by topic.
    // =========================================================

    /**
     * GET /api/v1/articles?days=7&limit=100
     * Returns the most recent collected articles, each linked to the topic it mentions,
     * for the news feed (grouped by category in the frontend).
     */
    @GetMapping("/api/v1/articles")
    public ResponseEntity<Map<String, Object>> getArticles(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String platform) {

        if (days < 1 || days > 365) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Query parameter 'days' must be between 1 and 365."));
        }
        if (limit < 1 || limit > 300) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Query parameter 'limit' must be between 1 and 300."));
        }

        String normalizedPlatform = (platform != null && !platform.isBlank()) ? platform.trim().toLowerCase() : null;
        List<ArticlePreview> articles = nodeRepository.findRecentArticles(days, limit, normalizedPlatform);

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
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("days", days);
        meta.put("count", articleList.size());
        meta.put("platform", normalizedPlatform);
        meta.put("generatedAt", Instant.now().toString());
        response.put("articles", articleList);
        response.put("meta", meta);

        return ResponseEntity.ok(response);
    }

    // =========================================================
    // TRENDS — top nodes by hype_score.
    // =========================================================

    /**
     * GET /api/v1/trends?days=7&limit=10
     * Returns the top N nodes (default 10) ordered by hype_score descending,
     * filtered by recent activity (default 7 days). Without the recency filter
     * the metric was cumulative and "hot trends" never dropped a card that had
     * once peaked.
     */
    @GetMapping("/api/v1/trends")
    public ResponseEntity<Map<String, Object>> getTrends(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "10") int limit) {

        if (days < 1 || days > 365) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Query parameter 'days' must be between 1 and 365."));
        }
        if (limit < 1 || limit > 100) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Query parameter 'limit' must be between 1 and 100."));
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
    // MANUAL INGEST — endpoint to trigger the pipeline over HTTP.
    // =========================================================

    /**
     * POST /api/v1/ingest
     * Manually triggers the multi-source ingestion pipeline.
     *
     * Fail-closed: the endpoint only accepts a request when INGESTION_API_KEY is
     * configured in the environment. Without the key, /ingest would be an open
     * invitation for anyone to fire the pipeline (~185 HTTP calls + 1 LLM) and
     * burn through the API key budget. Previously the auth check was silently
     * skipped when the env var was missing.
     *
     * Rate limit: 1 request per key every 5 minutes, in memory. The caller's key
     * is the X-API-Key header (anonymous calls become "anonymous" — which the
     * fail-closed check rejects anyway).
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

        // Guard against unbounded map growth (the legitimate caller is unique;
        // scanners inventing keys could exhaust memory). Once the cap is hit we
        // drop everything: the per-key rate limit resets to zero, but the
        // attacker loses their state too.
        if (lastIngestByKey.size() >= INGEST_RATE_LIMIT_MAX_KEYS) {
            lastIngestByKey.clear();
        }

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

            // Without this the run would respond "success" even when the LLM failed
            // and extraction fell back to the keyword list — that's how curation
            // sat stale for days without anyone noticing. llmError now travels
            // inside the ExtractionResult, so concurrent requests don't clobber
            // each other.
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
    // NODE SUMMARY — on-demand technical summary.
    // =========================================================

    /**
     * GET /api/v1/nodes/{id}/summary
     * Returns or dynamically generates the technical summary for a specific node.
     * If the node has no sourceUrl, it searches HN Algolia live and persists the result.
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
                // Use discussionUrl, not url: the recorded platform is "hackernews" and the
                // frontend trusts it for the source badge. Storing the external article
                // URL made the card say "Hacker News" while pointing at the original site
                // (e.g. a historical-photos blog), as if the topic originated there.
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

    // =========================================================
    // CATEGORIES — distinct node categories with counts.
    // =========================================================

    /**
     * GET /api/v1/categories
     * Returns every distinct {@code category} value currently used by nodes, with
     * the count of nodes in each. Sorted by descending count so the dominant
     * categories surface first.
     *
     * <p>Returned with a {@code Cache-Control: max-age=300} header because the data
     * only shifts after a new ingestion round (every 6 hours by default). The
     * frontend can therefore cache the response client-side for 5 minutes.
     */
    @GetMapping("/api/v1/categories")
    public ResponseEntity<Map<String, Object>> getCategories() {
        List<Map<String, Object>> categories = graphQueryService.loadCategories();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categories", categories);
        body.put("totalCategories", categories.size());
        body.put("generatedAt", Instant.now().toString());
        return ResponseEntity.ok()
                .header("Cache-Control", "max-age=300")
                .body(body);
    }

    // =========================================================
    // NODE HISTORY — daily mention / hype series for a node.
    // =========================================================

    /**
     * GET /api/v1/nodes/{id}/history?days=7
     * Returns one data point per day for the last {@code days} days (1-90) for the
     * given node. Each point carries the day's mention count and an approximated
     * {@code hypeScore} (1.0 + 0.5 * cumulative mentions through that day, matching
     * the {@code +0.5} increment in {@code upsertNode}). Days with no mentions
     * come back as {@code mentionCount=0} so the series is continuous for charting.
     *
     * <p>404 when the node does not exist; 400 for out-of-range {@code days}.
     */
    @GetMapping("/api/v1/nodes/{id}/history")
    public ResponseEntity<Map<String, Object>> getNodeHistory(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "7") int days) {

        if (days < 1 || days > 90) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Query parameter 'days' must be between 1 and 90."));
        }

        // 404 vs 200-with-empty: if the node doesn't exist, don't return a flat
        // zero series — that's indistinguishable from "real node, quiet window",
        // which the frontend will mis-interpret.
        if (nodeRepository.findById(id, "en").isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<NodeRepository.HistoryPoint> points = nodeRepository.findHistoryById(id, days);

        List<Map<String, Object>> series = points.stream()
                .map(p -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("ts", p.ts().toString());
                    entry.put("mentionCount", p.mentionCount());
                    entry.put("hypeScore", p.hypeScore());
                    return entry;
                })
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodeId", id.toString());
        body.put("days", days);
        body.put("points", series);
        body.put("generatedAt", Instant.now().toString());
        return ResponseEntity.ok()
                .header("Cache-Control", "max-age=300")
                .body(body);
    }

    // =========================================================
    // NODE ARTICLES — recent mentions for the topic page.
    // =========================================================

    /**
     * GET /api/v1/nodes/{id}/articles?days=30&limit=20
     * Returns the recent articles that mentioned the topic, used by the
     * SSR topic page to render a "who cited this" timeline. Empty when the
     * node has no linked articles; 404 when the node itself does not exist
     * (so the topic page can decide between "missing" and "no activity").
     */
    @GetMapping("/api/v1/nodes/{id}/articles")
    public ResponseEntity<Map<String, Object>> getNodeArticles(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "20") int limit) {

        if (days < 1 || days > 90) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Query parameter 'days' must be between 1 and 90."));
        }
        if (limit < 1 || limit > 100) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Query parameter 'limit' must be between 1 and 100."));
        }
        if (nodeRepository.findById(id, "en").isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<ArticlePreview> articles = nodeRepository.findRecentArticlesByNode(id, days, limit);

        List<Map<String, Object>> list = articles.stream()
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

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodeId", id.toString());
        body.put("articles", list);
        body.put("count", list.size());
        body.put("generatedAt", Instant.now().toString());
        return ResponseEntity.ok()
                .header("Cache-Control", "max-age=300")
                .body(body);
    }
}
