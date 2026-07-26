package com.yubai.blog.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ViewDailyServiceTest {

    @Mock
    ViewDailyRepository repository;

    ViewDailyService service() {
        return new ViewDailyService(repository);
    }

    private static ViewDailyEntity row(LocalDate day, long views) {
        var entity = new ViewDailyEntity() {};
        setField(entity, "day", day);
        setField(entity, "views", views);
        return entity;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = ViewDailyEntity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void bumpUpsertsAndPurgesOncePerDay() {
        var service = service();
        service.bump();
        service.bump();

        verify(repository, times(2)).upsertIncrement(any(LocalDate.class));
        // 同一天只清理一次
        verify(repository, times(1)).deleteOlderThan(any(LocalDate.class));
    }

    @Test
    void bumpFailureIsSwallowed() {
        doThrow(new RuntimeException("db down")).when(repository).upsertIncrement(any(LocalDate.class));

        // 趋势统计是旁路——异常绝不外抛影响详情读
        service().bump();
    }

    @Test
    void trendFillsMissingDaysWithZero() {
        var today = LocalDate.now(ViewDailyService.SITE_ZONE);
        when(repository.findAllByDayGreaterThanEqualOrderByDayAsc(any(LocalDate.class)))
            .thenReturn(List.of(row(today, 5), row(today.minusDays(3), 2)));

        var trend = service().trend();

        assertThat(trend).hasSize(ViewDailyService.TREND_DAYS);
        assertThat(trend.get(trend.size() - 1).day()).isEqualTo(today);
        assertThat(trend.get(trend.size() - 1).views()).isEqualTo(5);
        assertThat(trend.get(trend.size() - 4).views()).isEqualTo(2);
        assertThat(trend.get(0).views()).isZero();
    }
}
