package com.yubai.blog.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
}
