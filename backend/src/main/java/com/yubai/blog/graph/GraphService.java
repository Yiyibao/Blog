package com.yubai.blog.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.config.CacheConfig;
import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.note.NoteRepository;
import com.yubai.blog.post.PostRepository;

/**
 * Builds the read-only knowledge graph exposed by {@code GET /api/v1/graph/nodes}.
 *
 * <p>Design contract:
 * <ul>
 *   <li>TAG nodes are relation/filter hubs only. Public category pages no longer exist, so a TAG
 *       node never carries a URL ({@code url == null}); the frontend uses them to filter locally.</li>
 *   <li>POST / NOTE / DISH nodes keep their real content URLs.</li>
 *   <li>Tag identity is derived from a hash of the normalised label, so ids stay stable regardless
 *       of repository result order and are safe for non-ASCII (e.g. Chinese) labels.</li>
 *   <li>Blank or null category / folder / tag values are ignored instead of creating empty nodes.</li>
 *   <li>Edges are de-duplicated and the whole payload is emitted in a deterministic order so the
 *       frontend SVG layout is stable between requests.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class GraphService {

    private static final String TYPE_POST = "POST";
    private static final String TYPE_NOTE = "NOTE";
    private static final String TYPE_DISH = "DISH";
    private static final String TYPE_TAG = "TAG";

    /** TAG nodes are emitted first so they survive any frontend node cap and stay layout hubs. */
    private static final List<String> TYPE_ORDER = List.of(TYPE_TAG, TYPE_POST, TYPE_NOTE, TYPE_DISH);

    private static final Comparator<GraphNode> NODE_ORDER =
        Comparator.<GraphNode>comparingInt(node -> TYPE_ORDER.indexOf(node.type()))
            .thenComparing(GraphNode::id);

    private static final Comparator<GraphEdge> EDGE_ORDER =
        Comparator.comparing(GraphEdge::source).thenComparing(GraphEdge::target);

    private final PostRepository postRepository;
    private final NoteRepository noteRepository;
    private final DishRepository dishRepository;

    public GraphService(PostRepository postRepository, NoteRepository noteRepository, DishRepository dishRepository) {
        this.postRepository = postRepository;
        this.noteRepository = noteRepository;
        this.dishRepository = dishRepository;
    }

    /**
     * P1-5：只读热点缓存（TTL 兜底 + admin 写操作 evict）；
     * NB-5：全部改为轻量投影查询，构图不再加载文章/笔记正文列。
     * L-16/D-17：游客视图剔除笔记——按 includeNotes 拆两份缓存条目（evict 均为 allEntries，两份同失效）。
     */
    @Cacheable(cacheNames = CacheConfig.GRAPH, key = "#includeNotes")
    public GraphResponse buildGraph(boolean includeNotes) {
        List<GraphNode> contentNodes = new ArrayList<>();
        Set<GraphEdge> edges = new LinkedHashSet<>();
        // normalised tag key -> display label, sorted so tag emission never depends on query order.
        Map<String, String> tagLabels = new TreeMap<>();

        var postTags = groupPairs(postRepository.findPublishedTagRows());
        for (var post : postRepository.findPublishedGraphRows()) {
            if (post == null || post.getId() == null) {
                continue;
            }
            String nodeId = "p-" + post.getId();
            contentNodes.add(new GraphNode(nodeId, post.getTitle(), TYPE_POST, "/articles/" + post.getSlug()));
            linkTags(nodeId, postTags.get(post.getId()), tagLabels, edges);
            linkTag(nodeId, post.getCategory(), tagLabels, edges);
        }

        if (includeNotes) {
            var noteTags = groupPairs(noteRepository.findPublishedTagRows());
            for (var note : noteRepository.findPublishedGraphRows()) {
                if (note == null || note.getId() == null) {
                    continue;
                }
                String nodeId = "n-" + note.getId();
                contentNodes.add(new GraphNode(nodeId, note.getTitle(), TYPE_NOTE, "/notes?note=" + note.getId()));
                linkTags(nodeId, noteTags.get(note.getId()), tagLabels, edges);
                linkTag(nodeId, note.getFolder(), tagLabels, edges);
            }
        }

        for (var dish : dishRepository.findAllPublishedForGraph()) {
            if (dish == null || dish.getId() == null) {
                continue;
            }
            String nodeId = "d-" + dish.getId();
            contentNodes.add(new GraphNode(nodeId, dish.getName(), TYPE_DISH, "/recipes?dish=" + dish.getSlug()));
            linkTag(nodeId, dish.getCategory(), tagLabels, edges);
        }

        List<GraphNode> nodes = new ArrayList<>(tagLabels.size() + contentNodes.size());
        tagLabels.forEach((key, label) -> nodes.add(new GraphNode(tagId(key), label, TYPE_TAG, null)));
        nodes.addAll(contentNodes);
        nodes.sort(NODE_ORDER);

        List<GraphEdge> sortedEdges = new ArrayList<>(edges);
        sortedEdges.sort(EDGE_ORDER);

        return new GraphResponse(List.copyOf(nodes), List.copyOf(sortedEdges));
    }

    /** NB-5：把 [id, tag] 行分组为 id -> tags（一次查询替代逐实体集合加载）。 */
    private static Map<Long, List<String>> groupPairs(List<Object[]> rows) {
        Map<Long, List<String>> grouped = new java.util.HashMap<>();
        for (var row : rows) {
            if (row == null || row.length < 2 || !(row[0] instanceof Long id) || !(row[1] instanceof String tag)) {
                continue;
            }
            grouped.computeIfAbsent(id, key -> new ArrayList<>()).add(tag);
        }
        return grouped;
    }

    private void linkTags(String nodeId, List<String> tags, Map<String, String> tagLabels, Set<GraphEdge> edges) {
        if (tags == null) {
            return;
        }
        for (var tag : tags) {
            linkTag(nodeId, tag, tagLabels, edges);
        }
    }

    private void linkTag(String nodeId, String rawLabel, Map<String, String> tagLabels, Set<GraphEdge> edges) {
        if (rawLabel == null) {
            return;
        }
        String label = rawLabel.trim();
        if (label.isEmpty()) {
            return;
        }
        String key = normalise(label);
        // Case variants collapse onto one hub; pick the label deterministically instead of first-seen.
        tagLabels.merge(key, label, (existing, candidate) -> existing.compareTo(candidate) <= 0 ? existing : candidate);
        edges.add(new GraphEdge(nodeId, tagId(key)));
    }

    private static String normalise(String label) {
        return label.toLowerCase(Locale.ROOT);
    }

    /**
     * Content-addressed tag id: independent of iteration order and safe for any Unicode label
     * (slugifying Chinese labels would collapse them all to the empty string).
     */
    private static String tagId(String key) {
        byte[] digest = sha256(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder id = new StringBuilder(2 + 16);
        id.append("t-");
        for (int i = 0; i < 8; i++) {
            id.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
            id.append(Character.forDigit(digest[i] & 0xF, 16));
        }
        return id.toString();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
