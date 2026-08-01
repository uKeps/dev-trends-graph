-- ============================================================
-- Mapeador de Tendências da Bolha Dev e IA em Grafos
-- Schema para Supabase (PostgreSQL + pgvector)
-- Execute no SQL Editor do Supabase
-- ============================================================

-- 1. Ativar extensão pgvector para embeddings semânticos futuros
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================
-- TABELA: posts
-- Armazena os artigos coletados do Hacker News e outras fontes
-- ============================================================
CREATE TABLE IF NOT EXISTS posts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       TEXT             NOT NULL,
    url         TEXT,
    platform    VARCHAR(50)      NOT NULL DEFAULT 'hackernews',
    content     TEXT,
    hn_id       BIGINT           UNIQUE,
    score       INT              DEFAULT 0,
    created_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),
    processed   BOOLEAN          NOT NULL DEFAULT false
);

-- Índice para buscas por data (usado no endpoint /api/v1/graph?days=N)
CREATE INDEX IF NOT EXISTS idx_posts_created_at
    ON posts (created_at DESC);

-- Índice para filtrar posts não processados
CREATE INDEX IF NOT EXISTS idx_posts_processed
    ON posts (processed)
    WHERE processed = false;

-- Índice para busca por plataforma
CREATE INDEX IF NOT EXISTS idx_posts_platform
    ON posts (platform);

-- ============================================================
-- TABELA: nodes
-- Representa conceitos, tecnologias, frameworks e ferramentas
-- ============================================================
CREATE TABLE IF NOT EXISTS nodes (
    id          UUID PRIMARY KEY  DEFAULT gen_random_uuid(),
    label       VARCHAR(100)      NOT NULL,
    category    VARCHAR(50)       NOT NULL DEFAULT 'Technology',
    hype_score  FLOAT             NOT NULL DEFAULT 1.0,
    first_seen  TIMESTAMPTZ       NOT NULL DEFAULT now(),
    last_seen   TIMESTAMPTZ       NOT NULL DEFAULT now(),
    mention_count INT             NOT NULL DEFAULT 1,
    CONSTRAINT uq_nodes_label UNIQUE (label)
);

-- Índice B-Tree na label para buscas e joins com arestas
CREATE INDEX IF NOT EXISTS idx_nodes_label
    ON nodes (label);

-- Índice para ordenação por hype_score (top trends)
CREATE INDEX IF NOT EXISTS idx_nodes_hype_score
    ON nodes (hype_score DESC);

-- Índice para filtro por categoria
CREATE INDEX IF NOT EXISTS idx_nodes_category
    ON nodes (category);

-- Índice para busca por data de primeiro avistamento
CREATE INDEX IF NOT EXISTS idx_nodes_first_seen
    ON nodes (first_seen DESC);

-- ============================================================
-- TABELA: edges
-- Representa relações semânticas entre conceitos (nós)
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

-- Índice para busca por nó de origem
CREATE INDEX IF NOT EXISTS idx_edges_source
    ON edges (source_node_id);

-- Índice para busca por nó de destino
CREATE INDEX IF NOT EXISTS idx_edges_target
    ON edges (target_node_id);

-- Índice para filtro por tipo de relação
CREATE INDEX IF NOT EXISTS idx_edges_relation_type
    ON edges (relation_type);

-- Índice por data de criação para queries temporais
CREATE INDEX IF NOT EXISTS idx_edges_created_at
    ON edges (created_at DESC);

-- ============================================================
-- TABELA: ingestion_log
-- Rastreamento de execuções do pipeline de ingestão
-- ============================================================
CREATE TABLE IF NOT EXISTS ingestion_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    started_at    TIMESTAMPTZ      NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    posts_fetched INT              DEFAULT 0,
    nodes_created INT              DEFAULT 0,
    edges_created INT              DEFAULT 0,
    status        VARCHAR(20)      NOT NULL DEFAULT 'RUNNING',
    error_message TEXT
);

-- ============================================================
-- FUNÇÃO: upsert_node
-- Insere ou atualiza um nó, incrementando mention_count e hype_score
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
    ON CONFLICT (label) DO UPDATE
        SET mention_count = nodes.mention_count + 1,
            hype_score    = nodes.hype_score + 0.5,
            last_seen     = now(),
            category      = COALESCE(NULLIF(EXCLUDED.category, 'Technology'), nodes.category)
    RETURNING id INTO v_id;

    RETURN v_id;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- FUNÇÃO: upsert_edge
-- Insere ou incrementa o peso de uma aresta existente
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
-- Facilita a consulta de nós e arestas com labels resolvidos
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
