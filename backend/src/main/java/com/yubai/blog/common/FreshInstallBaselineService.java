package com.yubai.blog.common;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Removes historical demonstration rows only when Flyway is initializing a genuinely empty schema.
 * Existing databases keep every row while still validating and applying later migrations.
 */
@Service
public class FreshInstallBaselineService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FreshInstallBaselineService.class);
    private final JdbcTemplate jdbc;

    public FreshInstallBaselineService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public boolean isFreshEmptySchema() {
        var tables =
                jdbc.queryForObject(
                        "select count(*) from information_schema.tables "
                                + "where table_schema = current_schema and table_type = 'BASE TABLE'",
                        Integer.class);
        if (tables == null || tables == 0) return true;
        if (!hasTable("flyway_schema_history")) {
            throw new IllegalStateException(
                    "Refusing to migrate a non-empty schema without Flyway history");
        }
        return false;
    }

    public void removeHistoricalDemoRows() {
        jdbc.execute(
                "truncate table posts, dishes, post_categories, dish_categories, "
                        + "music_tracks, sys_quote restart identity cascade");
        LOGGER.info("Fresh database baseline created without historical demonstration content");
    }

    private boolean hasTable(String name) {
        var rows =
                jdbc.queryForList(
                        "select table_name from information_schema.tables"
                                + " where table_schema = current_schema and table_name = ?",
                        String.class,
                        name);
        return !rows.isEmpty();
    }
}
