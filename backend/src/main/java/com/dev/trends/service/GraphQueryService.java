package com.dev.trends.service;

import com.dev.trends.model.Node;
import com.dev.trends.repository.EdgeRepository;
import com.dev.trends.repository.NodeRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-mostly query layer for the trend graph. Wrapping these methods in
 * {@link Cacheable} keeps the database out of the hot path: the data only
 * changes when an ingestion round runs (every 6 hours by default), so a
 * short-TTL in-process cache is safe.
 */
@Service
public class GraphQueryService {

    private final NodeRepository nodeRepository;
    private final EdgeRepository edgeRepository;

    public GraphQueryService(NodeRepository nodeRepository, EdgeRepository edgeRepository) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
    }

    /**
     * Builds the full graph response for the last {@code days} days in the
     * requested language. Cached per (days, lang) so back/forward navigation
     * and rapid filter changes do not round-trip to Postgres on every request.
     */
    @Cacheable(value = "graph", key = "#days + ':' + #lang")
    public Map<String, Object> loadGraph(int days, String lang) {
        List<Node> nodes = nodeRepository.findNodesSince(days, lang);
        List<com.dev.trends.model.Edge> edges = edgeRepository.findEdgesSince(days);

        List<Map<String, Object>> nodeList = nodes.stream()
                .map(GraphQueryService::nodeToMap)
                .toList();
        List<Map<String, Object>> edgeList = edges.stream()
                .map(GraphQueryService::edgeToMap)
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
        return response;
    }

    /**
     * Returns the distinct categories with counts. Cached independently from
     * the graph payload because the cost of recomputing it on every
     * navigation is wasted effort: the response only changes after an
     * ingestion round.
     */
    @Cacheable("categories")
    public List<Map<String, Object>> loadCategories() {
        return nodeRepository.findCategories().stream()
                .map(c -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("category", c.category());
                    entry.put("count", c.count());
                    return entry;
                })
                .toList();
    }

    private static Map<String, Object> nodeToMap(Node n) {
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
    }

    private static Map<String, Object> edgeToMap(com.dev.trends.model.Edge e) {
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
    }
}
