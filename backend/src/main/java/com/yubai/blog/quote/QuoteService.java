package com.yubai.blog.quote;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.config.CacheConfig;

@Service
@Transactional(readOnly = true)
public class QuoteService {

    private final QuoteRepository repository;

    public QuoteService(QuoteRepository repository) {
        this.repository = repository;
    }

    /** P1-5：语录当前无管理写入口，TTL 失效即可（4F 管理端上线时须补 evict）。 */
    @Cacheable(CacheConfig.QUOTES)
    public List<QuoteResponse> findAll() {
        return repository.findAllByOrderByDisplayOrderAscIdAsc().stream()
            .map(QuoteResponse::from)
            .toList();
    }

    /**
     * NB-6：daily 名副其实——按 day-of-year 取模轮转，当日语录固定排在首位，
     * 同一天所有访客看到相同顺序，次日整体前移一位。纯函数，供控制器在缓存代理外调用。
     */
    static List<QuoteResponse> rotateForDay(List<QuoteResponse> quotes, LocalDate day) {
        if (quotes.size() < 2) return quotes;
        int offset = (day.getDayOfYear() - 1) % quotes.size();
        var rotated = new ArrayList<QuoteResponse>(quotes.size());
        rotated.addAll(quotes.subList(offset, quotes.size()));
        rotated.addAll(quotes.subList(0, offset));
        return List.copyOf(rotated);
    }
}
