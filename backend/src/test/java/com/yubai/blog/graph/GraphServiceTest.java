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

import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.note.NoteRepository;
import com.yubai.blog.post.PostRepository;
import com.yubai.blog.series.SeriesService;

/** NB-5 之后图谱由轻量投影行 + 标签边行构建，测试数据以行形式 stub（不再构造整实体）。 */
@ExtendWith(MockitoExtension.class)
class GraphServiceTest {

    @Mock
    PostRepository postRepository;

    @Mock
    NoteRepository noteRepository;

    @Mock
    DishRepository dishRepository;

    // 4B：未打桩默认空 Map——既有用例不出 SERIES 节点
    @Mock
    SeriesService seriesService;

    @InjectMocks
    GraphService service;

    private record P(long id, String title, String slug, String category, List<String> tags) {}
    private record N(long id, String title, String folder, List<String> tags) {}
    private record D(long id, String name, String slug, String category) {}

    private static PostRepository.PostGraphRow row(P p) {
        return new PostRepository.PostGraphRow() {
            @Override public Long getId() { return p.id(); }
            @Override public String getTitle() { return p.title(); }
            @Override public String getSlug() { return p.slug(); }
            @Override public String getCategory() { return p.category(); }
        };
    }

    private static NoteRepository.NoteGraphRow row(N n) {
        return new NoteRepository.NoteGraphRow() {
            @Override public Long getId() { return n.id(); }
            @Override public String getTitle() { return n.title(); }
            @Override public String getFolder() { return n.folder(); }
        };
    }

    private static DishRepository.DishGraphRow row(D d) {
        return new DishRepository.DishGraphRow() {
            @Override public Long getId() { return d.id(); }
            @Override public String getName() { return d.name(); }
            @Override public String getSlug() { return d.slug(); }
            @Override public String getCategory() { return d.category(); }
        };
    }

    private static List<Object[]> postTagRows(List<P> posts) {
        var rows = new ArrayList<Object[]>();
        for (var p : posts) {
            if (p.tags() == null) continue;
            for (var tag : p.tags()) rows.add(new Object[]{p.id(), tag});
        }
        return rows;
    }

    private static List<Object[]> noteTagRows(List<N> notes) {
        var rows = new ArrayList<Object[]>();
        for (var n : notes) {
            if (n.tags() == null) continue;
            for (var tag : n.tags()) rows.add(new Object[]{n.id(), tag});
        }
        return rows;
    }

    private void stubAll(List<P> posts, List<N> notes, List<D> dishes) {
        when(postRepository.findPublishedGraphRows()).thenAnswer(inv -> posts.stream().map(GraphServiceTest::row).toList());
        when(postRepository.findPublishedTagRows()).thenAnswer(inv -> postTagRows(posts));
        when(noteRepository.findPublishedGraphRows()).thenAnswer(inv -> notes.stream().map(GraphServiceTest::row).toList());
        when(noteRepository.findPublishedTagRows()).thenAnswer(inv -> noteTagRows(notes));
        when(dishRepository.findAllPublishedForGraph()).thenAnswer(inv -> dishes.stream().map(GraphServiceTest::row).toList());
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
            List.of(new P(1L, "设计系统与透明度", "clarity-by-design", "设计札记", List.of("产品设计", "信息架构"))),
            List.of(new N(1L, "Canvas 性能优化", "前端", List.of("前端架构"))),
            List.of(new D(1L, "糖醋排骨", "sweet-sour-pork", "粤式家常"))
        );

        var result = service.buildGraph(true);

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
    void guestGraphExcludesNotesEntirely() {
        // L-16/D-17：游客视图连笔记仓库都不触碰——节点/边/纯笔记标签一并消失
        when(postRepository.findPublishedGraphRows())
            .thenReturn(List.of(row(new P(1L, "文章", "post-a", "设计", List.of("共享标签")))));
        when(postRepository.findPublishedTagRows())
            .thenReturn(List.<Object[]>of(new Object[]{1L, "共享标签"}));
        when(dishRepository.findAllPublishedForGraph()).thenReturn(List.of());

        var result = service.buildGraph(false);

        assertThat(result.nodes()).noneMatch(node -> node.type().equals("NOTE"));
        assertThat(result.edges()).noneMatch(edge -> edge.source().startsWith("n-"));
        assertThat(nodeById(result, "p-1").type()).isEqualTo("POST");
        org.mockito.Mockito.verifyNoInteractions(noteRepository);
    }

    // 5C：子图抽取（纯函数 BFS）

