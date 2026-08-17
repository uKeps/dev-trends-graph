-- ============================================================
-- Trend mapper for the software development and AI bubble.
-- Schema for Supabase (PostgreSQL + pgvector).
-- Run in the Supabase SQL editor.
-- ============================================================

-- 1. Enable the pgvector extension for future semantic embeddings.
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================
-- TABLE: posts
-- Stores articles collected from Hacker News and other sources.
-- ============================================================
CREATE TABLE IF NOT EXISTS posts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       TEXT             NOT NULL,
    url         TEXT,
    platform    VARCHAR(50)      NOT NULL DEFAULT 'hackernews',
    created_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),
    -- Original publication date at the source (HN/Dev.to/Lobsters/SO). Different from
    -- created_at, which is when the pipeline ingested the post. Used to filter the news
    -- feed by actual recency.
    published_at TIMESTAMPTZ,
    node_id     UUID             REFERENCES nodes(id) ON DELETE CASCADE
);

-- Unique index linking each article to the topic it mentions, avoiding duplicates
-- across ingestion rounds (e.g. a story that stays on the HN front page for days).
CREATE UNIQUE INDEX IF NOT EXISTS uq_posts_node_url
    ON posts (node_id, url);

-- Index for date queries (used by /api/v1/graph?days=N).
CREATE INDEX IF NOT EXISTS idx_posts_created_at
    ON posts (created_at DESC);

-- Index for the news feed (findRecentArticles filters by published_at).
CREATE INDEX IF NOT EXISTS idx_posts_published_at
    ON posts (published_at DESC NULLS LAST);

-- Index for platform lookups.
CREATE INDEX IF NOT EXISTS idx_posts_platform
    ON posts (platform);

-- ============================================================
-- TABLE: nodes
-- Represents concepts, technologies, frameworks and tools.
-- ============================================================
CREATE TABLE IF NOT EXISTS nodes (
    id          UUID PRIMARY KEY  DEFAULT gen_random_uuid(),
    label       VARCHAR(100)      NOT NULL,
    category    VARCHAR(50)       NOT NULL DEFAULT 'Technology',
    hype_score  FLOAT             NOT NULL DEFAULT 1.0,
    first_seen  TIMESTAMPTZ       NOT NULL DEFAULT now(),
    last_seen   TIMESTAMPTZ       NOT NULL DEFAULT now(),
    mention_count INT             NOT NULL DEFAULT 1,
    summary         TEXT,   -- legacy Portuguese summary column (historical).
    summary_en      TEXT,   -- English summary (UI default language).
    source_url      TEXT,
    source_title    TEXT,
    source_platform VARCHAR(50)
);

-- Case-insensitive uniqueness on label. The LLM rarely returns the exact same
-- casing as the previous round (e.g. "react" vs "React"), and the old UNIQUE(label)
-- let both rows coexist, doubling the card in the graph. This index replaces the
-- uq_nodes_label constraint from the previous version.
CREATE UNIQUE INDEX IF NOT EXISTS uq_nodes_label_lower
    ON nodes (LOWER(label));

-- B-Tree index on label for searches and joins with edges.
CREATE INDEX IF NOT EXISTS idx_nodes_label
    ON nodes (label);

-- Index for hype_score ordering (top trends).
CREATE INDEX IF NOT EXISTS idx_nodes_hype_score
    ON nodes (hype_score DESC);

-- Index for category filter.
CREATE INDEX IF NOT EXISTS idx_nodes_category
    ON nodes (category);

-- Index for first-seen lookups.
CREATE INDEX IF NOT EXISTS idx_nodes_first_seen
    ON nodes (first_seen DESC);

-- ============================================================
-- TABLE: edges
-- Represents semantic relations between concepts (nodes).
-- ============================================================
CREATE TABLE IF NOT EXISTS edges (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_node_id UUID             NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    target_node_id UUID             NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    relation_type  VARCHAR(50)      NOT NULL DEFAULT 'RELATED_TO',
    weight         INT              NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT uq_edges_pair_relation UNIQUE (source_node_id, target_node_id, relation_type)
);

-- Index for source-node lookups.
CREATE INDEX IF NOT EXISTS idx_edges_source
    ON edges (source_node_id);

-- Index for target-node lookups.
CREATE INDEX IF NOT EXISTS idx_edges_target
    ON edges (target_node_id);

-- Index for relation-type filter.
CREATE INDEX IF NOT EXISTS idx_edges_relation_type
    ON edges (relation_type);

-- Index for time-based edge queries.
CREATE INDEX IF NOT EXISTS idx_edges_created_at
    ON edges (created_at DESC);

-- ============================================================
-- FUNCTION: upsert_node
-- Inserts or updates a node, incrementing mention_count and hype_score.
-- ============================================================
CREATE OR REPLACE FUNCTION upsert_node(
    p_label      VARCHAR(100),
    p_category   VARCHAR(50)
) RETURNS UUID AS $$
DECLARE
    v_id UUID;
BEGIN
    INSERT INTO nodes (label, category, hype_score, mention_count, last_seen)
    VALUES (p_label, p_category, 1.0, 1, now())
    ON CONFLICT (LOWER(label)) DO UPDATE
        SET mention_count = nodes.mention_count + 1,
            hype_score    = nodes.hype_score + 0.5,
            last_seen     = now(),
            category      = COALESCE(NULLIF(EXCLUDED.category, 'Technology'), nodes.category)
    RETURNING id INTO v_id;

    RETURN v_id;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- FUNCTION: upsert_edge
-- Inserts or increments the weight of an existing edge.
-- ============================================================
CREATE OR REPLACE FUNCTION upsert_edge(
    p_source_id    UUID,
    p_target_id    UUID,
    p_relation     VARCHAR(50)
) RETURNS UUID AS $$
DECLARE
    v_id UUID;
BEGIN
    INSERT INTO edges (source_node_id, target_node_id, relation_type, weight)
    VALUES (p_source_id, p_target_id, p_relation, 1)
    ON CONFLICT (source_node_id, target_node_id, relation_type) DO UPDATE
        SET weight = edges.weight + 1
    RETURNING id INTO v_id;

    RETURN v_id;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- VIEW: v_graph_data
-- Eases queries joining nodes and edges with resolved labels.
-- ============================================================
CREATE OR REPLACE VIEW v_graph_data AS
SELECT
    e.id            AS edge_id,
    e.relation_type,
    e.weight,
    e.created_at    AS edge_created_at,
    ns.id           AS source_id,
    ns.label        AS source_label,
    ns.category     AS source_category,
    ns.hype_score   AS source_hype_score,
    nt.id           AS target_id,
    nt.label        AS target_label,
    nt.category     AS target_category,
    nt.hype_score   AS target_hype_score
FROM edges e
JOIN nodes ns ON e.source_node_id = ns.id
JOIN nodes nt ON e.target_node_id = nt.id;
