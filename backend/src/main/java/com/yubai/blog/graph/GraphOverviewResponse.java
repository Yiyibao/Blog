package com.yubai.blog.graph;

import java.time.Instant;
import java.util.List;

/**
 * Presentation-ready knowledge graph contract. It keeps semantic graph data separate from
 * structural ROOT/GROUP branches so clients can reproduce the floral clustered layout without
 * inventing business relationships in the browser.
 */
public record GraphOverviewResponse(
    String schemaVersion,
    Stats stats,
    List<LegendItem> legend,
    List<VisualNode> nodes,
    List<VisualEdge> edges
) {
    public record Stats(
        int contentNodeCount,
        int visualNodeCount,
        int relationCount,
        Instant lastUpdatedAt,
        String recommendedCenterId,
        boolean localModeRecommended
    ) {
    }

    public record LegendItem(
        String type,
        String label,
        String color,
        int count
    ) {
    }

    public record VisualNode(
        String id,
        String label,
        String type,
        String kind,
        String groupId,
        String url,
        String subtitle,
        String imageUrl,
        Instant updatedAt,
        int degree,
        int importance
    ) {
    }

    public record VisualEdge(
        String source,
        String target,
        String kind,
        double strength
    ) {
    }
}
