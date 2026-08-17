package com.dev.trends.repository;

import com.dev.trends.model.ArticlePreview;
import com.dev.trends.model.Node;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class NodeRepository {

    /** Intentional cap for graph queries. Without this LIMIT, /api/v1/graph
     *  returns the entire set — in production we can have thousands of nodes and
     *  the frontend page would freeze trying to render them all. Keeping the limit
     *  explicit (instead of relying on spring.jdbc.template.max-rows) makes the
     *  truncation visible and reviewable. */
    public static final int GRAPH_QUERY_LIMIT = 500;

    /** Generic terms that MUST NOT appear as graph nodes (they are the typical
     *  output of the LLM when it gives up on extracting something specific). The
     *  same set is used:
     *  - in findNodesSince() as an SQL filter (LIKE on LOWER),
     *  - in GraphExtractionService.isBlacklisted() to discard the extracted node
     *    before persisting it.
     *  Keeping both sides derived from this list ensures adding 'docker' or
     *  removing 'web' applies in both places without having to remember the other. */
    public static final List<String> BLACKLIST = List.of(
            "mac", "macos", "linux", "windows", "unix", "pc", "computer", "software",
            "hardware", "internet", "web", "news", "show hn", "ask hn", "pdf", "article",
            "blog", "system", "file", "code", "tech", "technology", "data", "app"
    );

    /** Quoted, comma-separated list suitable for "IN (...)" in SQL. */
    private static final String BLACKLIST_SQL = BLACKLIST.stream()
            .map(s -> "'" + s.replace("'", "''") + "'")
            .collect(Collectors.joining(", "));

    private final JdbcTemplate jdbc;

    public NodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Node> NODE_ROW_MAPPER = (rs, rowNum) -> new Node(
            UUID.fromString(rs.getString("id")),
            rs.getString("label"),
            rs.getString("category"),
            rs.getDouble("hype_score"),
            rs.getObject("first_seen", OffsetDateTime.class),
            rs.getObject("last_seen", OffsetDateTime.class),
            rs.getInt("mention_count")
    );

    /**
     * Summary column for the given language. "summary" is the legacy Portuguese
     * column; English (the UI default) lives in "summary_en". Only returns
     * literals — never the raw parameter value — to avoid opening SQL injection.
     */
    private static String summaryColumn(String lang) {
        return "pt".equals(lang) ? "summary" : "summary_en";
    }

    /**
     * Inserts or updates a node (overload for convenience with 2 parameters).
     */
    public UUID upsertNode(String label, String category) {
        return upsertNode(label, category, null, null, null);
    }

    public UUID upsertNode(String label, String category, String summary, String sourceUrl, String sourceTitle) {
        return upsertNode(label, category, summary, sourceUrl, sourceTitle, null);
    }

    /**
     * Inserts or updates a node with summary, source link and platform.
     * Uniqueness is case-insensitive (uq_nodes_label_lower index in schema.sql):
     * "react" and "React" collide on the same node.
     */
    public UUID upsertNode(String label, String category, String summary, String sourceUrl, String sourceTitle, String sourcePlatform) {
        String sql = """
                INSERT INTO nodes (label, category, summary, source_url, source_title, source_platform, hype_score, mention_count, last_seen)
                VALUES (?, ?, ?, ?, ?, ?, 1.0, 1, NOW())
                ON CONFLICT (LOWER(label)) DO UPDATE
                    SET mention_count = nodes.mention_count + 1,
                        hype_score    = nodes.hype_score + 0.5,
                        last_seen     = NOW(),
                        summary       = COALESCE(NULLIF(EXCLUDED.summary, ''), nodes.summary),
                        source_url    = COALESCE(NULLIF(EXCLUDED.source_url, ''), nodes.source_url),
                        source_title  = COALESCE(NULLIF(EXCLUDED.source_title, ''), nodes.source_title),
                        source_platform = COALESCE(NULLIF(EXCLUDED.source_platform, ''), nodes.source_platform),
                        category      = COALESCE(NULLIF(EXCLUDED.category, 'Technology'), nodes.category)
                RETURNING id;
                """;
        return jdbc.queryForObject(sql, UUID.class, label, category, summary, sourceUrl, sourceTitle, sourcePlatform);
    }

    /**
     * Finds the UUID of a node by label (case-insensitive).
     */
    public Optional<UUID> findIdByLabel(String label) {
        String sql = "SELECT id FROM nodes WHERE LOWER(label) = LOWER(?) LIMIT 1";
        List<UUID> result = jdbc.query(sql,
                (rs, rowNum) -> UUID.fromString(rs.getString("id")), label);
        return result.stream().findFirst();
    }

    /**
     * Finds the UUIDs of many nodes by label (case-insensitive) in a single query.
     *
     * <p>Semantically equivalent to calling {@link #findIdByLabel} for each label
     * individually, but with 1 round-trip to the database instead of N. Returns a
     * normalized-label -> UUID map only for the labels that were found; missing
     * labels simply don't appear in the map (the caller decides whether to create
     * an orphan).
     */
    public Map<String, UUID> findIdsByLabels(Collection<String> labels) {
        if (labels == null || labels.isEmpty()) return Map.of();
        Map<String, String> lowerToOriginal = new HashMap<>();
        for (String label : labels) {
            if (label != null && !label.isBlank()) {
                lowerToOriginal.put(label.toLowerCase(), label);
            }
        }
        if (lowerToOriginal.isEmpty()) return Map.of();

        String placeholders = String.join(", ", Collections.nCopies(lowerToOriginal.size(), "?"));
        String sql = "SELECT id, label FROM nodes WHERE LOWER(label) IN (" + placeholders + ")";
        List<String> lowerLabels = new ArrayList<>(lowerToOriginal.keySet());

        Map<String, UUID> result = new HashMap<>();
        jdbc.query(sql, (rs) -> {
            String label = rs.getString("label");
            UUID id = UUID.fromString(rs.getString("id"));
            if (label != null) {
                result.put(label.toLowerCase(), id);
            }
        }, lowerLabels.toArray());
        return result;
    }

    /**
     * Returns all nodes seen since N days ago, ignoring generic IT terms.
     */
    public List<Node> findNodesSince(int days, String lang) {
        String sql = """
                SELECT id, label, category, %s AS summary, source_url, source_title, source_platform, hype_score, first_seen, last_seen, mention_count
                FROM nodes
                WHERE last_seen >= NOW() - (? || ' days')::INTERVAL
                  AND LOWER(label) NOT IN (%s)
                ORDER BY hype_score DESC
                LIMIT ?
                """.formatted(summaryColumn(lang), BLACKLIST_SQL);
        return jdbc.query(sql, (rs, rowNum) -> {
            Node n = new Node(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("label"),
                    rs.getString("category"),
                    rs.getDouble("hype_score"),
                    rs.getObject("first_seen", OffsetDateTime.class),
                    rs.getObject("last_seen", OffsetDateTime.class),
                    rs.getInt("mention_count")
            );
            n.setSummary(rs.getString("summary"));
            n.setSourceUrl(rs.getString("source_url"));
            n.setSourceTitle(rs.getString("source_title"));
            n.setSourcePlatform(rs.getString("source_platform"));
            return n;
        }, days, GRAPH_QUERY_LIMIT);
    }

    /**
     * Finds a node by ID including summary, source_url, source_title and source_platform.
     */
    public Optional<Node> findById(UUID id, String lang) {
        String sql = """
                SELECT id, label, category, %s AS summary, source_url, source_title, source_platform, hype_score, first_seen, last_seen, mention_count
                FROM nodes
                WHERE id = ?
                """.formatted(summaryColumn(lang));
        List<Node> result = jdbc.query(sql, (rs, rowNum) -> {
            Node n = new Node(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("label"),
                    rs.getString("category"),
                    rs.getDouble("hype_score"),
                    rs.getObject("first_seen", OffsetDateTime.class),
                    rs.getObject("last_seen", OffsetDateTime.class),
                    rs.getInt("mention_count")
            );
            n.setSummary(rs.getString("summary"));
            n.setSourceUrl(rs.getString("source_url"));
            n.setSourceTitle(rs.getString("source_title"));
            n.setSourcePlatform(rs.getString("source_platform"));
            return n;
        }, id);
        return result.stream().findFirst();
    }

    /**
     * Updates the summary of a node by ID.
     */
    public void updateSummary(UUID id, String summary, String lang) {
        String sql = "UPDATE nodes SET " + summaryColumn(lang) + " = ? WHERE id = ?";
        jdbc.update(sql, summary, id);
    }

    /**
     * Updates the source (sourceUrl/sourceTitle/sourcePlatform) of a node by ID.
     */
    public void updateSource(UUID id, String sourceUrl, String sourceTitle, String sourcePlatform) {
        String sql = "UPDATE nodes SET source_url = ?, source_title = ?, source_platform = ? WHERE id = ?";
        jdbc.update(sql, sourceUrl, sourceTitle, sourcePlatform, id);
    }

    /**
     * Returns the top N nodes by hype_score, considering only those seen in the
     * last `days` days. Without this filter "hot" was cumulative: hype_score
     * never decays, so the hottest card was always a node that had peaked months
     * earlier and never came back.
     */
    public List<Node> findTopByHypeScore(int days, int limit) {
        String sql = """
                SELECT id, label, category, hype_score, first_seen, last_seen, mention_count
                FROM nodes
                WHERE last_seen >= NOW() - (? || ' days')::INTERVAL
                ORDER BY hype_score DESC
                LIMIT ?
                """;
        return jdbc.query(sql, NODE_ROW_MAPPER, days, limit);
    }

    /**
     * Returns all nodes in the database.
     */
    public List<Node> findAll() {
        String sql = "SELECT id, label, category, hype_score, first_seen, last_seen, mention_count FROM nodes ORDER BY hype_score DESC";
        return jdbc.query(sql, NODE_ROW_MAPPER);
    }

    /**
     * Persists an article linked to a topic. Idempotent: re-collecting the same
     * article in future ingestion rounds does not duplicate the row, thanks to
     * the unique (node_id, url) index.
     */
    public void insertArticle(UUID nodeId, String title, String url, String platform) {
        insertArticle(nodeId, title, url, platform, null);
    }

    /**
     * Overload that also records the original publication date from the source.
     * When `publishedAt` is null, the column stays NULL and the feed falls back
     * to created_at in the query.
     */
    public void insertArticle(UUID nodeId, String title, String url, String platform, Instant publishedAt) {
        if (nodeId == null || url == null || url.isBlank()) return;
        String sql = "INSERT INTO posts (title, url, platform, node_id, published_at) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (node_id, url) DO NOTHING";
        jdbc.update(sql, title, url, platform, nodeId, publishedAt != null ? OffsetDateTime.ofInstant(publishedAt, java.time.ZoneOffset.UTC) : null);
    }

    /**
     * Returns the most recent articles (last N days) with their associated topic,
     * for the news feed. Filters by actual publication date (when available), not
     * by ingestion date — otherwise old articles collected today would look "fresh".
     * When {@code platform} is non-null, the result is restricted to that source
     * (case-insensitive); null or blank means "any platform".
     */
    public List<ArticlePreview> findRecentArticles(int days, int limit, String platform) {
        // Hard 30-day ceiling: even for posts without published_at (legacy backfill),
        // this guarantees the feed never shows anything older. Combined with the user's
        // `days` filter, it lets them narrow to 3D/7D/etc. without ever seeing stale content.
        StringBuilder sql = new StringBuilder("""
                SELECT p.title, p.url, p.platform, p.published_at, p.created_at, n.label AS node_label, n.category AS node_category
                FROM posts p
                JOIN nodes n ON p.node_id = n.id
                WHERE COALESCE(p.published_at, p.created_at) >= NOW() - (? || ' days')::INTERVAL
                  AND COALESCE(p.published_at, p.created_at) >= NOW() - INTERVAL '30 days'
                """);
        Object[] args;
        if (platform != null && !platform.isBlank()) {
            sql.append(" AND LOWER(p.platform) = LOWER(?)");
            sql.append(" ORDER BY p.published_at DESC NULLS LAST LIMIT ?");
            args = new Object[]{ days, platform, limit };
        } else {
            sql.append(" ORDER BY p.published_at DESC NULLS LAST LIMIT ?");
            args = new Object[]{ days, limit };
        }
        return jdbc.query(sql.toString(), (rs, rowNum) -> new ArticlePreview(
                rs.getString("title"),
                rs.getString("url"),
                rs.getString("platform"),
                rs.getObject("published_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("node_label"),
                rs.getString("node_category")
        ), args);
    }

    /** Backwards-compatible overload that returns articles for every platform. */
    public List<ArticlePreview> findRecentArticles(int days, int limit) {
        return findRecentArticles(days, limit, null);
    }

    /**
     * Returns the most recent articles linked to a specific node, ordered by
     * actual publication date descending. The 30-day ceiling from
     * {@link #findRecentArticles} still applies — a topic page should never
     * show articles older than that.
     *
     * <p>Empty result when the node has no linked articles (e.g. a brand new
     * topic that the LLM extracted from a single batch). The caller treats
     * this as "no recent activity" rather than "node does not exist" — the
     * node's existence is checked separately.
     */
    public List<ArticlePreview> findRecentArticlesByNode(UUID nodeId, int days, int limit) {
        if (nodeId == null) return List.of();
        String sql = """
                SELECT p.title, p.url, p.platform, p.published_at, p.created_at, n.label AS node_label, n.category AS node_category
                FROM posts p
                JOIN nodes n ON p.node_id = n.id
                WHERE p.node_id = ?
                  AND COALESCE(p.published_at, p.created_at) >= NOW() - (? || ' days')::INTERVAL
                  AND COALESCE(p.published_at, p.created_at) >= NOW() - INTERVAL '30 days'
                ORDER BY p.published_at DESC NULLS LAST
                LIMIT ?
                """;
        return jdbc.query(sql, (rs, rowNum) -> new ArticlePreview(
                rs.getString("title"),
                rs.getString("url"),
                rs.getString("platform"),
                rs.getObject("published_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("node_label"),
                rs.getString("node_category")
        ), nodeId, days, limit);
    }

    /**
     * Returns the distinct {@code category} values currently present in {@code nodes}
     * along with how many nodes fall into each. Ordered by descending count so the
     * dominant categories surface first. Drives the {@code GET /api/v1/categories}
     * endpoint; the controller adds {@code Cache-Control: max-age=300} because this
     * data only shifts after a new ingestion round.
     */
    public List<CategoryCount> findCategories() {
        String sql = """
                SELECT category, COUNT(*) AS count
                FROM nodes
                WHERE category IS NOT NULL
                GROUP BY category
                ORDER BY count DESC, category ASC
                """;
        return jdbc.query(sql, (rs, rowNum) -> new CategoryCount(
                rs.getString("category"),
                rs.getLong("count")
        ));
    }

    /**
     * Daily mention history for a single node over the last {@code days} days,
     * sourced from the {@code posts} table (which carries {@code published_at} /
     * {@code created_at} per article). Days with no mentions come back as
     * {@code mentionCount=0} so the caller gets a continuous series for charting.
     *
     * <p>{@code hypeScore} at each point is approximated as
     * {@code 1.0 + 0.5 * cumulativeMentionsToThatDay}, matching the
     * {@code +0.5} increment in {@code upsertNode} so the rightmost point lines
     * up with the current {@code nodes.hype_score} (modulo pre-existing history
     * before the window started — that's fine for the frontend sparkline).
     *
     * <p>Backed by {@code idx_posts_published_at} for the WHERE clause; missing
     * days are filled in Java so we don't depend on {@code generate_series}
     * (which isn't available in H2 and the test profile would need extra wiring).
     */
    public List<HistoryPoint> findHistoryById(UUID nodeId, int days) {
        String sql = """
                SELECT date_trunc('day', COALESCE(p.published_at, p.created_at)) AS day,
                       COUNT(*) AS mentions
                FROM posts p
                WHERE p.node_id = ?
                  AND COALESCE(p.published_at, p.created_at) >= NOW() - (? || ' days')::INTERVAL
                GROUP BY day
                """;
        Map<LocalDate, Long> dailyMentions = new HashMap<>();
        jdbc.query(sql, rs -> {
            OffsetDateTime day = rs.getObject("day", OffsetDateTime.class);
            long mentions = rs.getLong("mentions");
            if (day != null) {
                dailyMentions.put(day.toLocalDate(), mentions);
            }
        }, nodeId, days);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long cumulative = 0;
        List<HistoryPoint> points = new ArrayList<>(days);
        // Walk oldest -> newest so the cumulative sum lines up with the curve the
        // sparkline renders.
        for (int offset = days - 1; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            long mentions = dailyMentions.getOrDefault(day, 0L);
            cumulative += mentions;
            double hypeScore = 1.0 + 0.5 * cumulative;
            points.add(new HistoryPoint(
                    day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    mentions,
                    hypeScore
            ));
        }
        return points;
    }

    /** Aggregate count for {@link NodeRepository#findCategories()}. */
    public record CategoryCount(String category, long count) {}

    /**
     * One day's data point in {@link NodeRepository#findHistoryById(UUID, int)}.
     * {@code ts} is midnight UTC of that day.
     */
    public record HistoryPoint(Instant ts, long mentionCount, double hypeScore) {}
}
