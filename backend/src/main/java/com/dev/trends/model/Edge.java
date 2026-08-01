package com.dev.trends.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entidade que representa uma aresta do grafo de tecnologias.
 */
public class Edge {
    private UUID id;
    private UUID sourceNodeId;
    private UUID targetNodeId;
    private String sourceLabel;
    private String targetLabel;
    private String relationType;
    private Integer weight;
    private OffsetDateTime createdAt;

    public Edge() {}

    public Edge(UUID id, UUID sourceNodeId, UUID targetNodeId, String sourceLabel,
                String targetLabel, String relationType, Integer weight, OffsetDateTime createdAt) {
        this.id = id;
        this.sourceNodeId = sourceNodeId;
        this.targetNodeId = targetNodeId;
        this.sourceLabel = sourceLabel;
        this.targetLabel = targetLabel;
        this.relationType = relationType;
        this.weight = weight;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSourceNodeId() { return sourceNodeId; }
    public void setSourceNodeId(UUID sourceNodeId) { this.sourceNodeId = sourceNodeId; }
    public UUID getTargetNodeId() { return targetNodeId; }
    public void setTargetNodeId(UUID targetNodeId) { this.targetNodeId = targetNodeId; }
    public String getSourceLabel() { return sourceLabel; }
    public void setSourceLabel(String sourceLabel) { this.sourceLabel = sourceLabel; }
    public String getTargetLabel() { return targetLabel; }
    public void setTargetLabel(String targetLabel) { this.targetLabel = targetLabel; }
    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }
    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
