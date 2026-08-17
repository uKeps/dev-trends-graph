package com.dev.trends.controller;

import com.dev.trends.model.ExtractionResult;
import com.dev.trends.model.Node;
import com.dev.trends.repository.EdgeRepository;
import com.dev.trends.repository.NodeRepository;
import com.dev.trends.service.GraphExtractionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GraphController.class)
class GraphControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NodeRepository nodeRepository;

    @MockBean
    private EdgeRepository edgeRepository;

    @MockBean
    private GraphExtractionService graphExtractionService;

    @MockBean
    private JdbcTemplate jdbc;

    @Test
    void healthEndpoint_shouldReturnUp() throws Exception {
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("reticle-api"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.components.database").value("UP"));
    }

    @Test
    void healthEndpoint_shouldReturnDownWhenDatabaseUnreachable() throws Exception {
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenThrow(new org.springframework.dao.TransientDataAccessResourceException("connection refused"));

        mockMvc.perform(get("/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.database").value(org.hamcrest.Matchers.startsWith("DOWN")));
    }

    @Test
    void graphEndpoint_shouldReturnEmptyGraphForNoData() throws Exception {
        when(nodeRepository.findNodesSince(7, "en")).thenReturn(List.of());
        when(edgeRepository.findEdgesSince(7)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/graph").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").isArray())
                .andExpect(jsonPath("$.edges").isArray())
                .andExpect(jsonPath("$.meta.days").value(7));
    }

    @Test
    void graphEndpoint_shouldAskRepositoryForPortugueseSummaries() throws Exception {
        when(nodeRepository.findNodesSince(7, "pt")).thenReturn(List.of());
        when(edgeRepository.findEdgesSince(7)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/graph").param("days", "7").param("lang", "pt-BR"))
                .andExpect(status().isOk());

        verify(nodeRepository).findNodesSince(7, "pt");
    }

    @Test
    void graphEndpoint_shouldReturnBadRequestForInvalidDays() throws Exception {
        mockMvc.perform(get("/api/v1/graph").param("days", "0"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/graph").param("days", "999"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void summaryEndpoint_shouldReturnDiscussionUrlForLiveSource() throws Exception {
        UUID id = UUID.randomUUID();
        Node node = new Node(id, "Cocaine", "Concept", 1.0, null, null, 1);
        when(nodeRepository.findById(id, "en")).thenReturn(Optional.of(node));
        when(graphExtractionService.generateTopicSummary(any(), any(), any(), any(), any()))
                .thenReturn("resumo");
        when(graphExtractionService.findLiveSource("Cocaine")).thenReturn(
                new GraphExtractionService.Article("42", "Cocaine paraphernalia ads",
                        "https://rarehistoricalphotos.com/cocaine-paraphernalia-ads-1970s/",
                        "https://news.ycombinator.com/item?id=42", "hackernews", null));

        // The badge says "Hacker News"; the link must point at Hacker News, not the
        // article's original site.
        mockMvc.perform(get("/api/v1/nodes/" + id + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourcePlatform").value("hackernews"))
                .andExpect(jsonPath("$.sourceUrl").value("https://news.ycombinator.com/item?id=42"));
    }

    @Test
    void trendsEndpoint_shouldReturnEmptyTrends() throws Exception {
        when(nodeRepository.findTopByHypeScore(7, 10)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/trends"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trends").isArray())
                .andExpect(jsonPath("$.meta.days").value(7))
                .andExpect(jsonPath("$.meta.limit").value(10));
    }

    @Test
    void categoriesEndpoint_shouldReturnCategoriesSortedByCount() throws Exception {
        when(nodeRepository.findCategories()).thenReturn(List.of(
                new NodeRepository.CategoryCount("Framework", 12L),
                new NodeRepository.CategoryCount("Language", 5L)
        ));

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").isArray())
                .andExpect(jsonPath("$.categories[0].category").value("Framework"))
                .andExpect(jsonPath("$.categories[0].count").value(12))
                .andExpect(jsonPath("$.totalCategories").value(2))
                .andExpect(header().string("Cache-Control", "max-age=300"));
    }

    @Test
    void historyEndpoint_shouldReturnDailySeriesForExistingNode() throws Exception {
        UUID id = UUID.randomUUID();
        Node node = new Node(id, "React", "Framework", 4.5,
                java.time.OffsetDateTime.now(), java.time.OffsetDateTime.now(), 7);
        when(nodeRepository.findById(id, "en")).thenReturn(Optional.of(node));

        // Three days out of seven have mentions: 1, 0, 2.
        // Cumulative at the end should be 3 -> hypeScore 1.0 + 0.5*3 = 2.5.
        java.time.Instant today = java.time.Instant.now();
        java.time.Instant d0 = today.minus(java.time.Duration.ofDays(6));
        java.time.Instant d2 = today.minus(java.time.Duration.ofDays(4));
        java.time.Instant d3 = today.minus(java.time.Duration.ofDays(3));
        when(nodeRepository.findHistoryById(id, 7)).thenReturn(List.of(
                new NodeRepository.HistoryPoint(d0, 1L, 1.5),
                new NodeRepository.HistoryPoint(today.minus(java.time.Duration.ofDays(5)), 0L, 1.5),
                new NodeRepository.HistoryPoint(d2, 2L, 2.5),
                new NodeRepository.HistoryPoint(d3, 0L, 2.5),
                new NodeRepository.HistoryPoint(today.minus(java.time.Duration.ofDays(2)), 0L, 2.5),
                new NodeRepository.HistoryPoint(today.minus(java.time.Duration.ofDays(1)), 0L, 2.5),
                new NodeRepository.HistoryPoint(today, 0L, 2.5)
        ));

        mockMvc.perform(get("/api/v1/nodes/" + id + "/history").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value(id.toString()))
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.points.length()").value(7))
                .andExpect(jsonPath("$.points[0].mentionCount").value(1))
                .andExpect(jsonPath("$.points[2].mentionCount").value(2))
                .andExpect(header().string("Cache-Control", "max-age=300"));
    }

    @Test
    void historyEndpoint_shouldReturn404WhenNodeDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        when(nodeRepository.findById(id, "en")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/nodes/" + id + "/history"))
                .andExpect(status().isNotFound());
    }

    @Test
    void historyEndpoint_shouldReturn400ForOutOfRangeDays() throws Exception {
        mockMvc.perform(get("/api/v1/nodes/" + UUID.randomUUID() + "/history").param("days", "0"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/nodes/" + UUID.randomUUID() + "/history").param("days", "365"))
                .andExpect(status().isBadRequest());
    }
}
