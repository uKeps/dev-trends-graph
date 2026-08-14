package com.dev.trends.model;

import java.time.OffsetDateTime;

/**
 * Record que representa um artigo individual coletado, associado ao tópico que ele menciona.
 * `publishedAt` é a data real de publicação na fonte; `createdAt` é quando o pipeline o coletou.
 * O frontend usa `publishedAt ?? createdAt` para mostrar "X min/h/d atrás".
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
