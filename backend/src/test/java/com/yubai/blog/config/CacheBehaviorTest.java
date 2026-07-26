package com.yubai.blog.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.graph.GraphService;
import com.yubai.blog.note.NoteRepository;
import com.yubai.blog.post.PostContentSanitizer;
import com.yubai.blog.post.PostRepository;
import com.yubai.blog.post.PostRequest;
import com.yubai.blog.post.PostService;
import com.yubai.blog.post.PostStatus;
import com.yubai.blog.series.SeriesService;

/**
 * P1-5：缓存行为回归——只读热点二次调用命中缓存（仓库只查一次）；
 * admin 写操作触发 @CacheEvict 后再次调用必须重新落库查询。
 * （Mockito 对返回 List 的未打桩方法默认给空列表，构图空结果即可验证调用次数。）
 */
@SpringJUnitConfig(classes = {CacheConfig.class, GraphService.class, PostService.class})
class CacheBehaviorTest {

    @MockitoBean
    PostRepository postRepository;

    @MockitoBean
    NoteRepository noteRepository;

    @MockitoBean
    DishRepository dishRepository;

    @MockitoBean
    PostContentSanitizer sanitizer;

    // 4B：GraphService 新增依赖——未打桩默认空 Map，构图不出 SERIES 节点
    @MockitoBean
    SeriesService seriesService;

    @Autowired
    GraphService graphService;

    @Autowired
    PostService postService;

    @Autowired
    CacheManager cacheManager;

    /** 缓存是上下文级共享状态（mock 每测重置而缓存不会），逐测清空保证隔离。 */
    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });
    }

    @Test
    void graphIsCachedAcrossCalls() {
        graphService.buildGraph(true);
        graphService.buildGraph(true);
        graphService.buildGraph(true);

        verify(postRepository, times(1)).findPublishedGraphRows();
        verify(noteRepository, times(1)).findPublishedGraphRows();
        verify(dishRepository, times(1)).findAllPublishedForGraph();
    }

    @Test
    void adminWriteEvictsGraphCache() {
        graphService.buildGraph(true);
        graphService.buildGraph(true);
        verify(postRepository, times(1)).findPublishedGraphRows();

        when(postRepository.existsBySlug("evict-check")).thenReturn(false);
        when(sanitizer.sanitize(any())).thenReturn("<p>x</p>");
        when(postRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        postService.create(new PostRequest("evict-check", "标题", "摘要", LocalDate.of(2026, 1, 1),
            5, "工程实践", List.of(), "#000000", "01", false, PostStatus.DRAFT, "<p>x</p>", null, null));

        graphService.buildGraph(true);
        verify(postRepository, times(2)).findPublishedGraphRows();
    }
}
