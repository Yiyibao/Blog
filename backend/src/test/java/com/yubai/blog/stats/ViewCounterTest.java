package com.yubai.blog.stats;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ViewCounterTest {

    @Mock ViewDailyService viewDailyService;

    @Mock IntSupplier increment;

    @Test
    void recordsDailyTrendWhenOnePublishedRowWasUpdated() {
        when(increment.getAsInt()).thenReturn(1);

        new ViewCounter(viewDailyService).record(increment);

        verify(viewDailyService).bump();
    }

    @Test
    void doesNotRecordDailyTrendWhenNoPublishedRowWasUpdated() {
        when(increment.getAsInt()).thenReturn(0);

        new ViewCounter(viewDailyService).record(increment);

        verify(viewDailyService, never()).bump();
    }
}
