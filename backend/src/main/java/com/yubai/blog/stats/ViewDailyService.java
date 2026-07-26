package com.yubai.blog.stats;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 4D：全站日浏览趋势——各详情读 Controller 在去重窗口命中处调用 bump()（编排层挂点，
 * 不动各 Service 构造器）；日窗口取 Asia/Shanghai（与 4A-6 AI 日预算同一约定）。
 * bump 走 REQUIRES_NEW 旁路：趋势统计失败绝不影响详情读主流程。
 */
@Service
public class ViewDailyService {

    static final ZoneId SITE_ZONE = ZoneId.of("Asia/Shanghai");
    static final int RETENTION_DAYS = 180;
    static final int TREND_DAYS = 30;

    private static final Logger log = LoggerFactory.getLogger(ViewDailyService.class);

    public record DayViews(LocalDate day, long views) {}

    private final ViewDailyRepository repository;

    /** 每天只清理一次的轻量闸（进程内即可，多实例最多多跑几次 DELETE，幂等）。 */
    private final AtomicReference<LocalDate> lastPurged = new AtomicReference<>();

    public ViewDailyService(ViewDailyRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bump() {
        try {
            var today = LocalDate.now(SITE_ZONE);
            repository.upsertIncrement(today);
            if (!today.equals(lastPurged.getAndSet(today))) {
                repository.deleteOlderThan(today.minusDays(RETENTION_DAYS));
            }
        } catch (RuntimeException exception) {
            log.warn("view_daily 计数失败: {}", exception.toString());
        }
    }

    /** 最近 30 天趋势，缺日补零（前端折线不断点）。 */
    @Transactional(readOnly = true)
    public List<DayViews> trend() {
        var today = LocalDate.now(SITE_ZONE);
        var since = today.minusDays(TREND_DAYS - 1);
        var byDay = repository.findAllByDayGreaterThanEqualOrderByDayAsc(since).stream()
            .collect(Collectors.toMap(ViewDailyEntity::getDay, Function.identity()));
        var result = new ArrayList<DayViews>(TREND_DAYS);
        for (var day = since; !day.isAfter(today); day = day.plusDays(1)) {
            var row = byDay.get(day);
            result.add(new DayViews(day, row == null ? 0 : row.getViews()));
        }
        return result;
    }
}
