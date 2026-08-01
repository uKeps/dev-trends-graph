package com.dev.trends.model;

import java.util.List;

/**
 * Record imutável representando o resultado da extração pelo LLM.
 * Utiliza Records do Java 16+ para concisão.
 */
public record ExtractionResult(
        List<NodeRequest> nodes,
        List<EdgeRequest> edges
) {
    public static ExtractionResult empty() {
        return new ExtractionResult(List.of(), List.of());
    }
}
