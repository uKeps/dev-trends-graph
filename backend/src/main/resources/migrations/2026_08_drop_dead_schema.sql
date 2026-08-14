-- ============================================================
-- Migration: drop dead schema pieces
-- Created: 2026-08
--
-- The schema.sql authors defined several columns and a table that the
-- application never reads or writes. They survived because no one was
-- watching the schema. This migration removes them.
-- Fresh installs: schema.sql (the source of truth) is updated in the
-- same commit to omit these — see commit history.
--
-- Apply in the Supabase SQL editor.
-- ============================================================

-- posts.content / score / processed / hn_id: defined in the original
-- schema but never read or written by the Java pipeline. The pipeline
-- persists only title, url, platform, node_id and published_at
-- (see NodeRepository.insertArticle).
ALTER TABLE posts DROP COLUMN IF EXISTS content;
ALTER TABLE posts DROP COLUMN IF EXISTS score;
ALTER TABLE posts DROP COLUMN IF EXISTS processed;
ALTER TABLE posts DROP COLUMN IF EXISTS hn_id;

-- Index that referenced the dropped processed column.
DROP INDEX IF EXISTS idx_posts_processed;

-- ingestion_log was intended to track every pipeline run, but the
-- GraphExtractionService never wrote to it. The table is empty in
-- production and unused in queries.
DROP TABLE IF EXISTS ingestion_log;
