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
     * Insere ou atualiza um nó usando a função PL/pgSQL do schema.
     */
    public UUID upsertNode(String label, String category) {
        String sql = "SELECT upsert_node(?, ?)";
        return jdbc.queryForObject(sql, UUID.class, label, category);
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
     * Retorna todos os nós que foram vistos desde N dias atrás.
     */
    public List<Node> findNodesSince(int days) {
        String sql = """
                SELECT id, label, category, hype_score, first_seen, last_seen, mention_count
                FROM nodes
                WHERE last_seen >= NOW() - (? || ' days')::INTERVAL
                ORDER BY hype_score DESC
                """;
        return jdbc.query(sql, NODE_ROW_MAPPER, days);
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
