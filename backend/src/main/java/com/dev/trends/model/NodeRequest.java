package com.dev.trends.model;

/**
 * Record que representa um nó a ser criado/atualizado no grafo com resumo e link de origem.
 */
public record NodeRequest(
        String label,
        String category,
        String summary,
        String sourceUrl,
        String sourceTitle
) {
    public NodeRequest(String label, String category) {
        this(label, category, null, null, null);
    }
}
