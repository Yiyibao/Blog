package com.yubai.blog.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

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

    @Test
    void buildGraphReturnsNodesAndEdges() {
        var post = new PostEntity() {
            @Override public Long getId() { return 1L; }
            @Override public String getTitle() { return "设计系统与透明度"; }
            @Override public String getSlug() { return "clarity-by-design"; }
            @Override public String getCategory() { return "设计札记"; }
            @Override public List<String> getTags() { return List.of("产品设计", "信息架构"); }
        };
        var note = new NoteEntity() {
            @Override public Long getId() { return 1L; }
            @Override public String getTitle() { return "Canvas 性能优化"; }
            @Override public String getFolder() { return "前端"; }
            @Override public List<String> getTags() { return List.of("前端架构"); }
        };
        var dish = new DishEntity() {
            @Override public Long getId() { return 1L; }
            @Override public String getName() { return "糖醋排骨"; }
            @Override public String getSlug() { return "sweet-sour-pork"; }
            @Override public String getCategory() { return "粤式家常"; }
        };

        when(postRepository.findAllPublishedWithTags()).thenReturn(List.of(post));
        when(noteRepository.findAllPublishedWithTags()).thenReturn(List.of(note));
        when(dishRepository.findAllPublishedForGraph()).thenReturn(List.of(dish));

        var result = service.buildGraph();

        assertThat(result.nodes()).isNotEmpty();
        assertThat(result.edges()).isNotEmpty();

        var postNode = result.nodes().stream().filter(n -> n.id().equals("p-1")).findFirst().orElseThrow();
        assertThat(postNode.label()).isEqualTo("设计系统与透明度");
        assertThat(postNode.type()).isEqualTo("POST");
        assertThat(postNode.url()).isEqualTo("/articles/clarity-by-design");

        var noteNode = result.nodes().stream().filter(n -> n.id().equals("n-1")).findFirst().orElseThrow();
        assertThat(noteNode.type()).isEqualTo("NOTE");
        assertThat(noteNode.url()).isEqualTo("/notes?note=1");

        var dishNode = result.nodes().stream().filter(n -> n.id().equals("d-1")).findFirst().orElseThrow();
        assertThat(dishNode.type()).isEqualTo("DISH");
        assertThat(dishNode.url()).isEqualTo("/recipes?dish=sweet-sour-pork");

        var tagNodes = result.nodes().stream().filter(n -> n.type().equals("TAG")).toList();
        assertThat(tagNodes).extracting("label").contains("产品设计", "信息架构", "设计札记", "前端架构", "前端", "粤式家常");
    }

    @Test
    void buildGraphReturnsEmptyWhenNoContent() {
        when(postRepository.findAllPublishedWithTags()).thenReturn(List.of());
        when(noteRepository.findAllPublishedWithTags()).thenReturn(List.of());
        when(dishRepository.findAllPublishedForGraph()).thenReturn(List.of());

        var result = service.buildGraph();
        assertThat(result.nodes()).isEmpty();
        assertThat(result.edges()).isEmpty();
    }
}
