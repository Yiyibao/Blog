package com.yubai.blog.graph;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GraphController.class)
@AutoConfigureMockMvc(addFilters = false)
class GraphControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    GraphService graphService;

    @Test
    void overviewReturnsPresentationReadyContract() throws Exception {
        var graph = new GraphResponse(
            List.of(
                new GraphNode("p-1", "Architecture", "POST", "/articles/architecture",
                    "Engineering", null, Instant.parse("2026-07-31T00:00:00Z")),
                new GraphNode("t-java", "Java", "TAG", "/tags/Java")
            ),
            List.of(new GraphEdge("p-1", "t-java"))
        );
        when(graphService.buildGraph(false)).thenReturn(graph);

        mockMvc.perform(get("/api/v1/graph/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.schemaVersion").value("2.0"))
            .andExpect(jsonPath("$.data.stats.contentNodeCount").value(1))
            .andExpect(jsonPath("$.data.stats.relationCount").value(4))
            .andExpect(jsonPath("$.data.stats.lastUpdatedAt").value("2026-07-31T00:00:00Z"))
            .andExpect(jsonPath("$.data.legend[0].type").value("POST"))
            .andExpect(jsonPath("$.data.nodes[0].id").value("root-knowledge"))
            .andExpect(jsonPath("$.data.nodes[0].kind").value("ROOT"))
            .andExpect(jsonPath("$.data.edges[0].kind").value("STRUCTURE"));
    }
}
