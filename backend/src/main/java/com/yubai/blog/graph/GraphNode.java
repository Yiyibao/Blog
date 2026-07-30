package com.yubai.blog.graph;

import java.time.Instant;

public record GraphNode(
    String id,
    String label,
    String type,
    String url,
    String subtitle,
    String imageUrl,
    Instant updatedAt
) {
    public GraphNode(String id, String label, String type, String url) {
        this(id, label, type, url, null, null, null);
    }
}
