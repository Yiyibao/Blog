package com.yubai.blog.search;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchTelemetryEventRepository
        extends JpaRepository<SearchTelemetryEventEntity, UUID> {}
