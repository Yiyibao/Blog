package com.yubai.blog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.yubai.blog.dish.DishEntity;
import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.dish.DishRequest;
import com.yubai.blog.note.NoteEntity;
import com.yubai.blog.note.NoteRepository;
import com.yubai.blog.note.NoteRequest;
import com.yubai.blog.note.NoteStatus;
import com.yubai.blog.post.PostContentSanitizer;
import com.yubai.blog.post.PostEntity;
import com.yubai.blog.post.PostRepository;
import com.yubai.blog.post.PostRequest;
import com.yubai.blog.post.PostStatus;

import jakarta.persistence.EntityManager;

/**
 * P1-1/L-12：列表查询次数与投影回归防线。
 *
 * <p>文章/笔记列表自 L-12 起走 Service 层「投影行 + 一次标签 IN 批查询」，
 * prepare 次数必须 ≤2 且不触正文列；菜谱列表仍为实体路径，@BatchSize 批量抓取
 * 后 prepare 次数必须 ≤3（1 条主查询 + ingredients/steps 各 1 条 IN 批量查询；
 * page size 大于总行数时无 count 查询）。
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ListQueryBatchingTest {
    private static final int SEED = 9;

    @Autowired
    PostRepository postRepository;

    @Autowired
    NoteRepository noteRepository;

    @Autowired
    DishRepository dishRepository;

    @Autowired
    EntityManager entityManager;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        var env = loadEnv();
        var baseUrl = env.getProperty("DB_URL", "jdbc:postgresql://localhost:5432/yubai_blog");
        var testUrl = baseUrl.replaceAll("/[^/]+$", "/yubai_blog_it");
        registry.add("spring.datasource.url", () -> testUrl);
        registry.add("spring.datasource.username", () -> env.getProperty("DB_USERNAME", "yubai_app"));
        registry.add("spring.datasource.password", () -> env.getProperty("DB_PASSWORD", ""));
    }

    @Test
    void postListLoadsTagsInBatches() {
        var sanitizer = new PostContentSanitizer();
        for (int i = 1; i <= SEED; i++) {
            postRepository.save(PostEntity.create(new PostRequest(
                "qc-batch-post-" + i, "批量测试文章 " + i, "摘要 " + i, LocalDate.of(2026, 1, i),
                5, "批量测试", List.of("标签A", "标签B", "标签C"), "#112233", "QC-" + i,
                false, PostStatus.PUBLISHED, "<p>正文 " + i + "</p>"
            ), sanitizer));
        }
        var service = new com.yubai.blog.post.PostService(postRepository, sanitizer);
        long prepares = measure(() ->
            service.findPublished(0, 50, null, null).items()
                .forEach(post -> assertThat(post.tags()).isNotEmpty()));
        assertThat(prepares)
            .as("L-12 后文章列表 JDBC prepare 次数（投影主查询 + 标签 IN 批查询）")
            .isLessThanOrEqualTo(2);
    }

    @Test
    void noteListLoadsTagsInBatches() {
        for (int i = 1; i <= SEED; i++) {
            noteRepository.save(NoteEntity.create(new NoteRequest(
                "批量测试笔记 " + i, "# 内容 " + i, "批量测试", NoteStatus.DRAFT,
                List.of("标签A", "标签B"), 0L
            )));
        }
        var service = new com.yubai.blog.note.NoteService(noteRepository);
        long prepares = measure(() ->
            service.findAll(NoteStatus.DRAFT, 0, 50).items()
                .forEach(note -> assertThat(note.tags()).isNotEmpty()));
        assertThat(prepares)
            .as("L-12 后笔记列表 JDBC prepare 次数（投影主查询 + 标签 IN 批查询）")
            .isLessThanOrEqualTo(2);
    }

    @Test
    void dishListLoadsIngredientsAndStepsInBatches() {
        for (int i = 1; i <= SEED; i++) {
            dishRepository.save(DishEntity.create(new DishRequest(
                "qc-batch-dish-" + i, "批量测试菜 " + i, "简介 " + i, "批量测试",
                "/images/dishes/qc-" + i + ".webp", "图 " + i, "测试", "https://example.com/qc",
                15, "简单", new BigDecimal("4.5"), false, true, 100 + i,
                List.of("食材A", "食材B"), List.of("步骤一", "步骤二")
            )));
        }
        long prepares = measure(() ->
            dishRepository.findAllByPublishedTrueOrderByFeaturedDescDisplayOrderAsc(PageRequest.of(0, 50))
                .forEach(dish -> {
                    assertThat(dish.getIngredients()).isNotEmpty();
                    assertThat(dish.getSteps()).isNotEmpty();
                }));
        assertThat(prepares)
            .as("菜谱列表页 JDBC prepare 次数（主查询 + ingredients/steps 各一条批量查询）")
            .isLessThanOrEqualTo(3);
    }

    /** 落库并清空一级缓存后执行查询，返回期间的 JDBC prepare 计数。 */
    private long measure(Runnable query) {
        entityManager.flush();
        entityManager.clear();
        var statistics = statistics();
        statistics.clear();
        query.run();
        long prepares = statistics.getPrepareStatementCount();
        System.out.println("[P1-1] JDBC prepares = " + prepares);
        return prepares;
    }

    private Statistics statistics() {
        return entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    /** 与 BlogApiIntegrationTest 相同的 .env.properties / 环境变量回退（P2-4 Testcontainers 时统一收敛）。 */
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
