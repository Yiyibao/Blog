package com.yubai.blog.quote;

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
}
