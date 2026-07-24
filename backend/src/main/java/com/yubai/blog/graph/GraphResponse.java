package com.yubai.blog.graph;

import java.util.List;

public record GraphResponse(
    List<GraphNode> nodes,
    List<GraphEdge> edges
) {
}
