package com.yubai.blog.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 4A-2：SSE 流式任务执行器。虚拟线程适合阻塞式上游读取，单管理员场景无需限制并发池。
 */
@Configuration
public class AiStreamExecutorConfig {
    @Bean(name = "aiStreamExecutor", destroyMethod = "shutdown")
    public ExecutorService aiStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** SSE 心跳调度器：纳入 Spring 生命周期（上下文关闭即回收），2 线程避免单连接拖慢全局。 */
    @Bean(name = "aiSseHeartbeatScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService aiSseHeartbeatScheduler() {
        return Executors.newScheduledThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "ai-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }
}
