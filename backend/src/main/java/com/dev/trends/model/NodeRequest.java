package com.dev.trends.model;

/**
 * Record representing a node to be created or updated in the graph, with summary and source link.
 */
public record NodeRequest(
        String label,
        String category,
        String summary,
        String sourceUrl,
        String sourceTitle,
        String sourcePlatform
) {
    public NodeRequest(String label, String category) {
        this(label, category, null, null, null, null);
    }
}
