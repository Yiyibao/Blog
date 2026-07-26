package com.yubai.blog.config;

import java.time.Duration;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * P1-5：只读热点端点的进程内缓存。
 *
 * <p>缓存对象与失效策略：
 * <ul>
 *   <li>{@link #GRAPH} 知识图谱、{@link #SITEMAP} 站点地图 XML——admin 对文章/笔记/菜品的任何
 *       写操作会主动 {@code @CacheEvict}（内容集合变化直接影响两者）；TTL 兜底。</li>
 *   <li>{@link #QUOTES} 语录、{@link #MUSIC} 曲目——当前无管理写入口（4F 上线时须补 evict），仅靠 TTL。</li>
 * </ul>
 * 单实例进程内缓存与既有限流器同一假设；多实例部署时需换分布式方案（计划 6C 一并评估）。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String GRAPH = "graph";
    public static final String SITEMAP = "sitemap";
    public static final String QUOTES = "quotes";
    public static final String MUSIC = "music";

    @Bean
    public CacheManager cacheManager() {
        var manager = new CaffeineCacheManager(GRAPH, SITEMAP, QUOTES, MUSIC);
        manager.setCaffeine(Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(50));
        return manager;
    }
}
