package com.yubai.blog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
import com.yubai.blog.post.PostRevisionService;
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
    com.yubai.blog.kitchen.MealLogRepository mealLogRepository;

    @Autowired
    com.yubai.blog.kitchen.DailyMenuRepository dailyMenuRepository;

    @Autowired
    com.yubai.blog.kitchen.DailyMenuItemRepository dailyMenuItemRepository;

    @Autowired
    EntityManager entityManager;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // P2-4：数据库解析统一收敛到 TestDatabase（可达直连快速模式 / Testcontainers 自起容器）
        TestDatabase.register(registry);
    }

    @Test
    void postListLoadsTagsInBatches() {
        var sanitizer = new PostContentSanitizer();
        for (int i = 1; i <= SEED; i++) {
            var slug = "qc-batch-post-" + i;
            postRepository.save(PostEntity.create(new PostRequest(
                slug, "批量测试文章 " + i, "摘要 " + i, LocalDate.of(2026, 1, i),
                5, "批量测试", List.of("标签A", "标签B", "标签C"), "#112233", "QC-" + i,
                false, PostStatus.PUBLISHED, "<p>正文 " + i + "</p>", null, null
            ), slug, sanitizer));
        }
        var service = new com.yubai.blog.post.PostService(postRepository, sanitizer, null, null);
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
        var service = new com.yubai.blog.note.NoteService(noteRepository, null, null);
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
                15, "简单", new BigDecimal("4.5"), false, true, 100 + i, 2,
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

    @Test
    void mealLogTimelineDoesNotFetchDishCollections() {
        // FD-16：打卡时间线 = 实体页查询 + 一次 slug 标量批查询，绝不触发 DishEntity 的
        // EAGER @ElementCollection（那会按菜品数放大 prepare）。上限 ≤2：主查询 + slug IN 批查询。
        var dish = dishRepository.save(DishEntity.create(new DishRequest(
            "qc-meallog-dish", "打卡防线菜", "简介", "批量测试",
            "/images/dishes/qc-ml.webp", "图", "测试", "https://example.com/qc",
            15, "简单", new BigDecimal("4.5"), false, true, 200, 2,
            List.of("食材A", "食材B"), List.of("步骤一", "步骤二")
        )));
        for (int i = 1; i <= SEED; i++) {
            mealLogRepository.save(com.yubai.blog.kitchen.MealLogEntity.create(
                LocalDate.of(2026, 2, i), dish.getId(), "打卡防线菜",
                com.yubai.blog.kitchen.MealSlot.DINNER, null, "", 1L, "站长"));
        }
        var service = new com.yubai.blog.kitchen.MealLogService(
            mealLogRepository, dailyMenuRepository, dailyMenuItemRepository, dishRepository);
        // 同一 IT 库会残留集成测试提交的打卡行（@DataJpaTest 只回滚自己的写入），
        // 断言只校验本测试种下的行；prepare 计数对整页成立
        long prepares = measure(() ->
            service.timeline(0, 50, null, null, null).items().stream()
                .filter(log -> dish.getId().equals(log.dishId()))
                .forEach(log -> assertThat(log.dishSlug()).isEqualTo("qc-meallog-dish")));
        assertThat(service.timeline(0, 50, null, null, null).items())
            .as("本测试种下的打卡应出现在时间线里")
            .anyMatch(log -> "qc-meallog-dish".equals(log.dishSlug()));
        assertThat(prepares)
            .as("FD-16 打卡时间线 JDBC prepare 次数（实体页查询 + slug 标量批查询）")
            .isLessThanOrEqualTo(2);
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

}
