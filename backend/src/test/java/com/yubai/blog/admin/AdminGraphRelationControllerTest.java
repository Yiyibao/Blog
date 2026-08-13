package com.yubai.blog.admin;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yubai.blog.graph.GraphRelationOrigin;
import com.yubai.blog.graph.GraphRelationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminGraphRelationController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminGraphRelationControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean GraphRelationService service;

    @Test
    void listsRelationsWithStableContract() throws Exception {
        var id = UUID.randomUUID();
        when(service.list(null, null))
                .thenReturn(
                        List.of(
                                new GraphRelationService.Response(
                                        id,
                                        "p-1",
                                        "p-2",
                                        "related_to",
                                        GraphRelationOrigin.MANUAL,
                                        "alice",
                                        null,
                                        null,
                                        0)));

        mockMvc.perform(get("/api/v1/admin/graph/relations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sourceId").value("p-1"))
                .andExpect(jsonPath("$.data[0].relationType").value("related_to"))
                .andExpect(jsonPath("$.data[0].version").value(0));
    }
}
