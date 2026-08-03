package com.yubai.blog.admin.ai;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiProviderRepository extends JpaRepository<AiProviderEntity, Long> {
    boolean existsByNameIgnoreCase(String name);

    Optional<AiProviderEntity> findByNameIgnoreCase(String name);

    Optional<AiProviderEntity> findFirstByIsDefaultTrueAndEnabledTrue();

    Optional<AiProviderEntity> findFirstByEnabledTrueOrderByIdAsc();
}
