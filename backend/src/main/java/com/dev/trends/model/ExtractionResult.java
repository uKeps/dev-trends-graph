package com.dev.trends.model;

import java.util.List;

/**
 * Immutable record representing the LLM extraction result.
 * Includes the reason for falling back to keyword extraction, when applicable —
 * {@code llmError} carries that information PER CALL, instead of the legacy
 * mutable field shared across all requests in the service.
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
