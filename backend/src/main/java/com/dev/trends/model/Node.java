package com.dev.trends.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entidade que representa um nó do grafo de tecnologias.
 */
public class Node {
    private UUID id;
    private String label;
    private String category;
    private Double hypeScore;
    private OffsetDateTime firstSeen;
    private OffsetDateTime lastSeen;
    private Integer mentionCount;
    private String summary;
    private String sourceUrl;
    private String sourceTitle;

    public Node() {}

    public Node(UUID id, String label, String category, Double hypeScore,
                OffsetDateTime firstSeen, OffsetDateTime lastSeen, Integer mentionCount) {
        this.id = id;
        this.label = label;
        this.category = category;
        this.hypeScore = hypeScore;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
        this.mentionCount = mentionCount;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Double getHypeScore() { return hypeScore; }
    public void setHypeScore(Double hypeScore) { this.hypeScore = hypeScore; }
    public OffsetDateTime getFirstSeen() { return firstSeen; }
    public void setFirstSeen(OffsetDateTime firstSeen) { this.firstSeen = firstSeen; }
    public OffsetDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(OffsetDateTime lastSeen) { this.lastSeen = lastSeen; }
    public Integer getMentionCount() { return mentionCount; }
    public void setMentionCount(Integer mentionCount) { this.mentionCount = mentionCount; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getSourceTitle() { return sourceTitle; }
    public void setSourceTitle(String sourceTitle) { this.sourceTitle = sourceTitle; }
}
