package com.yubai.blog.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuoteServiceTest {

    @Mock
    QuoteRepository repository;

    @InjectMocks
    QuoteService service;

    private QuoteEntity mockQuote(long id, String content, String author, String category) {
        return new QuoteEntity() {
            @Override public Long getId() { return id; }
            @Override public String getContent() { return content; }
            @Override public String getAuthor() { return author; }
            @Override public String getCategory() { return category; }
            @Override public int getDisplayOrder() { return (int) id; }
        };
    }

    @Test
    void findAllReturnsQuotesOrdered() {
        var q1 = mockQuote(1L, "代码是写给人看的", "《SICP》", "极客哲学");
        var q2 = mockQuote(2L, "留白，不是空无", "余白手记", "设计美学");
        when(repository.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of(q1, q2));

        var result = service.findAll();
        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo("q-1");
        assertThat(result.get(0).content()).isEqualTo("代码是写给人看的");
        assertThat(result.get(0).author()).isEqualTo("《SICP》");
        assertThat(result.get(0).category()).isEqualTo("极客哲学");
    }

    @Test
    void findAllReturnsEmptyListWhenNoQuotes() {
        when(repository.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of());
        var result = service.findAll();
        assertThat(result).isEmpty();
    }
}
