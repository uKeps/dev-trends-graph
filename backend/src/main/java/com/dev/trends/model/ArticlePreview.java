package com.dev.trends.model;

import java.time.OffsetDateTime;

/**
 * Record que representa um artigo individual coletado, associado ao tópico que ele menciona.
 */
public record ArticlePreview(
        String title,
        String url,
        String platform,
        OffsetDateTime createdAt,
        String nodeLabel,
        String nodeCategory
) {}
