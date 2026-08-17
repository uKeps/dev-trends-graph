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
}
