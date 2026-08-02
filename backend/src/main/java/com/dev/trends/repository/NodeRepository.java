package com.dev.trends.repository;

import com.dev.trends.model.Node;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NodeRepository {

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
     * Garante que as colunas summary, source_url e source_title existam na tabela nodes.
     */
    public void ensureSourceColumnsExist() {
        try {
            jdbc.execute("ALTER TABLE nodes ADD COLUMN IF NOT EXISTS summary TEXT;");
            jdbc.execute("ALTER TABLE nodes ADD COLUMN IF NOT EXISTS source_url TEXT;");
            jdbc.execute("ALTER TABLE nodes ADD COLUMN IF NOT EXISTS source_title TEXT;");
        } catch (Exception e) {
            // Ignora se já existirem
        }
    }

    /**
     * Insere ou atualiza um nó com resumo e link de origem.
     */
    public UUID upsertNode(String label, String category, String summary, String sourceUrl, String sourceTitle) {
        ensureSourceColumnsExist();
        
        String sql = """
                INSERT INTO nodes (label, category, summary, source_url, source_title, hype_score, mention_count, last_seen)
                VALUES (?, ?, ?, ?, ?, 1.0, 1, NOW())
                ON CONFLICT (label) DO UPDATE
                    SET mention_count = nodes.mention_count + 1,
                        hype_score    = nodes.hype_score + 0.5,
                        last_seen     = NOW(),
                        summary       = EXCLUDED.summary,
                        source_url    = EXCLUDED.source_url,
                        source_title  = EXCLUDED.source_title,
                        category      = COALESCE(NULLIF(EXCLUDED.category, 'Technology'), nodes.category)
                RETURNING id;
                """;
        return jdbc.queryForObject(sql, UUID.class, label, category, summary, sourceUrl, sourceTitle);
    }

    /**
     * Busca o UUID de um nó pelo label (case-insensitive).
     */
    public Optional<UUID> findIdByLabel(String label) {
        String sql = "SELECT id FROM nodes WHERE LOWER(label) = LOWER(?) LIMIT 1";
        List<UUID> result = jdbc.query(sql,
                (rs, rowNum) -> UUID.fromString(rs.getString("id")), label);
        return result.stream().findFirst();
    }

    /**
     * Retorna todos os nós que foram vistos desde N dias atrás, ignorando termos genéricos de TI.
     */
    public List<Node> findNodesSince(int days) {
        ensureSourceColumnsExist();
        String sql = """
                SELECT id, label, category, summary, source_url, source_title, hype_score, first_seen, last_seen, mention_count
                FROM nodes
                WHERE last_seen >= NOW() - (? || ' days')::INTERVAL
                  AND LOWER(label) NOT IN (
                    'mac', 'macos', 'linux', 'windows', 'unix', 'pc', 'computer', 'software',
                    'hardware', 'internet', 'web', 'news', 'show hn', 'ask hn', 'pdf', 'article',
                    'blog', 'system', 'file', 'code', 'tech', 'technology', 'data', 'app'
                  )
                ORDER BY hype_score DESC
                """;
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
            return n;
        }, days);
    }

    /**
     * Retorna os top N nós por hype_score.
     */
    public List<Node> findTopByHypeScore(int limit) {
        String sql = """
                SELECT id, label, category, hype_score, first_seen, last_seen, mention_count
                FROM nodes
                ORDER BY hype_score DESC
                LIMIT ?
                """;
        return jdbc.query(sql, NODE_ROW_MAPPER, limit);
    }

    /**
     * Retorna todos os nós do banco.
     */
    public List<Node> findAll() {
        String sql = "SELECT id, label, category, hype_score, first_seen, last_seen, mention_count FROM nodes ORDER BY hype_score DESC";
        return jdbc.query(sql, NODE_ROW_MAPPER);
    }
}
