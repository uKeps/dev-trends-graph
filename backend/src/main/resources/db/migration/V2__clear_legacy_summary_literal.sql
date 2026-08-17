-- ============================================================
-- Flyway V2 — Backfill of the data migration that was previously
-- done in NodeRepository.applySchemaMigrations on every boot.
-- Clear the generic summary that an older pipeline version stamped
-- on source-less nodes so the frontend re-triggers the on-demand
-- source lookup for them.
-- ============================================================

UPDATE nodes
SET summary = NULL
WHERE summary = 'Conceito em destaque no ecossistema.'
  AND (source_url IS NULL OR source_url = '');
