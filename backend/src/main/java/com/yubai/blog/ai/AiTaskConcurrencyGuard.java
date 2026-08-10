package com.yubai.blog.ai;

import com.yubai.blog.common.TooManyRequestsException;
import com.yubai.blog.config.AiPlatformProperties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class AiTaskConcurrencyGuard {
    private final AiPlatformProperties properties;
    private final Semaphore queueSeats;
    private final Semaphore globalRunning;
    private final ConcurrentHashMap<String, Semaphore> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> providers = new ConcurrentHashMap<>();

    public AiTaskConcurrencyGuard(AiPlatformProperties properties) {
        this.properties = properties;
        queueSeats =
                new Semaphore(
                        positive(properties.getMaxConcurrentTasks())
                                + positive(properties.getMaxQueuedTasks()),
                        true);
        globalRunning = new Semaphore(positive(properties.getMaxConcurrentTasks()), true);
    }

    public Lease acquire(String owner, Long providerId) {
        if (!queueSeats.tryAcquire()) {
            throw new TooManyRequestsException("AI task queue is full", 5);
        }
        var user =
                users.computeIfAbsent(
                        owner,
                        ignored ->
                                new Semaphore(
                                        positive(properties.getMaxConcurrentTasksPerUser()), true));
        var providerKey = providerId == null ? "default" : providerId.toString();
        var provider =
                providers.computeIfAbsent(
                        providerKey,
                        ignored ->
                                new Semaphore(
                                        positive(properties.getMaxConcurrentTasksPerProvider()),
                                        true));
        boolean globalAcquired = false;
        boolean userAcquired = false;
        boolean providerAcquired = false;
        try {
            var wait = positive(properties.getQueueWaitSeconds());
            globalAcquired = globalRunning.tryAcquire(wait, TimeUnit.SECONDS);
            userAcquired = globalAcquired && user.tryAcquire(wait, TimeUnit.SECONDS);
            providerAcquired = userAcquired && provider.tryAcquire(wait, TimeUnit.SECONDS);
            if (!providerAcquired) {
                throw new TooManyRequestsException("AI task concurrency limit reached", 5);
            }
            return new Lease(queueSeats, globalRunning, user, provider);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TooManyRequestsException("AI task wait was interrupted", 1);
        } catch (RuntimeException exception) {
            if (providerAcquired) provider.release();
            if (userAcquired) user.release();
            if (globalAcquired) globalRunning.release();
            queueSeats.release();
            throw exception;
        }
    }

    private static int positive(int value) {
        return Math.max(1, value);
    }

    public static final class Lease implements AutoCloseable {
        private final Semaphore queue;
        private final Semaphore global;
        private final Semaphore user;
        private final Semaphore provider;
        private boolean closed;

        Lease(Semaphore queue, Semaphore global, Semaphore user, Semaphore provider) {
            this.queue = queue;
            this.global = global;
            this.user = user;
            this.provider = provider;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            provider.release();
            user.release();
            global.release();
            queue.release();
        }
    }
}
