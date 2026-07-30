package com.yubai.blog.graph;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.CurrentUser;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/v1/graph")
@Validated
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

    /**
     * 新版全站知识关联画布：返回中心节点、分类枢纽、图例、统计和带权关系，
     * 旧 /nodes 契约继续保留，便于前端渐进迁移。
     */
    @GetMapping("/overview")
    public ApiResponse<GraphOverviewResponse> getOverview() {
        var graph = service.buildGraph(CurrentUser.isAuthenticated());
        return ApiResponse.ok(GraphService.toOverview(graph));
    }

    /**
     * 5C：局部子图——以 center 为圆心 BFS depth 层（默认 2）。
     * 经代理调用 buildGraph 命中 P1-5 缓存后在内存抽取，无新查询面。
     */
    @GetMapping("/nodes/{center}")
    public ApiResponse<GraphResponse> getSubgraph(
        @PathVariable String center,
        @RequestParam(defaultValue = "2") @Min(1) @Max(3) int depth
    ) {
        var graph = service.buildGraph(CurrentUser.isAuthenticated());
        return ApiResponse.ok(GraphService.extractSubgraph(graph, center, depth));
    }
}
