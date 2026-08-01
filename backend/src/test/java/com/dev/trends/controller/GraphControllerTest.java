package com.dev.trends.controller;

import com.dev.trends.model.ExtractionResult;
import com.dev.trends.repository.EdgeRepository;
import com.dev.trends.repository.NodeRepository;
import com.dev.trends.service.GraphExtractionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

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

    @Test
    void healthEndpoint_shouldReturnUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("dev-trends-graph-api"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void graphEndpoint_shouldReturnEmptyGraphForNoData() throws Exception {
        when(nodeRepository.findNodesSince(7)).thenReturn(List.of());
        when(edgeRepository.findEdgesSince(7)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/graph").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").isArray())
                .andExpect(jsonPath("$.edges").isArray())
                .andExpect(jsonPath("$.meta.days").value(7));
    }

    @Test
    void graphEndpoint_shouldReturnBadRequestForInvalidDays() throws Exception {
        mockMvc.perform(get("/api/v1/graph").param("days", "0"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/graph").param("days", "999"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void trendsEndpoint_shouldReturnEmptyTrends() throws Exception {
        when(nodeRepository.findTopByHypeScore(10)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/trends"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trends").isArray())
                .andExpect(jsonPath("$.meta.limit").value(10));
    }
}
