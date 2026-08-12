package com.yubai.blog.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yubai.blog.TestDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FreshInstallBaselineTest {
    private static final String FRESH_SCHEMA = "fresh_empty_baseline_test";
    private static final String UPGRADE_SCHEMA = "fresh_upgrade_preservation_test";
    private static final String UNSAFE_SCHEMA = "fresh_unversioned_test";
    private static final String PREFLIGHT_SCHEMA = "flyway_release_preflight_test";
    private static HikariDataSource rootDataSource;
    private static JdbcTemplate rootJdbc;

    @BeforeAll
    static void setup() {
        var config = new HikariConfig();
        config.setJdbcUrl(TestDatabase.URL);
        config.setUsername(TestDatabase.USERNAME);
        config.setPassword(TestDatabase.PASSWORD);
        config.setMaximumPoolSize(2);
        rootDataSource = new HikariDataSource(config);
        rootJdbc = new JdbcTemplate(rootDataSource);
        for (var schema : List.of(FRESH_SCHEMA, UPGRADE_SCHEMA, UNSAFE_SCHEMA, PREFLIGHT_SCHEMA)) {
            recreate(schema);
        }
    }

    @AfterAll
    static void cleanup() {
        try {
            for (var schema :
                    List.of(FRESH_SCHEMA, UPGRADE_SCHEMA, UNSAFE_SCHEMA, PREFLIGHT_SCHEMA)) {
                rootJdbc.execute("drop schema if exists " + schema + " cascade");
            }
        } finally {
            rootDataSource.close();
        }
    }

    @Test
    void freshSchemaEndsAtLatestVersionWithoutHistoricalDemoRows() {
        try (var schemaDataSource = schemaDataSource(FRESH_SCHEMA)) {
            var baseline = new FreshInstallBaselineService(schemaDataSource);
            assertThat(baseline.isFreshEmptySchema()).isTrue();

            migrate(schemaDataSource, null);
            baseline.removeHistoricalDemoRows();

            var jdbc = new JdbcTemplate(schemaDataSource);
            assertThat(count(jdbc, "posts")).isZero();
            assertThat(count(jdbc, "dishes")).isZero();
            assertThat(count(jdbc, "music_tracks")).isZero();
            assertThat(count(jdbc, "sys_quote")).isZero();
            assertThat(latestVersion(jdbc)).isEqualTo("55");
        }
    }

    @Test
    void upgradingVersionedSchemaPreservesExistingContent() {
        try (var schemaDataSource = schemaDataSource(UPGRADE_SCHEMA)) {
            migrate(schemaDataSource, "39");
            var jdbc = new JdbcTemplate(schemaDataSource);
            var postsBefore = count(jdbc, "posts");
            var dishesBefore = count(jdbc, "dishes");
            var baseline = new FreshInstallBaselineService(schemaDataSource);

            assertThat(baseline.isFreshEmptySchema()).isFalse();
            migrate(schemaDataSource, null);

            assertThat(count(jdbc, "posts")).isEqualTo(postsBefore);
            assertThat(count(jdbc, "dishes")).isEqualTo(dishesBefore);
            assertThat(latestVersion(jdbc)).isEqualTo("55");
        }
    }

    @Test
    void nonEmptyUnversionedSchemaFailsClosed() {
        try (var schemaDataSource = schemaDataSource(UNSAFE_SCHEMA)) {
            new JdbcTemplate(schemaDataSource).execute("create table unmanaged_data(id bigint)");
            var baseline = new FreshInstallBaselineService(schemaDataSource);

            assertThatThrownBy(baseline::isFreshEmptySchema)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("without Flyway history");
        }
    }

    @Test
    void releasePreflightValidatesAndReportsPendingWithoutMigrating() {
        try (var schemaDataSource = schemaDataSource(PREFLIGHT_SCHEMA)) {
            migrate(schemaDataSource, "54");
            var flyway =
                    Flyway.configure()
                            .dataSource(schemaDataSource)
                            .locations("classpath:db/migration")
                            .ignoreMigrationPatterns("*:pending")
                            .load();

            var report = FlywayReleasePreflight.inspect(flyway, 55);

            assertThat(report.currentVersion()).isEqualTo(54);
            assertThat(report.targetVersion()).isEqualTo(55);
            assertThat(report.pendingMigrations()).isEqualTo(1);
            assertThat(latestVersion(new JdbcTemplate(schemaDataSource))).isEqualTo("54");
        }
    }

    private static HikariDataSource schemaDataSource(String schema) {
        var config = new HikariConfig();
        config.setJdbcUrl(TestDatabase.URL);
        config.setUsername(TestDatabase.USERNAME);
        config.setPassword(TestDatabase.PASSWORD);
        config.setMaximumPoolSize(2);
        config.setConnectionInitSql("set search_path to " + schema + ", public");
        return new HikariDataSource(config);
    }

    private static void migrate(HikariDataSource dataSource, String target) {
        var configuration =
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }

    private static long count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private static String latestVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "select version from flyway_schema_history where success "
                        + "order by installed_rank desc limit 1",
                String.class);
    }

    private static void recreate(String schema) {
        rootJdbc.execute("drop schema if exists " + schema + " cascade");
        rootJdbc.execute("create schema " + schema);
    }
}
