-- ============================================================
-- Migration: case-insensitive uniqueness on nodes.label
-- Created: 2026-08
--
-- Apply this script in the Supabase SQL editor on databases
-- provisioned with the original schema.sql (constraint version).
-- schema.sql (the source of truth for fresh installs) was updated
-- in the same commit to declare the index directly.
-- ============================================================

-- 1. Deduplicate existing rows that collide only because of casing.
--    Keep the one with the highest mention_count so we don't lose
--    accumulated history; if tied, keep the oldest first_seen.
UPDATE nodes n
SET mention_count = keeper.mention_count,
    hype_score    = keeper.hype_score,
    last_seen     = keeper.last_seen,
    first_seen    = keeper.first_seen,
    summary       = COALESCE(NULLIF(keeper.summary, ''), n.summary),
    summary_en    = COALESCE(NULLIF(keeper.summary_en, ''), n.summary_en),
    source_url    = COALESCE(NULLIF(keeper.source_url, ''), n.source_url),
    source_title  = COALESCE(NULLIF(keeper.source_title, ''), n.source_title),
    source_platform = COALESCE(NULLIF(keeper.source_platform, ''), n.source_platform),
    category      = COALESCE(NULLIF(keeper.category, 'Technology'), n.category)
FROM (
    SELECT DISTINCT ON (LOWER(label)) id, mention_count, hype_score,
           last_seen, first_seen, summary, summary_en, source_url,
           source_title, source_platform, category
    FROM nodes
    ORDER BY LOWER(label), mention_count DESC, first_seen ASC
) keeper
WHERE LOWER(n.label) = LOWER(keeper.label)
  AND n.id <> keeper.id;

-- Drop the loser rows entirely.
DELETE FROM nodes n
USING (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY LOWER(label) ORDER BY mention_count DESC, first_seen ASC
    ) AS rn
    FROM nodes
) ranked
WHERE n.id = ranked.id AND ranked.rn > 1;

-- 2. Replace the unique constraint with a unique index on LOWER(label).
ALTER TABLE nodes DROP CONSTRAINT IF EXISTS uq_nodes_label;

CREATE UNIQUE INDEX IF NOT EXISTS uq_nodes_label_lower
    ON nodes (LOWER(label));

-- 3. Update the PL/pgSQL helper to honor the new conflict target.
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
