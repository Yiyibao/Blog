package com.yubai.blog.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 6B：LocalFileStorage 单元测试——路径安全、原子写入、上传下载删除。 */
class LocalFileStorageTest {

    @TempDir
    Path tempDir;

    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorage(tempDir.toString());
    }

    @Test
    void storesAndReadsBytes() {
        var key = "abc-123/test.png";
        var data = "hello storage".getBytes(StandardCharsets.UTF_8);
        storage.store(key, data);
        assertThat(storage.read(key)).isEqualTo(data);
    }

    @Test
    void deletesStoredFile() {
        var key = "del-test/file.bin";
        storage.store(key, new byte[]{1, 2, 3});
        storage.delete(key);
        assertThatThrownBy(() -> storage.read(key))
            .isInstanceOf(StorageException.class);
    }

    @Test
    void deleteIdempotentDoesNotThrow() {
        storage.delete("nonexistent/key");
    }

    @Test
    void atomicWriteReplacesExisting() {
        var key = "overwrite/foo.txt";
        storage.store(key, "first".getBytes(StandardCharsets.UTF_8));
        storage.store(key, "second".getBytes(StandardCharsets.UTF_8));
        assertThat(storage.read(key)).isEqualTo("second".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsPathTraversalWithDotDot() {
        assertThatThrownBy(() -> storage.store("../etc/passwd", new byte[0]))
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("..");
    }

    @Test
    void rejectsPathTraversalWithNestedDotDot() {
        assertThatThrownBy(() -> storage.store("a/../../../etc/passwd", new byte[0]))
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("..");
    }

    @Test
    void permitsDotsInsideAFileName() {
        storage.store("safe/foo..bar.txt", new byte[]{1});
        assertThat(storage.read("safe/foo..bar.txt")).containsExactly(1);
    }

    @Test
    void rejectsAbsoluteKey() {
        assertThatThrownBy(() -> storage.store("/etc/passwd", new byte[0]))
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("'/'");
    }

    @Test
    void rejectsKeyThatEscapesRoot() throws IOException {
        var realDir = tempDir.getParent().resolve("real-outside");
        var symlinkOutside = tempDir.resolve("link");
        try {
            Files.createDirectories(realDir);
            Files.createSymbolicLink(symlinkOutside, realDir);
        } catch (IOException | UnsupportedOperationException e) {
            return;
        }
        assertThatThrownBy(() -> storage.store("link/secret.txt", new byte[0]))
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("unsafe directory");
    }

    @Test
    void rejectsNullKey() {
        assertThatThrownBy(() -> storage.resolvePath(null))
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("blank");
    }

    @Test
    void rejectsBlankKey() {
        assertThatThrownBy(() -> storage.resolvePath("  "))
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("blank");
    }

    @Test
    void createsParentDirectoriesAutomatically() {
        var key = "deep/nested/dir/file.txt";
        storage.store(key, new byte[]{0});
        assertThat(storage.read(key)).containsExactly(0);
    }

    @Test
    void resolvedPathStaysWithinRoot() {
        var path = storage.resolvePath("some/valid/key.png");
        assertThat(path.toAbsolutePath().toString()).startsWith(tempDir.toAbsolutePath().toString());
    }

    @Test
    void windowsBackslashNormalized() {
        var key = "dir\\sub\\file.txt";
        var path = storage.resolvePath(key);
        assertThat(path.toAbsolutePath().toString()).startsWith(tempDir.toAbsolutePath().toString());
        assertThat(path.toString()).contains("dir", "sub", "file.txt");
    }

    @Test
    void concurrentWritesUseIndependentTemporaryFiles() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 24)
                .mapToObj(value -> executor.submit(() -> storage.store("same/file.bin", new byte[]{(byte) value})))
                .toList();
            executor.shutdown();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            for (var future : futures) future.get();
        }
        assertThat(storage.read("same/file.bin")).hasSize(1);
        try (var files = Files.walk(tempDir)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".tmp"))).isEmpty();
        }
    }

    @Test
    void concurrentSameKeyWritesProduceValidContent() throws Exception {
        var key = "concurrent/content.bin";
        var threadCount = 16;
        var marker = new AtomicInteger(0);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, threadCount)
                .mapToObj(i -> executor.submit(() -> {
                    var id = marker.incrementAndGet();
                    var data = ("payload-" + id + "-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8);
                    storage.store(key, data);
                    return id;
                }))
                .toList();
            executor.shutdown();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            for (var future : futures) future.get();
        }
        var finalContent = storage.read(key);
        assertThat(finalContent).isNotEmpty();
        assertThat(new String(finalContent, StandardCharsets.UTF_8)).startsWith("payload-");
        try (var files = Files.walk(tempDir)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".tmp"))).isEmpty();
        }
    }

    @Test
    void concurrentReadWriteDoesNotCorruptContent() throws Exception {
        var key = "rw-test/file.bin";
        storage.store(key, "initial".getBytes(StandardCharsets.UTF_8));
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = IntStream.range(0, 20)
                .mapToObj(i -> executor.submit(() -> {
                    if (i % 2 == 0) {
                        storage.store(key, ("write-" + i).getBytes(StandardCharsets.UTF_8));
                    } else {
                        var data = storage.read(key);
                        assertThat(data).isNotEmpty();
                    }
                    return null;
                }))
                .toList();
            executor.shutdown();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            for (var future : futures) future.get();
        }
        assertThat(storage.read(key)).isNotEmpty();
        try (var files = Files.walk(tempDir)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".tmp"))).isEmpty();
        }
    }

    @Test
    void failedMoveCleansTemporaryFile() throws IOException {
        var occupiedDir = tempDir.resolve("occupied");
        Files.createDirectory(occupiedDir);
        Files.createFile(occupiedDir.resolve("lock"));
        assertThatThrownBy(() -> storage.store("occupied", new byte[]{1}))
            .isInstanceOf(StorageException.class);
        try (var files = Files.list(tempDir)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".tmp"))).isEmpty();
        }
    }
}