    @Test
    void subgraphDepthOneKeepsCenterAndDirectNeighborsOnly() {
        stubAll(
            List.of(new P(1L, "A", "a", "设计札记", List.of("产品设计")),
                    new P(2L, "B", "b", "设计札记", List.of("信息架构"))),
            List.of(),
            List.of()
        );
        var graph = service.buildGraph(true); // true：消费 stubAll 的全部桩（strict stubs）
        var designHub = tagByLabel(graph, "设计札记");

        var sub = GraphService.extractSubgraph(graph, "p-1", 1);

        // p-1 的直接邻居：产品设计 + 设计札记（分类枢纽）；p-2 在两跳外不进
        assertThat(sub.nodes()).extracting(GraphNode::id)
            .containsExactlyInAnyOrder("p-1", designHub.id(), tagByLabel(graph, "产品设计").id());
        assertThat(sub.edges()).allSatisfy(edge -> {
            assertThat(sub.nodes()).extracting(GraphNode::id).contains(edge.source());
            assertThat(sub.nodes()).extracting(GraphNode::id).contains(edge.target());
        });
    }

    @Test
    void subgraphDepthTwoReachesPostsSharingAHub() {
        stubAll(
            List.of(new P(1L, "A", "a", "设计札记", List.of()),
                    new P(2L, "B", "b", "设计札记", List.of()),
                    new P(3L, "C", "c", "前端", List.of())),
            List.of(),
            List.of()
        );
        var graph = service.buildGraph(true);

        var sub = GraphService.extractSubgraph(graph, "p-1", 2);

        // 两跳经「设计札记」枢纽到 p-2；p-3 挂在另一枢纽不可达
        assertThat(sub.nodes()).extracting(GraphNode::id).contains("p-1", "p-2");
        assertThat(sub.nodes()).extracting(GraphNode::id).doesNotContain("p-3");
    }

    @Test
    void subgraphUnknownCenterThrowsNotFound() {
        stubAll(List.of(new P(1L, "A", "a", "设计札记", List.of())), List.of(), List.of());
        var graph = service.buildGraph(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                GraphService.extractSubgraph(graph, "p-999", 2))
            .isInstanceOf(com.yubai.blog.common.NotFoundException.class);
    }

    @Test
    void tagNodesLinkToPublicTagPages() {
        // 5B：TAG 节点补链 /tags/{label}（旧契约 url=null 废止；/categories 死链不复现）
        stubAll(
            List.of(new P(1L, "设计系统与透明度", "clarity-by-design", "设计札记", List.of("产品设计"))),
            List.of(new N(1L, "Canvas 性能优化", "前端", List.of("前端架构"))),
            List.of(new D(1L, "糖醋排骨", "sweet-sour-pork", "粤式家常"))
        );

        var result = service.buildGraph(true);

        var tagNodes = result.nodes().stream().filter(n -> n.type().equals("TAG")).toList();
        assertThat(tagNodes).isNotEmpty();
        assertThat(tagNodes).allSatisfy(node ->
            assertThat(node.url()).isEqualTo("/tags/" + node.label()));
        assertThat(result.nodes()).noneMatch(n -> "/categories".equals(n.url()));
    }

