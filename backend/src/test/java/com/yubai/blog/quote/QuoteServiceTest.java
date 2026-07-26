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

    // NB-6：按日轮转——同日稳定、次日前移一位、年内取模回卷

    private static List<QuoteResponse> quotes(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
            .mapToObj(i -> new QuoteResponse("q-" + i, "内容" + i, "作者" + i, "分类"))
            .toList();
    }

    @Test
    void rotateForDayPutsTheDaysQuoteFirstAndAdvancesDaily() {
        var all = quotes(3);
        // 1 月 1 日 dayOfYear=1 → 偏移 0；1 月 2 日 → 偏移 1；1 月 4 日 → 取模回卷到 0
        assertThat(QuoteService.rotateForDay(all, java.time.LocalDate.of(2026, 1, 1)))
            .extracting(QuoteResponse::id).containsExactly("q-1", "q-2", "q-3");
        assertThat(QuoteService.rotateForDay(all, java.time.LocalDate.of(2026, 1, 2)))
            .extracting(QuoteResponse::id).containsExactly("q-2", "q-3", "q-1");
        assertThat(QuoteService.rotateForDay(all, java.time.LocalDate.of(2026, 1, 4)))
            .extracting(QuoteResponse::id).containsExactly("q-1", "q-2", "q-3");
    }

    @Test
    void rotateForDayIsDeterministicWithinTheSameDay() {
        var all = quotes(5);
        var day = java.time.LocalDate.of(2026, 7, 27);
        assertThat(QuoteService.rotateForDay(all, day)).isEqualTo(QuoteService.rotateForDay(all, day));
    }

    @Test
    void rotateForDayLeavesTinyListsUntouched() {
        assertThat(QuoteService.rotateForDay(List.of(), java.time.LocalDate.of(2026, 3, 15))).isEmpty();
        var single = quotes(1);
        assertThat(QuoteService.rotateForDay(single, java.time.LocalDate.of(2026, 3, 15))).isSameAs(single);
    }
}
