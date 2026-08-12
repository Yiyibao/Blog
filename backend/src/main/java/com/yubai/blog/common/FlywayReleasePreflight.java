package com.yubai.blog.common;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

/** Read-only Flyway validation entry point used before an atomic production release switch. */
public final class FlywayReleasePreflight {
    private FlywayReleasePreflight() {}

    public static void main(String[] args) {
        if (args.length != 1 || !args[0].matches("\\d+")) {
            throw new IllegalArgumentException("Expected one numeric schema target argument");
        }
        var target = Integer.parseInt(args[0]);
        var flyway =
                Flyway.configure()
                        .dataSource(
                                requiredEnv("DB_URL"),
                                requiredEnv("DB_USERNAME"),
                                requiredEnv("DB_PASSWORD"))
                        .ignoreMigrationPatterns("*:pending")
                        .load();
        var report = inspect(flyway, target);
        System.out.printf(
                "Flyway validate/info passed: current=V%d target=V%d pending=%d%n",
                report.currentVersion(), report.targetVersion(), report.pendingMigrations());
    }

    static Report inspect(Flyway flyway, int target) {
        flyway.validate();
        var info = flyway.info();
        var current = info.current();
        var currentVersion = current == null ? 0 : numeric(current.getVersion());
        var latestAvailable =
                Arrays.stream(info.all())
                        .map(migration -> migration.getVersion())
                        .filter(java.util.Objects::nonNull)
                        .mapToInt(FlywayReleasePreflight::numeric)
                        .max()
                        .orElse(0);
        if (latestAvailable != target) {
            throw new IllegalStateException(
                    "Release migrations end at V" + latestAvailable + ", expected V" + target);
        }
        if (currentVersion > target) {
            throw new IllegalStateException("Database is newer than this release");
        }
        return new Report(currentVersion, target, info.pending().length);
    }

    private static int numeric(MigrationVersion version) {
        var value = version.getVersion();
        if (!value.matches("\\d+")) {
            throw new IllegalStateException("Non-numeric Flyway version is unsupported: " + value);
        }
        return Integer.parseInt(value);
    }

    private static String requiredEnv(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for Flyway preflight");
        }
        return value;
    }

    record Report(int currentVersion, int targetVersion, int pendingMigrations) {}
}
