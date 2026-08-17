package com.dev.trends.repository;

import com.dev.trends.model.Edge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class EdgeRepository {

    /** Same intentional cap as NodeRepository.GRAPH_QUERY_LIMIT — keeping it
     *  consistent ensures the (nodes, edges) tuple always fits in the response. */
    private static final int EDGE_QUERY_LIMIT = 500;

    private final JdbcTemplate jdbc;

    public EdgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts or increments the weight of an edge using the PL/pgSQL function from the schema.
     */
    public UUID upsertEdge(UUID sourceId, UUID targetId, String relationType) {
        String sql = "SELECT upsert_edge(?, ?, ?)";
        return jdbc.queryForObject(sql, UUID.class, sourceId, targetId, relationType);
    }

    /**
     * Returns every edge whose endpoints were seen in the last N days, including
     * the source and target labels so the frontend doesn't have to join again.
     */
    public List<Edge> findEdgesSince(int days) {
        String sql = """
                SELECT
                    e.id,
                    e.source_node_id,
                    e.target_node_id,
                    ns.label AS source_label,
                    nt.label AS target_label,
                    e.relation_type,
                    e.weight,
                    e.created_at
                FROM edges e
                JOIN nodes ns ON e.source_node_id = ns.id
                JOIN nodes nt ON e.target_node_id = nt.id
                WHERE ns.last_seen >= NOW() - (? || ' days')::INTERVAL
                   OR nt.last_seen >= NOW() - (? || ' days')::INTERVAL
                ORDER BY e.weight DESC
                LIMIT ?
                """;
        return jdbc.query(sql, (rs, rowNum) -> new Edge(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("source_node_id")),
                UUID.fromString(rs.getString("target_node_id")),
                rs.getString("source_label"),
                rs.getString("target_label"),
                rs.getString("relation_type"),
                rs.getInt("weight"),
                rs.getObject("created_at", OffsetDateTime.class)
        ), days, days, EDGE_QUERY_LIMIT);
    }

    /**
     * Returns every edge in the database along with the node labels.
     */
    public List<Edge> findAll() {
        String sql = """
                SELECT
                    e.id,
                    e.source_node_id,
                    e.target_node_id,
                    ns.label AS source_label,
                    nt.label AS target_label,
                    e.relation_type,
                    e.weight,
                    e.created_at
                FROM edges e
                JOIN nodes ns ON e.source_node_id = ns.id
                JOIN nodes nt ON e.target_node_id = nt.id
                ORDER BY e.weight DESC
                """;
        return jdbc.query(sql, (rs, rowNum) -> new Edge(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("source_node_id")),
                UUID.fromString(rs.getString("target_node_id")),
                rs.getString("source_label"),
                rs.getString("target_label"),
                rs.getString("relation_type"),
                rs.getInt("weight"),
                rs.getObject("created_at", OffsetDateTime.class)
        ));
    }
}
