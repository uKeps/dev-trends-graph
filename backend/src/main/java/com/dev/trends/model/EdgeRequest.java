package com.dev.trends.model;

/**
 * Record que representa uma aresta a ser criada/atualizada no grafo.
 */
public record EdgeRequest(
        String source,
        String target,
        String relation
) {}
