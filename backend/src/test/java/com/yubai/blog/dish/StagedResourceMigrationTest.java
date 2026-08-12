package com.yubai.blog.dish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yubai.blog.TestDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class StagedResourceMigrationTest {
    private static final String SCHEMA = "staged_resource_upgrade_test";
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
        jdbc.execute("drop schema if exists " + SCHEMA + " cascade");
        jdbc.execute("create schema " + SCHEMA);
    }

    @AfterAll
    static void cleanup() {
        try {
            jdbc.execute("drop schema if exists " + SCHEMA + " cascade");
        } finally {
            dataSource.close();
        }
    }

    @Test
    void v55AuditsLegacyDualStorageAndRejectsNewDualStorage() {
        migrate("54");
        var legacyId = UUID.randomUUID();
        jdbc.update(
                "insert into "
                        + SCHEMA
                        + ".dish_assets(public_id, storage_key, content, file_name, media_type, "
                        + "byte_size, sha256, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, now(), now())",
                legacyId,
                "legacy/key",
                new byte[] {1},
                "legacy.png",
                "image/png",
                1,
                "abc");

        migrate(null);

        assertThat(
                        jdbc.queryForObject(
                                "select audit_status from "
                                        + SCHEMA
                                        + ".resource_storage_audit where resource_id = ?",
                                String.class,
                                legacyId.toString()))
                .isEqualTo("DUAL_STORAGE");
        assertThat(
                        jdbc.queryForObject(
                                "select owner from " + SCHEMA + ".dish_assets where public_id = ?",
                                String.class,
                                legacyId))
                .isEqualTo("legacy-admin");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "insert into "
                                                + SCHEMA
                                                + ".dish_assets(public_id, storage_key, content, file_name, media_type, "
                                                + "byte_size, sha256, owner, created_at, updated_at) "
                                                + "values (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                                        UUID.randomUUID(),
                                        "new/dual",
                                        new byte[] {1},
                                        "new.png",
                                        "image/png",
                                        1,
                                        "abc",
                                        "alice"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static void migrate(String target) {
        var configuration =
                Flyway.configure()
                        .dataSource(dataSource)
                        .schemas(SCHEMA)
                        .locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }
}