    @Test
    void tagIdsAndOutputOrderAreStableWhenSourceOrderIsShuffled() {
        var posts = new ArrayList<>(List.of(
            new P(1L, "设计系统与透明度", "clarity-by-design", "设计札记", List.of("产品设计", "信息架构")),
            new P(2L, "类型驱动开发", "type-driven", "工程实践", List.of("信息架构", "TypeScript")),
            new P(3L, "缓存分层", "cache-layers", "工程实践", List.of("性能"))
        ));
        var notes = new ArrayList<>(List.of(
            new N(1L, "Canvas 性能优化", "前端", List.of("性能", "前端架构")),
            new N(2L, "JVM 内存模型", "后端", List.of("性能"))
        ));
        var dishes = new ArrayList<>(List.of(
            new D(1L, "糖醋排骨", "sweet-sour-pork", "粤式家常"),
            new D(2L, "麻婆豆腐", "mapo-tofu", "川味")
        ));

        stubAll(posts, notes, dishes);
        var baseline = service.buildGraph(true);

        var random = new Random(20260726L);
        for (int i = 0; i < 5; i++) {
            Collections.shuffle(posts, random);
            Collections.shuffle(notes, random);
            Collections.shuffle(dishes, random);

            var shuffled = service.buildGraph(true);

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
        var posts = new ArrayList<P>();
        posts.add(new P(1L, "无分类文章", "no-category", null, java.util.Arrays.asList("  ", null, "有效标签")));
        posts.add(new P(2L, "空白分类", "blank-category", "   ", null));

        var notes = new ArrayList<N>();
        notes.add(new N(1L, "无目录笔记", "", List.of("")));

        var dishes = new ArrayList<D>();
        dishes.add(new D(1L, "无分类菜", "no-cat-dish", null));

        stubAll(posts, notes, dishes);

        var result = service.buildGraph(true);

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
            List.of(new P(1L, "重复标签", "dupes", "前端", List.of("前端", " 前端 ", "前端架构", "前端架构"))),
            List.of(),
            List.of()
        );

        var result = service.buildGraph(true);

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
                new P(1L, "A", "a", "Engineering", List.of("TypeScript")),
                new P(2L, "B", "b", "engineering", List.of("typescript"))
            ),
            List.of(),
            List.of()
        );

        var result = service.buildGraph(true);

        var tagNodes = result.nodes().stream().filter(n -> n.type().equals("TAG")).toList();
        assertThat(tagNodes).hasSize(2);
        assertThat(result.edges()).hasSize(4).doesNotHaveDuplicates();
    }

    @Test
    void subgraphEdgesOnlyConnectNodesWithinResult() {
        // 3 个菜品共享 1 个分类枢纽；center d-1 depth 1 只取 d-1 + 分类 + 该分类下全部邻居
        stubAll(
            List.of(),
            List.of(),
            List.of(new D(1L, "糖醋排骨", "sweet-sour-pork", "粤式家常"),
                    new D(2L, "白切鸡", "white-cut-chicken", "粤式家常"),
                    new D(3L, "麻婆豆腐", "mapo-tofu", "川味"))
        );
        var graph = service.buildGraph(true);
        var sub = GraphService.extractSubgraph(graph, "d-1", 3);
        // 子图边两端必须都在子图节点集中
        assertThat(sub.edges()).allSatisfy(edge -> {
            assertThat(sub.nodes()).extracting(GraphNode::id).contains(edge.source());
            assertThat(sub.nodes()).extracting(GraphNode::id).contains(edge.target());
        });
        // d-1 和同分类的 d-2 都在子图中，d-3 通过另一分类不可达
        assertThat(sub.nodes()).extracting(GraphNode::id).contains("d-1", "d-2");
        assertThat(sub.nodes()).extracting(GraphNode::id).doesNotContain("d-3");
    }

    @Test
    void subgraphOnGuestGraphHidesNoteCenters() {
        when(postRepository.findPublishedGraphRows())
            .thenReturn(List.of(row(new P(1L, "文章", "post-a", "设计札记", List.of()))));
        when(postRepository.findPublishedTagRows())
            .thenReturn(List.<Object[]>of());
        when(dishRepository.findAllPublishedForGraph()).thenReturn(List.of());
        var guestGraph = service.buildGraph(false);
        // NOTE 节点在游客图中不存在，以其为中心的请求应 404
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                GraphService.extractSubgraph(guestGraph, "n-1", 2))
            .isInstanceOf(com.yubai.blog.common.NotFoundException.class);
        // POST 中心在游客图中正常
        var sub = GraphService.extractSubgraph(guestGraph, "p-1", 1);
        assertThat(sub.nodes()).extracting(GraphNode::id).contains("p-1");
        assertThat(sub.nodes()).noneMatch(n -> n.type().equals("NOTE"));
    }

    @Test
    void subgraphDepthFourReachesFurtherNodes() {
        // p-1 —tagA— p-2 —tagB— p-3（四跳链：p-1→tagA→p-2→tagB→p-3，depth=4 可达）
        stubAll(
            List.of(new P(1L, "A", "a", "设计札记", List.of("tagA")),
                    new P(2L, "B", "b", "工程实践", List.of("tagA", "tagB")),
                    new P(3L, "C", "c", "前端", List.of("tagB"))),
            List.of(),
            List.of()
        );
        var graph = service.buildGraph(true);
        // depth=1: 仅 p-1 + tagA + 设计札记
        var d1 = GraphService.extractSubgraph(graph, "p-1", 1);
        assertThat(d1.nodes()).extracting(GraphNode::id).contains("p-1")
            .doesNotContain("p-2", "p-3");
        // depth=2: 经 tagA 到 p-2
        var d2 = GraphService.extractSubgraph(graph, "p-1", 2);
        assertThat(d2.nodes()).extracting(GraphNode::id).contains("p-1", "p-2")
            .doesNotContain("p-3");
        // depth=4: 经 p-2→tagB→p-3（纯函数无 depth 上限约束）
        var d4 = GraphService.extractSubgraph(graph, "p-1", 4);
        assertThat(d4.nodes()).extracting(GraphNode::id).contains("p-1", "p-2", "p-3");
    }

    @Test
    void buildGraphReturnsEmptyWhenNoContent() {
        stubAll(List.of(), List.of(), List.of());

        var result = service.buildGraph(true);

        assertThat(result.nodes()).isEmpty();
        assertThat(result.edges()).isEmpty();
    }
}
