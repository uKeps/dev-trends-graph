package com.dev.trends.model;

/**
 * Record que representa um nó a ser criado/atualizado no grafo.
 */
public record NodeRequest(
        String label,
        String category
) {}
