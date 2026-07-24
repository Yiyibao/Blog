package com.yubai.blog.quote;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuoteRepository extends JpaRepository<QuoteEntity, Long> {
    List<QuoteEntity> findAllByOrderByDisplayOrderAscIdAsc();
}
