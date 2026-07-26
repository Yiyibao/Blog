package com.yubai.blog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Properties;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * P2-4：集成/数据层测试共用的数据库解析（BlogApiIntegrationTest 与 ListQueryBatchingTest 统一收敛）。
 * 快速模式：本机/CI 已有可达的专用 IT 库（.env.properties 或环境变量指路）则直连；
 * 否则自动起 Testcontainers PostgreSQL（需 Docker），CI 无须手工配置 service 容器。
 * 容器为 JVM 级单例，由 Testcontainers 的 Ryuk 在退出后回收。
 */
public final class TestDatabase {

    public static final String NAME = "yubai_blog_it";
    public static final String URL;
    public static final String USERNAME;
    public static final String PASSWORD;

    static {
        var env = loadEnv();
        var envUrl = env.getProperty("DB_URL", "jdbc:postgresql://localhost:5432/yubai_blog");
        var envUser = env.getProperty("DB_USERNAME", "yubai_app");
        var envPass = env.getProperty("DB_PASSWORD", "");
        var candidate = envUrl.replaceAll("/[^/]+$", "/" + NAME);
        if (reachable(candidate, envUser, envPass)) {
            URL = candidate;
            USERNAME = envUser;
            PASSWORD = envPass;
        } else {
            var container = new org.testcontainers.containers.PostgreSQLContainer<>("postgres:17")
                .withDatabaseName(NAME).withUsername("yubai_app").withPassword("it-container-pass");
            container.start();
            URL = container.getJdbcUrl();
            USERNAME = container.getUsername();
            PASSWORD = container.getPassword();
        }
    }

    private TestDatabase() {
    }

    /** 三个数据源属性注册进测试上下文。 */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
    }

    /** 清空 public schema（随后的 Flyway 迁移会重建全部结构与种子）。 */
    public static void resetSchema() {
        try (var connection = DriverManager.getConnection(URL, USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("drop schema if exists public cascade");
            statement.execute("create schema public");
            statement.execute("grant all on schema public to " + USERNAME);
            statement.execute("grant all on schema public to public");
        } catch (Exception exception) {
            throw new IllegalStateException(
                "Integration database is unavailable (direct '" + NAME + "' and Testcontainers both failed).",
                exception
            );
        }
    }

    private static boolean reachable(String url, String user, String pass) {
        DriverManager.setLoginTimeout(3);
        try (var ignored = DriverManager.getConnection(url, user, pass)) {
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static Properties loadEnv() {
        var properties = new Properties();
        var candidates = new Path[]{Path.of(".env.properties"), Path.of("backend/.env.properties")};
        for (var candidate : candidates) {
            if (!Files.isRegularFile(candidate)) continue;
            try (var reader = Files.newBufferedReader(candidate)) {
                properties.load(reader);
                break;
            } catch (Exception ignored) {
                // fall through to defaults
            }
        }
        if (properties.isEmpty()) {
            var env = System.getenv();
            for (var key : new String[]{"DB_URL", "DB_USERNAME", "DB_PASSWORD"}) {
                var val = env.get(key);
                if (val != null) properties.setProperty(key, val);
            }
        }
        return properties;
    }
}
