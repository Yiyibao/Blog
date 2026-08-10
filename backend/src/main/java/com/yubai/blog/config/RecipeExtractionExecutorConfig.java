package com.yubai.blog.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class RecipeExtractionExecutorConfig {
    @Bean(name = "taskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("scheduled-maintenance-");
        return scheduler;
    }

    @Bean(name = "recipeExtractionExecutor", destroyMethod = "shutdown")
    ExecutorService recipeExtractionExecutor() {
        return new ThreadPoolExecutor(
                2,
                2,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(20),
                Thread.ofPlatform().name("recipe-extraction-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "recipeExtractionTimeoutScheduler", destroyMethod = "shutdown")
    ScheduledExecutorService recipeExtractionTimeoutScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("recipe-extraction-timeout-", 0).factory());
    }
}
