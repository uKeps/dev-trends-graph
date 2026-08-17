-- ============================================================
-- Flyway V1 — Baseline schema for the dev trends service.
-- Mirrors src/main/resources/schema.sql; this is the versioned
-- source of truth for fresh installs. Idempotent statements keep
-- it safe on databases where the previous hand-rolled CREATE
-- TABLEs already ran.
-- ============================================================

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
    published_at TIMESTAMPTZ,
    node_id     UUID             REFERENCES nodes(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_posts_node_url
    ON posts (node_id, url);

CREATE INDEX IF NOT EXISTS idx_posts_published_at
    ON posts (published_at DESC NULLS LAST);

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
    summary         TEXT,
    summary_en      TEXT,
    source_url      TEXT,
    source_title    TEXT,
    source_platform VARCHAR(50)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_nodes_label_lower
    ON nodes (LOWER(label));

CREATE INDEX IF NOT EXISTS idx_nodes_label
    ON nodes (label);

CREATE INDEX IF NOT EXISTS idx_nodes_hype_score
    ON nodes (hype_score DESC);

CREATE INDEX IF NOT EXISTS idx_nodes_category
    ON nodes (category);

CREATE INDEX IF NOT EXISTS idx_nodes_last_seen
    ON nodes (last_seen DESC);

-- ============================================================
-- TABLE: edges
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

CREATE INDEX IF NOT EXISTS idx_edges_source
    ON edges (source_node_id);

CREATE INDEX IF NOT EXISTS idx_edges_target
    ON edges (target_node_id);

CREATE INDEX IF NOT EXISTS idx_edges_relation_type
    ON edges (relation_type);

-- ============================================================
-- FUNCTION: upsert_node
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
