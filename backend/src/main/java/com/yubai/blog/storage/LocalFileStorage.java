package com.yubai.blog.storage;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalFileStorage implements StorageService {
    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);
    private final Path rootDir;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    public LocalFileStorage(@Value("${app.attachment.storage.dir}") String dir) {
        var resolved = Path.of(dir).normalize().toAbsolutePath();
        try {
            Files.createDirectories(resolved);
            this.rootDir = resolved.toRealPath();
        } catch (IOException e) {
            throw new StorageException("Cannot create storage directory: " + resolved, e);
        }
        log.info("LocalFileStorage root: {}", rootDir);
    }

    @Override
    public String store(String storageKey, byte[] data) {
        var path = resolvePath(storageKey);
        Path tmp = null;
        try {
            if (data == null) throw new StorageException("Storage data must not be null");
            var parent = createSafeParent(path);
            rejectSymbolicLink(path, storageKey);
            tmp = Files.createTempFile(parent, ".upload-", ".tmp");
            Files.write(tmp, data, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            rwLock.writeLock().lock();
            try {
                try {
                    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                } catch (AccessDeniedException e) {
                    moveWithRetry(tmp, path);
                }
            } finally {
                rwLock.writeLock().unlock();
            }
            tmp = null;
        } catch (IOException e) {
            throw new StorageException("Failed to store: " + storageKey, e);
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
        return storageKey;
    }

    @Override
    public byte[] read(String storageKey) {
        var path = resolvePath(storageKey);
        rwLock.readLock().lock();
        try {
            verifyExistingPath(path, storageKey);
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new StorageException("Failed to read: " + storageKey, e);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void delete(String storageKey) {
        var path = resolvePath(storageKey);
        rwLock.writeLock().lock();
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
            verifyExistingPath(path, storageKey);
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new StorageException("Failed to delete: " + storageKey, e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    Path resolvePath(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new StorageException("Storage key must not be blank");
        }
        var normalized = storageKey.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new StorageException("Storage key must not start with '/': " + storageKey);
        }
        for (var segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                throw new StorageException("Storage key must not contain '..': " + storageKey);
            }
        }
        var path = rootDir.resolve(normalized).normalize().toAbsolutePath();
        if (!path.startsWith(rootDir)) {
            throw new StorageException("Storage key escapes root: " + storageKey);
        }
        return path;
    }

    private Path createSafeParent(Path path) throws IOException {
        var current = rootDir;
        for (var segment : rootDir.relativize(path.getParent())) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new StorageException("Storage path contains an unsafe directory: " + current);
                }
            } else {
                try {
                    Files.createDirectory(current);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                        throw new StorageException("Storage path contains an unsafe directory: " + current);
                    }
                }
            }
        }
        return current;
    }

    private void verifyExistingPath(Path path, String storageKey) throws IOException {
        var current = rootDir;
        for (var segment : rootDir.relativize(path)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new StorageException("Storage key escapes root via symlink: " + storageKey);
            }
        }
        var real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(rootDir)) {
            throw new StorageException("Storage key escapes root: " + storageKey);
        }
    }

    private static void rejectSymbolicLink(Path path, String storageKey) {
        if (Files.isSymbolicLink(path)) {
            throw new StorageException("Storage key targets a symlink: " + storageKey);
        }
    }

    private static void moveWithRetry(Path tmp, Path path) throws IOException {
        var maxAttempts = 3;
        var delayMs = 50;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException e) {
                if (i == maxAttempts - 1) throw e;
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                delayMs *= 2;
            }
        }
    }

    Path getRootDir() {
        return rootDir;
    }
}
