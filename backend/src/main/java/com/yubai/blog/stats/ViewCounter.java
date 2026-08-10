package com.yubai.blog.stats;

import java.util.function.IntSupplier;
import org.springframework.stereotype.Service;

/** Coordinates resource view increments with the site-wide daily trend. */
@Service
public class ViewCounter {

    private final ViewDailyService viewDailyService;

    public ViewCounter(ViewDailyService viewDailyService) {
        this.viewDailyService = viewDailyService;
    }

    /**
     * Updates the daily trend only when the resource update confirms that one published row was
     * incremented.
     */
    public void record(IntSupplier increment) {
        if (increment.getAsInt() == 1) {
            viewDailyService.bump();
        }
    }
}
