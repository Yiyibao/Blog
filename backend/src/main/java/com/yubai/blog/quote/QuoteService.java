package com.yubai.blog.quote;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class QuoteService {

    private final QuoteRepository repository;

    public QuoteService(QuoteRepository repository) {
        this.repository = repository;
    }

    public List<QuoteResponse> findAll() {
        return repository.findAllByOrderByDisplayOrderAscIdAsc().stream()
            .map(QuoteResponse::from)
            .toList();
    }
}
