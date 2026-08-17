-- ============================================================
-- Migration: prune dead indexes, add the missing last_seen index.
-- Created: 2026-08
--
-- Apply this in the Supabase SQL editor on existing databases.
-- Fresh installs: the changes below are also applied automatically
-- at application startup by NodeRepository.applySchemaMigrations(),
-- so the editor run is only needed for databases that pre-date
-- this change.
--
-- The dropped indexes were verified to be unused (no query plan
-- references them in the current codebase). v_graph_data is
-- defined in schema.sql but never queried by Java, so it goes
-- too.
--
-- The new idx_nodes_last_seen backs the "recent" queries that
-- drive /api/v1/graph, /api/v1/trends, /api/v1/articles and the
-- new /api/v1/nodes/{id}/history endpoint.
-- ============================================================

DROP INDEX IF EXISTS idx_posts_created_at;
DROP INDEX IF EXISTS idx_nodes_first_seen;
DROP INDEX IF EXISTS idx_edges_created_at;
DROP VIEW IF EXISTS v_graph_data;

CREATE INDEX IF NOT EXISTS idx_nodes_last_seen
    ON nodes (last_seen DESC);