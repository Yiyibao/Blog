package com.yubai.blog.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yubai.blog.dish.DishEntity;
import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.note.NoteEntity;
import com.yubai.blog.note.NoteRepository;
import com.yubai.blog.post.PostEntity;
import com.yubai.blog.post.PostRepository;

@ExtendWith(MockitoExtension.class)
class GraphServiceTest {

    @Mock
    PostRepository postRepository;

    @Mock
    NoteRepository noteRepository;

    @Mock
    DishRepository dishRepository;

    @InjectMocks
    GraphService service;

    private static PostEntity post(long id, String title, String slug, String category, List<String> tags) {
        return new PostEntity() {
            @Override public Long getId() { return id; }
            @Override public String getTitle() { return title; }
            @Override public String getSlug() { return slug; }
            @Override public String getCategory() { return category; }
            @Override public List<String> getTags() { return tags; }
        };
    }

    private static NoteEntity note(long id, String title, String folder, List<String> tags) {
        return new NoteEntity() {
            @Override public Long getId() { return id; }
            @Override public String getTitle() { return title; }
            @Override public String getFolder() { return folder; }
            @Override public List<String> getTags() { return tags; }
        };
    }

    private static DishEntity dish(long id, String name, String slug, String category) {
        return new DishEntity() {
            @Override public Long getId() { return id; }
            @Override public String getName() { return name; }
            @Override public String getSlug() { return slug; }
            @Override public String getCategory() { return category; }
        };
    }

    private void stubAll(List<PostEntity> posts, List<NoteEntity> notes, List<DishEntity> dishes) {
        when(postRepository.findAllPublishedWithTags()).thenReturn(posts);
        when(noteRepository.findAllPublishedWithTags()).thenReturn(notes);
        when(dishRepository.findAllPublishedForGraph()).thenReturn(dishes);
    }

    private static GraphNode nodeById(GraphResponse response, String id) {
        return response.nodes().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }

    private static GraphNode tagByLabel(GraphResponse response, String label) {
        return response.nodes().stream()
            .filter(n -> n.type().equals("TAG") && n.label().equals(label))
            .findFirst()
            .orElseThrow();
    }

    @Test
    void contentNodesKeepTheirRealUrls() {
        stubAll(
            List.of(post(1L, "设计系统与透明度", "clarity-by-design", "设计札记", List.of("产品设计", "信息架构"))),
            List.of(note(1L, "Canvas 性能优化", "前端", List.of("前端架构"))),
            List.of(dish(1L, "糖醋排骨", "sweet-sour-pork", "粤式家常"))
        );

        var result = service.buildGraph();

        var postNode = nodeById(result, "p-1");
        assertThat(postNode.label()).isEqualTo("设计系统与透明度");
        assertThat(postNode.type()).isEqualTo("POST");
        assertThat(postNode.url()).isEqualTo("/articles/clarity-by-design");

        var noteNode = nodeById(result, "n-1");
        assertThat(noteNode.type()).isEqualTo("NOTE");
        assertThat(noteNode.url()).isEqualTo("/notes?note=1");

        var dishNode = nodeById(result, "d-1");
        assertThat(dishNode.type()).isEqualTo("DISH");
        assertThat(dishNode.url()).isEqualTo("/recipes?dish=sweet-sour-pork");

        assertThat(result.nodes()).filteredOn(n -> n.type().equals("TAG"))
            .extracting(GraphNode::label)
            .containsExactlyInAnyOrder("产品设计", "信息架构", "设计札记", "前端架构", "前端", "粤式家常");
    }

    @Test
    void tagNodesHaveNullUrlBecausePublicCategoryPagesWereRemoved() {
        stubAll(
            List.of(post(1L, "设计系统与透明度", "clarity-by-design", "设计札记", List.of("产品设计"))),
            List.of(note(1L, "Canvas 性能优化", "前端", List.of("前端架构"))),
            List.of(dish(1L, "糖醋排骨", "sweet-sour-pork", "粤式家常"))
        );

        var result = service.buildGraph();

        var tagNodes = result.nodes().stream().filter(n -> n.type().equals("TAG")).toList();
        assertThat(tagNodes).isNotEmpty();
        assertThat(tagNodes).allSatisfy(node -> assertThat(node.url()).isNull());
        assertThat(result.nodes()).noneMatch(n -> "/categories".equals(n.url()));
    }

