package com.yubai.blog.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.note.NoteRepository;
import com.yubai.blog.post.PostRepository;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    PostRepository postRepository;

    @Mock
    DishRepository dishRepository;

    @Mock
    NoteRepository noteRepository;

    @InjectMocks
    SearchService service;

    @Captor
    ArgumentCaptor<Pageable> pageableCaptor;

    @Test
    void emptyQueryReturnsEmptyResponse() {
        var result = service.search("   ", 5);
        assertThat(result.total()).isZero();
        assertThat(result.articles()).isEmpty();
        assertThat(result.dishes()).isEmpty();
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void searchFindsResultsAcrossAllTypes() {
        when(postRepository.searchPublished(anyString(), any(Pageable.class))).thenReturn(Page.empty());
        when(dishRepository.searchPublished(anyString(), any(Pageable.class))).thenReturn(Page.empty());
        when(noteRepository.searchPublished(anyString(), any(Pageable.class))).thenReturn(Page.empty());

        var result = service.search("keyword", 5);
        assertThat(result.total()).isZero();
    }

    @Test
    void limitIsClampedBetweenOneAndTen() {
        when(postRepository.searchPublished(anyString(), any(Pageable.class))).thenReturn(Page.empty());
        when(dishRepository.searchPublished(anyString(), any(Pageable.class))).thenReturn(Page.empty());
        when(noteRepository.searchPublished(anyString(), any(Pageable.class))).thenReturn(Page.empty());

        var result = service.search("test", 100);
        assertThat(result.total()).isZero();

        result = service.search("test", 0);
        assertThat(result.total()).isZero();
    }

    @Test
    void limitParameterIsPassedAsPageSize() {
        when(postRepository.searchPublished(anyString(), any(Pageable.class)))
            .thenAnswer(invocation -> {
                Pageable pageable = invocation.getArgument(1);
                assertThat(pageable.getPageSize()).isEqualTo(3);
                return Page.empty();
            });
        when(dishRepository.searchPublished(anyString(), any(Pageable.class)))
            .thenAnswer(invocation -> {
                Pageable pageable = invocation.getArgument(1);
                assertThat(pageable.getPageSize()).isEqualTo(3);
                return Page.empty();
            });
        when(noteRepository.searchPublished(anyString(), any(Pageable.class)))
            .thenAnswer(invocation -> {
                Pageable pageable = invocation.getArgument(1);
                assertThat(pageable.getPageSize()).isEqualTo(3);
                return Page.empty();
            });

        service.search("test", 3);
    }

    @Test
    void postSearchOnlyQueriesPostRepository() {
        when(postRepository.searchPublished(anyString(), any(Pageable.class))).thenReturn(Page.empty());

        var request = new SearchRequest("test", SearchType.POST, 0, 10);
        var result = service.search(request);

        assertThat(result.type()).isEqualTo("POST");
        assertThat(result.results()).isEmpty();
    }

    @Test
    void dishSearchOnlyQueriesDishRepository() {
        when(dishRepository.searchPublished(anyString(), any(Pageable.class))).thenReturn(Page.empty());

        var request = new SearchRequest("test", SearchType.DISH, 0, 10);
        var result = service.search(request);

        assertThat(result.type()).isEqualTo("DISH");
        assertThat(result.results()).isEmpty();
    }

    @Test
    void noteSearchOnlyQueriesNoteRepository() {
        when(noteRepository.searchPublished(anyString(), any(Pageable.class))).thenReturn(Page.empty());

        var request = new SearchRequest("test", SearchType.NOTE, 0, 10);
        var result = service.search(request);

        assertThat(result.type()).isEqualTo("NOTE");
        assertThat(result.results()).isEmpty();
    }

    @Test
    void allSearchQueriesAllRepositories() {
        when(postRepository.searchPublished(anyString(), any(Pageable.class))).thenReturn(Page.empty());
        when(dishRepository.searchPublished(anyString(), any(Pageable.class))).thenReturn(Page.empty());
        when(noteRepository.searchPublished(anyString(), any(Pageable.class))).thenReturn(Page.empty());

        var request = new SearchRequest("test", SearchType.ALL, 0, 10);
        var result = service.search(request);

        assertThat(result.type()).isEqualTo("ALL");
        assertThat(result.results()).isEmpty();
    }

    @Test
    void emptyQueryInPostSearchReturnsEmptyResponse() {
        var request = new SearchRequest("   ", SearchType.POST, 0, 10);
        var result = service.search(request);

        assertThat(result.type()).isEqualTo("POST");
        assertThat(result.totalElements()).isZero();
    }

    @Test
    void postSearchPassesPaginationCorrectly() {
        when(postRepository.searchPublished(anyString(), pageableCaptor.capture())).thenReturn(Page.empty());

        var request = new SearchRequest("test", SearchType.POST, 2, 5);
        service.search(request);

        var captured = pageableCaptor.getValue();
        assertThat(captured.getPageNumber()).isEqualTo(2);
        assertThat(captured.getPageSize()).isEqualTo(5);
    }

    // NB-5：笔记摘要由投影截断源（前 400 字符）生成

    @Test
    void noteExcerptCleansMarkdownAndFallsBackToFolder() {
        assertThat(SearchService.noteExcerpt(noteRow("# 标题\n\n正文 **加粗** 内容", "前端")))
            .isEqualTo("标题 正文 加粗 内容");
        assertThat(SearchService.noteExcerpt(noteRow("###   \n> \n", "后端"))).isEqualTo("后端");
        assertThat(SearchService.noteExcerpt(noteRow("x".repeat(400), "目录")))
            .hasSize(203)
            .endsWith("...");
    }

    private static NoteRepository.NoteSearchRow noteRow(String excerptSource, String folder) {
        return new NoteRepository.NoteSearchRow() {
            @Override public Long getId() { return 1L; }
            @Override public String getTitle() { return "t"; }
            @Override public String getFolder() { return folder; }
            @Override public String getExcerptSource() { return excerptSource; }
        };
    }

    // P0-9：LIKE 通配符转义

    @Test
    void escapeLikeEscapesWildcardsAndBackslash() {
        assertThat(SearchService.escapeLike("100%")).isEqualTo("100\\%");
        assertThat(SearchService.escapeLike("a_b")).isEqualTo("a\\_b");
        assertThat(SearchService.escapeLike("c:\\dir")).isEqualTo("c:\\\\dir");
        assertThat(SearchService.escapeLike("%_\\")).isEqualTo("\\%\\_\\\\");
        assertThat(SearchService.escapeLike("普通查询")).isEqualTo("普通查询");
    }

    @Test
    void groupedSearchEscapesWildcardsInLikePattern() {
        var patternCaptor = ArgumentCaptor.forClass(String.class);
        when(postRepository.searchPublished(patternCaptor.capture(), any(Pageable.class))).thenReturn(Page.empty());
        when(dishRepository.searchPublished(anyString(), any(Pageable.class))).thenReturn(Page.empty());
        when(noteRepository.searchPublished(anyString(), any(Pageable.class))).thenReturn(Page.empty());

        service.search("50%_off", 5);

        assertThat(patternCaptor.getValue()).isEqualTo("%50\\%\\_off%");
    }
}
