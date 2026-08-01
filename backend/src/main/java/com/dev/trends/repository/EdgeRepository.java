package com.dev.trends.repository;

import com.dev.trends.model.Edge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class EdgeRepository {

    private final JdbcTemplate jdbc;

    public EdgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insere ou incrementa o peso de uma aresta usando a função PL/pgSQL do schema.
     */
    public UUID upsertEdge(UUID sourceId, UUID targetId, String relationType) {
        String sql = "SELECT upsert_edge(?, ?, ?)";
        return jdbc.queryForObject(sql, UUID.class, sourceId, targetId, relationType);
    }

    /**
     * Retorna todas as arestas cujos nós foram vistos nos últimos N dias,
     * incluindo os labels de origem e destino para facilitar o frontend.
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
        ), days, days);
    }

    /**
     * Retorna todas as arestas do banco com os labels dos nós.
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
