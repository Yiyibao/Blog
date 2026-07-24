package com.yubai.blog.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
}
