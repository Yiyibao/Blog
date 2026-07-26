package com.yubai.blog.graph;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.CurrentUser;

@RestController
@RequestMapping("/api/v1/graph")
public class GraphController {

    private final GraphService service;

    public GraphController(GraphService service) {
        this.service = service;
    }

    /** L-16/D-17：游客视图剔除学习笔记节点，登录用户（任意角色）看全图。 */
    @GetMapping("/nodes")
    public ApiResponse<GraphResponse> getGraph() {
        return ApiResponse.ok(service.buildGraph(CurrentUser.isAuthenticated()));
    }
}
