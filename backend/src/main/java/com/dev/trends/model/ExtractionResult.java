package com.dev.trends.model;

import java.util.List;

/**
 * Record imutável representando o resultado da extração pelo LLM.
 * Inclui o motivo da queda para extração por palavra-chave, quando foi o caso —
 * {@code llmError} carrega essa informação POR CHAMADA, em vez do antigo campo
 * mutável compartilhado por todas as requests no service.
 */
public record ExtractionResult(
        List<NodeRequest> nodes,
        List<EdgeRequest> edges,
        String llmError
) {
    public static ExtractionResult empty() {
        return new ExtractionResult(List.of(), List.of(), null);
    }

    public static ExtractionResult withError(String error) {
        return new ExtractionResult(List.of(), List.of(), error);
    }
}
