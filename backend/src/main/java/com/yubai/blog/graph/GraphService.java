package com.yubai.blog.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.note.NoteRepository;
import com.yubai.blog.post.PostRepository;

@Service
@Transactional(readOnly = true)
public class GraphService {

    private final PostRepository postRepository;
    private final NoteRepository noteRepository;
    private final DishRepository dishRepository;

    public GraphService(PostRepository postRepository, NoteRepository noteRepository, DishRepository dishRepository) {
        this.postRepository = postRepository;
        this.noteRepository = noteRepository;
        this.dishRepository = dishRepository;
    }

    public GraphResponse buildGraph() {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Map<String, String> tagNodeIds = new LinkedHashMap<>();

        var posts = postRepository.findAllPublishedWithTags();
        for (var post : posts) {
            String nodeId = "p-" + post.getId();
            nodes.add(new GraphNode(nodeId, post.getTitle(), "POST", "/articles/" + post.getSlug()));

            for (var tag : post.getTags()) {
                String tagId = ensureTagNode(tag, tagNodeIds, nodes);
                edges.add(new GraphEdge(nodeId, tagId));
            }

            String catTagId = ensureTagNode(post.getCategory(), tagNodeIds, nodes);
            edges.add(new GraphEdge(nodeId, catTagId));
        }

        var notes = noteRepository.findAllPublishedWithTags();
        for (var note : notes) {
            String nodeId = "n-" + note.getId();
            nodes.add(new GraphNode(nodeId, note.getTitle(), "NOTE", "/notes?note=" + note.getId()));

            for (var tag : note.getTags()) {
                String tagId = ensureTagNode(tag, tagNodeIds, nodes);
                edges.add(new GraphEdge(nodeId, tagId));
            }

            String folderTagId = ensureTagNode(note.getFolder(), tagNodeIds, nodes);
            edges.add(new GraphEdge(nodeId, folderTagId));
        }

        var dishes = dishRepository.findAllPublishedForGraph();
        for (var dish : dishes) {
            String nodeId = "d-" + dish.getId();
            nodes.add(new GraphNode(nodeId, dish.getName(), "DISH", "/recipes?dish=" + dish.getSlug()));

            String catTagId = ensureTagNode(dish.getCategory(), tagNodeIds, nodes);
            edges.add(new GraphEdge(nodeId, catTagId));
        }

        return new GraphResponse(nodes, edges);
    }

    private String ensureTagNode(String tagName, Map<String, String> existing, List<GraphNode> nodes) {
        String key = tagName.trim().toLowerCase();
        if (existing.containsKey(key)) {
            return existing.get(key);
        }
        String tagId = "t-" + (existing.size() + 1);
        existing.put(key, tagId);
        nodes.add(new GraphNode(tagId, tagName, "TAG", "/categories"));
        return tagId;
    }
}
