package com.dev.trends.model;

import java.time.OffsetDateTime;

/**
 * Record representing a single collected article, linked to the topic it mentions.
 * `publishedAt` is the actual publication date at the source; `createdAt` is when
 * the pipeline collected it. The frontend uses `publishedAt ?? createdAt` to render
 * "X min/h/d ago".
 */
public record ArticlePreview(
        String title,
        String url,
        String platform,
        OffsetDateTime publishedAt,
        OffsetDateTime createdAt,
        String nodeLabel,
        String nodeCategory
) {}
