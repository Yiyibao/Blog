package com.yubai.blog.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayMigrationConfig {

    @Bean
    public FlywayMigrationStrategy migrationSafetyStrategy(
            ProjectArchiveService archiveService,
            FreshInstallBaselineService freshInstallBaselineService,
            @Value("${app.database.remove-historical-demo-content:true}")
                    boolean removeHistoricalDemoContent) {
        return flyway -> {
            var freshEmptySchema = freshInstallBaselineService.isFreshEmptySchema();
            archiveService.archive();
            flyway.migrate();
            if (freshEmptySchema && removeHistoricalDemoContent) {
                freshInstallBaselineService.removeHistoricalDemoRows();
            }
        };
    }
}
