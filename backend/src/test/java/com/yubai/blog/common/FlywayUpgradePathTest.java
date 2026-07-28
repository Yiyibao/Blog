package com.yubai.blog.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.yubai.blog.TestDatabase;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlywayUpgradePathTest {

    private static final String SCHEMA = "upgrade_path_test";
    private static HikariDataSource ds;
    private static JdbcTemplate jdbc;
    private static ProjectArchiveService archiveService;

    @BeforeAll
    static void setup() {
        var config = new HikariConfig();
        config.setJdbcUrl(TestDatabase.URL);
        config.setUsername(TestDatabase.USERNAME);
        config.setPassword(TestDatabase.PASSWORD);
        config.setMaximumPoolSize(2);
        config.setConnectionInitSql("set search_path to " + SCHEMA);
        ds = new HikariDataSource(config);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("drop schema if exists " + SCHEMA + " cascade");
        jdbc.execute("create schema " + SCHEMA);
        archiveService = new ProjectArchiveService(ds);
    }

    @AfterAll
    static void cleanup() {
        try {
            jdbc.execute("drop schema if exists " + SCHEMA + " cascade");
        } finally {
            ds.close();
        }
    }

    private void migrateTo(String version) {
        Flyway.configure()
            .dataSource(ds)
            .schemas(SCHEMA)
            .locations("classpath:db/migration")
            .target(version)
            .load()
            .migrate();
    }

    @Test
    @Order(1)
    void step1_migrateToV4() {
        migrateTo("4");

        var count = jdbc.queryForObject(
            "select count(*) from " + SCHEMA + ".projects", Integer.class);
        assertThat(count).as("V1 seeds 3 project rows").isEqualTo(3);

        var stackCount = jdbc.queryForObject(
            "select count(*) from " + SCHEMA + ".project_stack", Integer.class);
        assertThat(stackCount).as("V1 seeds 9 project_stack rows").isEqualTo(9);
    }

    @Test
    @Order(2)
    void step2_archiveViaPreHook() {
        archiveService.archive();

        var archived = jdbc.queryForObject(
            "select count(*) from " + SCHEMA + ".projects_archived", Integer.class);
        assertThat(archived).as("archive copies 3 project rows").isEqualTo(3);

        var archivedStack = jdbc.queryForObject(
            "select count(*) from " + SCHEMA + ".project_stack_archived", Integer.class);
        assertThat(archivedStack).as("archive copies 9 project_stack rows").isEqualTo(9);

        var row = jdbc.queryForMap(
            "select title, description, year from " + SCHEMA + ".projects_archived where id = 1");
        assertThat(row.get("title")).as("Archived Mori \u9605\u8bfb\u5668").isEqualTo("Mori \u9605\u8bfb\u5668");

        var sourceStillExists = jdbc.queryForList(
            "select table_name from information_schema.tables"
                + " where table_schema = ? and table_name = 'projects'",
            String.class, SCHEMA);
        assertThat(sourceStillExists).as("projects table still present after archive").isNotEmpty();
    }

    @Test
    @Order(3)
    void step3_idempotentArchive() {
        archiveService.archive();

        var archived = jdbc.queryForObject(
            "select count(*) from " + SCHEMA + ".projects_archived", Integer.class);
        assertThat(archived).as("idempotent archive keeps 3 project rows").isEqualTo(3);

        var archivedStack = jdbc.queryForObject(
            "select count(*) from " + SCHEMA + ".project_stack_archived", Integer.class);
        assertThat(archivedStack).as("idempotent archive keeps 9 project_stack rows").isEqualTo(9);
    }

    @Test
    @Order(4)
    void step4_v8DropsProjects() {
        migrateTo("8");

        var tables = jdbc.queryForList(
            "select table_name from information_schema.tables"
                + " where table_schema = ? and table_name in ('projects', 'project_stack')",
            String.class, SCHEMA);
        assertThat(tables).as("V8 dropped both tables").isEmpty();

        var archived = jdbc.queryForObject(
            "select count(*) from " + SCHEMA + ".projects_archived", Integer.class);
        assertThat(archived).as("projects_archived survives V8 with data").isEqualTo(3);

        var archivedStack = jdbc.queryForObject(
            "select count(*) from " + SCHEMA + ".project_stack_archived", Integer.class);
        assertThat(archivedStack).as("project_stack_archived survives V8 with data").isEqualTo(9);
    }

    @Test
    @Order(5)
    void step5_v8plusNoop() {
        migrateTo("9");

        archiveService.archive();

        var archived = jdbc.queryForObject(
            "select count(*) from " + SCHEMA + ".projects_archived", Integer.class);
        assertThat(archived).as("no-op archive preserves 3 project rows").isEqualTo(3);
    }

    @Test
    @Order(6)
    void step6_metadataFailureIsFailClosed() {
        var badUrl = TestDatabase.URL.replaceAll("/[^/]+$", "/nonexistent_test_db");
        var badConfig = new HikariConfig();
        badConfig.setJdbcUrl(badUrl);
        badConfig.setUsername(TestDatabase.USERNAME);
        badConfig.setPassword(TestDatabase.PASSWORD);
        badConfig.setMaximumPoolSize(1);
        badConfig.setConnectionTimeout(2000);
        badConfig.setInitializationFailTimeout(-1);
        var badDs = new HikariDataSource(badConfig);
        try {
            var badService = new ProjectArchiveService(badDs);
            assertThatThrownBy(badService::archive)
                .as("metadata/query failure must propagate, never be silently swallowed")
                .isInstanceOf(DataAccessException.class);
        } finally {
            badDs.close();
        }
    }
}
