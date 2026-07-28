package com.yubai.blog.common;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayMigrationConfig {

    @Bean
    public FlywayMigrationStrategy preMigrationArchive(ProjectArchiveService archiveService) {
        return flyway -> {
            archiveService.archive();
            flyway.migrate();
        };
    }
}
