package com.yubai.blog.admin.ai;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiProviderModelRepository extends JpaRepository<AiProviderModelEntity, Long> {
    List<AiProviderModelEntity> findByProviderIdOrderByModelAsc(Long providerId);

    Optional<AiProviderModelEntity> findByProviderIdAndModel(Long providerId, String model);

    Optional<AiProviderModelEntity> findByProviderIdAndModelAndEnabledTrue(
            Long providerId, String model);

    void deleteByProviderIdAndModelNotIn(Long providerId, List<String> models);
}
