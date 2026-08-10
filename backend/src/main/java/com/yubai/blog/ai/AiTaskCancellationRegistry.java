package com.yubai.blog.ai;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class AiTaskCancellationRegistry {
    private final ConcurrentHashMap<UUID, Thread> workers = new ConcurrentHashMap<>();

    public void register(UUID taskId) {
        workers.put(taskId, Thread.currentThread());
    }

    public void unregister(UUID taskId) {
        workers.remove(taskId, Thread.currentThread());
    }

    public void cancel(UUID taskId) {
        var worker = workers.get(taskId);
        if (worker != null) worker.interrupt();
    }
}
