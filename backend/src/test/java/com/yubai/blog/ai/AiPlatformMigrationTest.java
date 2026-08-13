package com.yubai.blog.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.yubai.blog.TestDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AiPlatformMigrationTest {
    private static final String FRESH_SCHEMA = "ai_platform_fresh_test";
    private static final String UPGRADE_SCHEMA = "ai_platform_upgrade_test";
    private static final String ROLLBACK_SCHEMA = "ai_platform_rollback_test";
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setup() {
        var config = new HikariConfig();
        config.setJdbcUrl(TestDatabase.URL);
        config.setUsername(TestDatabase.USERNAME);
        config.setPassword(TestDatabase.PASSWORD);
        config.setMaximumPoolSize(2);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        recreate(FRESH_SCHEMA);
        recreate(UPGRADE_SCHEMA);
        recreate(ROLLBACK_SCHEMA);
    }

    @AfterAll
    static void cleanup() {
        try {
            jdbc.execute("drop schema if exists " + FRESH_SCHEMA + " cascade");
            jdbc.execute("drop schema if exists " + UPGRADE_SCHEMA + " cascade");
            jdbc.execute("drop schema if exists " + ROLLBACK_SCHEMA + " cascade");
        } finally {
            dataSource.close();
        }
    }

    @Test
    void v53ApplicationContractRemainsAvailableAfterExpandOnlyV54() {
        migrate(ROLLBACK_SCHEMA, "53");
        var v53Columns =
                jdbc.queryForList(
                        "select table_name || '.' || column_name from information_schema.columns "
                                + "where table_schema = ? and table_name in "
                                + "('ai_sessions','ai_tasks','ai_task_parts','ai_task_events',"
                                + "'ai_files','ai_memories','ai_artifacts')",
                        String.class,
                        ROLLBACK_SCHEMA);

        migrate(ROLLBACK_SCHEMA, null);

        var v54Columns =
                jdbc.queryForList(
                        "select table_name || '.' || column_name from information_schema.columns "
                                + "where table_schema = ?",
                        String.class,
                        ROLLBACK_SCHEMA);
        assertThat(v54Columns).containsAll(v53Columns);
        assertThat(columnIsNullable(ROLLBACK_SCHEMA, "ai_sessions", "project_id")).isTrue();
        assertThat(columnIsNullable(ROLLBACK_SCHEMA, "ai_tasks", "resolved_provider_id")).isTrue();
        assertThat(columnIsNullable(ROLLBACK_SCHEMA, "ai_tasks", "required_capabilities")).isTrue();
    }

    @Test
    void freshAndV52UpgradeBothCreateTheCompleteM2Schema() {
        migrate(FRESH_SCHEMA, null);
        migrate(UPGRADE_SCHEMA, "52");
        migrate(UPGRADE_SCHEMA, null);

        for (var schema : List.of(FRESH_SCHEMA, UPGRADE_SCHEMA)) {
            var tables =
                    jdbc.queryForList(
                            "select table_name from information_schema.tables "
                                    + "where table_schema = ? and table_name like 'ai_%'",
                            String.class, schema);
            assertThat(tables)
                    .contains(
                            "ai_sessions",
                            "ai_tasks",
                            "ai_task_parts",
                            "ai_task_events",
                            "ai_files",
                            "ai_memories",
                            "ai_artifacts",
                            "ai_projects",
                            "ai_provider_models");
            var version =
                    jdbc.queryForObject(
                            "select version from "
                                    + schema
                                    + ".flyway_schema_history where success "
                                    + "order by installed_rank desc limit 1",
                            String.class);
            assertThat(version).isEqualTo("61");
            assertThat(columnExists(schema, "ai_sessions", "project_id")).isTrue();
            assertThat(columnExists(schema, "ai_tasks", "resolved_provider_id")).isTrue();
            assertThat(columnExists(schema, "ai_tasks", "required_capabilities")).isTrue();
        }
    }

    private static boolean columnExists(String schema, String table, String column) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists (select 1 from information_schema.columns "
                                + "where table_schema = ? and table_name = ? and column_name = ?)",
                        Boolean.class,
                        schema,
                        table,
                        column));
    }

    private static boolean columnIsNullable(String schema, String table, String column) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select is_nullable = 'YES' from information_schema.columns "
                                + "where table_schema = ? and table_name = ? and column_name = ?",
                        Boolean.class,
                        schema,
                        table,
                        column));
    }

    private static void migrate(String schema, String target) {
        var configuration =
                Flyway.configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }

    private static void recreate(String schema) {
        jdbc.execute("drop schema if exists " + schema + " cascade");
        jdbc.execute("create schema " + schema);
    }
}