    @Test
    void tagIdsAndOutputOrderAreStableWhenSourceOrderIsShuffled() {
        var posts = new ArrayList<>(List.of(
            post(1L, "设计系统与透明度", "clarity-by-design", "设计札记", List.of("产品设计", "信息架构")),
            post(2L, "类型驱动开发", "type-driven", "工程实践", List.of("信息架构", "TypeScript")),
            post(3L, "缓存分层", "cache-layers", "工程实践", List.of("性能"))
        ));
        var notes = new ArrayList<>(List.of(
            note(1L, "Canvas 性能优化", "前端", List.of("性能", "前端架构")),
            note(2L, "JVM 内存模型", "后端", List.of("性能"))
        ));
        var dishes = new ArrayList<>(List.of(
            dish(1L, "糖醋排骨", "sweet-sour-pork", "粤式家常"),
            dish(2L, "麻婆豆腐", "mapo-tofu", "川味")
        ));

        stubAll(posts, notes, dishes);
        var baseline = service.buildGraph();

        var random = new Random(20260726L);
        for (int i = 0; i < 5; i++) {
            Collections.shuffle(posts, random);
            Collections.shuffle(notes, random);
            Collections.shuffle(dishes, random);

            var shuffled = service.buildGraph();

            assertThat(shuffled.nodes()).containsExactlyElementsOf(baseline.nodes());
            assertThat(shuffled.edges()).containsExactlyElementsOf(baseline.edges());
        }

        // Unicode labels must still yield distinct, non-empty ids.
        var tagIds = baseline.nodes().stream().filter(n -> n.type().equals("TAG")).map(GraphNode::id).toList();
        assertThat(tagIds).doesNotHaveDuplicates().allSatisfy(id -> assertThat(id).matches("t-[0-9a-f]{16}"));
        assertThat(tagByLabel(baseline, "设计札记").id()).isNotEqualTo(tagByLabel(baseline, "工程实践").id());
    }

    @Test
    void blankAndNullRelationValuesAreIgnoredInsteadOfCreatingEmptyNodes() {
        var posts = new ArrayList<PostEntity>();
        posts.add(post(1L, "无分类文章", "no-category", null, java.util.Arrays.asList("  ", null, "有效标签")));
        posts.add(post(2L, "空白分类", "blank-category", "   ", null));

        var notes = new ArrayList<NoteEntity>();
        notes.add(note(1L, "无目录笔记", "", List.of("")));

        var dishes = new ArrayList<DishEntity>();
        dishes.add(dish(1L, "无分类菜", "no-cat-dish", null));

        stubAll(posts, notes, dishes);

        var result = service.buildGraph();

        assertThat(result.nodes()).extracting(GraphNode::id).contains("p-1", "p-2", "n-1", "d-1");
        assertThat(result.nodes()).filteredOn(n -> n.type().equals("TAG"))
            .extracting(GraphNode::label)
            .containsExactly("有效标签");
        assertThat(result.nodes()).noneMatch(n -> n.label() != null && n.label().isBlank());

        var tagId = tagByLabel(result, "有效标签").id();
        assertThat(result.edges()).containsExactly(new GraphEdge("p-1", tagId));
    }

    @Test
    void duplicateRelationValuesProduceASingleEdgeAndASingleTagNode() {
        stubAll(
            List.of(post(1L, "重复标签", "dupes", "前端", List.of("前端", " 前端 ", "前端架构", "前端架构"))),
            List.of(),
            List.of()
        );

        var result = service.buildGraph();

        assertThat(result.edges()).doesNotHaveDuplicates().hasSize(2);
        assertThat(result.nodes()).filteredOn(n -> n.type().equals("TAG"))
            .extracting(GraphNode::label)
            .containsExactlyInAnyOrder("前端", "前端架构");
        assertThat(result.edges()).allSatisfy(edge -> assertThat(edge.source()).isEqualTo("p-1"));
    }

    @Test
    void caseVariantsOfTheSameTagCollapseIntoOneHub() {
        stubAll(
            List.of(
                post(1L, "A", "a", "Engineering", List.of("TypeScript")),
                post(2L, "B", "b", "engineering", List.of("typescript"))
            ),
            List.of(),
            List.of()
        );

        var result = service.buildGraph();

        var tagNodes = result.nodes().stream().filter(n -> n.type().equals("TAG")).toList();
        assertThat(tagNodes).hasSize(2);
        assertThat(result.edges()).hasSize(4).doesNotHaveDuplicates();
    }

    @Test
    void buildGraphReturnsEmptyWhenNoContent() {
        stubAll(List.of(), List.of(), List.of());

        var result = service.buildGraph();

        assertThat(result.nodes()).isEmpty();
        assertThat(result.edges()).isEmpty();
    }
}
