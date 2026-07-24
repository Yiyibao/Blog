package com.yubai.blog.graph;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/graph")
public class GraphController {

    private final GraphService service;

    public GraphController(GraphService service) {
        this.service = service;
    }

    @GetMapping("/nodes")
    public ApiResponse<GraphResponse> getGraph() {
        return ApiResponse.ok(service.buildGraph());
    }
}
