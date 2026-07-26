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

    /** P1-5：公开列表缓存；4F 管理写入口已补 evict（TTL 兜底不变）。 */
    @Cacheable(CacheConfig.QUOTES)
    public List<QuoteResponse> findAll() {
        return repository.findAllByOrderByDisplayOrderAscIdAsc().stream()
            .map(QuoteResponse::from)
            .toList();
    }

    // ── 4F：管理端 CRUD（不改迁移即可增删语录；NB-6 按日轮转基于同一有序列表天然生效）──

    public List<AdminQuoteResponse> findAdmin() {
        return repository.findAllByOrderByDisplayOrderAscIdAsc().stream()
            .map(AdminQuoteResponse::from)
            .toList();
    }

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.cache.annotation.CacheEvict(cacheNames = CacheConfig.QUOTES, allEntries = true)
    public AdminQuoteResponse create(QuoteRequest request) {
        return AdminQuoteResponse.from(repository.save(QuoteEntity.create(request)));
    }

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.cache.annotation.CacheEvict(cacheNames = CacheConfig.QUOTES, allEntries = true)
    public AdminQuoteResponse update(long id, QuoteRequest request) {
        var quote = repository.findById(id)
            .orElseThrow(() -> new com.yubai.blog.common.NotFoundException("语录不存在：" + id));
        quote.update(request);
        return AdminQuoteResponse.from(quote);
    }

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.cache.annotation.CacheEvict(cacheNames = CacheConfig.QUOTES, allEntries = true)
    public void delete(long id) {
        if (!repository.existsById(id)) throw new com.yubai.blog.common.NotFoundException("语录不存在：" + id);
        repository.deleteById(id);
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
