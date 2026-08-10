package com.yubai.blog.admin.recipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.config.RecipeExtractionProperties;
import com.yubai.blog.dish.InvalidRecipeException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class VideoRecipeSourceExtractor {
    private static final long MAX_METADATA_BYTES = 5L * 1024 * 1024;
    private static final long MAX_COVER_BYTES = 10L * 1024 * 1024;
    private static final Pattern VTT_TIMESTAMP =
            Pattern.compile(
                    "^\\s*(?:\\d{2}:)?\\d{2}:\\d{2}[.,]\\d{3}\\s+-->\\s+(?:\\d{2}:)?\\d{2}:\\d{2}[.,]\\d{3}.*$");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    private final RecipeExtractionProperties properties;
    private final RecipeUrlValidator urlValidator;
    private final ObjectMapper mapper;

    public VideoRecipeSourceExtractor(
            RecipeExtractionProperties properties,
            RecipeUrlValidator urlValidator,
            ObjectMapper mapper) {
        this.properties = properties;
        this.urlValidator = urlValidator;
        this.mapper = mapper;
    }

    public VideoSource extract(String rawUrl) {
        if (!properties.videoEnabled()) {
            throw new InvalidRecipeException("视频链接提取尚未启用");
        }
        var uri = urlValidator.validatePublicHttps(rawUrl);
        if (!urlValidator.hostMatches(uri, properties.videoHosts())) {
            throw new InvalidRecipeException("暂不支持该视频平台");
        }

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("recipe-video-");
            runYtDlp(uri.toString(), workDir);
            var infoFile = findSingle(workDir, ".info.json");
            if (infoFile == null || Files.size(infoFile) > MAX_METADATA_BYTES) {
                throw new InvalidRecipeException("未获取到有效的视频元数据");
            }
            var metadata = mapper.readTree(Files.readAllBytes(infoFile));
            var transcript = readTranscript(workDir, properties.maxTranscriptChars());
            var cover = readCover(workDir);
            return fromMetadata(metadata, transcript, cover, uri.toString());
        } catch (InvalidRecipeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new InvalidRecipeException("视频信息读取失败: " + exception.getMessage());
        } finally {
            deleteTree(workDir);
        }
    }

    private void runYtDlp(String url, Path workDir) {
        var outputTemplate = workDir.resolve("source.%(ext)s").toString();
        var command =
                List.of(
                        properties.ytDlpPath(),
                        "--no-config",
                        "--no-playlist",
                        "--playlist-end",
                        "1",
                        "--skip-download",
                        "--socket-timeout",
                        "10",
                        "--retries",
                        "1",
                        "--fragment-retries",
                        "1",
                        "--extractor-retries",
                        "1",
                        "--file-access-retries",
                        "1",
                        "--max-filesize",
                        "10M",
                        "--write-info-json",
                        "--write-subs",
                        "--write-auto-subs",
                        "--sub-langs",
                        "zh.*,zh-Hans.*,zh-Hant.*,en.*",
                        "--sub-format",
                        "vtt/best",
                        "--write-thumbnail",
                        "--restrict-filenames",
                        "--quiet",
                        "--no-warnings",
                        "--output",
                        outputTemplate,
                        url);
        final Process process;
        try {
            process =
                    new ProcessBuilder(command)
                            .directory(workDir.toFile())
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start();
        } catch (IOException exception) {
            throw new InvalidRecipeException("服务器未安装或无法运行 yt-dlp");
        }

        try {
            var timeout = positiveTimeout(properties.videoTimeout());
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new InvalidRecipeException("视频信息提取超时");
            }
            if (process.exitValue() != 0) {
                throw new InvalidRecipeException("视频平台未返回可用信息，可能需要登录或该链接不受支持");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new InvalidRecipeException("视频信息提取被中断");
        }
    }

    VideoSource fromMetadata(
            JsonNode metadata, String transcript, CoverData cover, String sourceUrl) {
        var title = text(metadata, "title");
        var creator =
                firstNonBlank(
                        text(metadata, "uploader"),
                        text(metadata, "channel"),
                        text(metadata, "creator"));
        var description = text(metadata, "description");
        var duration = metadata.path("duration").asLong(0);
        var tags = new ArrayList<String>();
        if (metadata.path("tags").isArray()) {
            metadata.path("tags")
                    .forEach(
                            node -> {
                                if (node.isTextual()
                                        && !node.asText().isBlank()
                                        && tags.size() < 30) {
                                    tags.add(node.asText().trim());
                                }
                            });
        }

        var content = new StringBuilder();
        appendLine(content, "视频标题", title);
        appendLine(content, "作者", creator);
        if (duration > 0) appendLine(content, "视频时长", duration + " 秒");
        appendLine(content, "视频简介", description);
        if (!tags.isEmpty()) appendLine(content, "标签", String.join("、", tags));
        if (transcript != null && !transcript.isBlank()) {
            content.append("\n字幕/口述内容：\n").append(transcript.trim());
        }
        var usefulCharacters =
                (description == null ? 0 : description.length())
                        + (transcript == null ? 0 : transcript.length());
        if (usefulCharacters < 60) {
            throw new InvalidRecipeException("视频没有足够的简介或字幕，无法可靠生成菜谱");
        }

        return new VideoSource(
                content.toString(),
                sourceUrl,
                title,
                creator,
                cover == null ? null : cover.bytes(),
                cover == null ? null : cover.mediaType());
    }

    static String cleanVtt(String raw, int maxChars) {
        if (raw == null || raw.isBlank()) return "";
        var unique = new LinkedHashSet<String>();
        for (var line : raw.replace("\r", "").split("\n")) {
            var trimmed = line.trim();
            if (trimmed.isEmpty()
                    || trimmed.equals("WEBVTT")
                    || trimmed.startsWith("Kind:")
                    || trimmed.startsWith("Language:")
                    || VTT_TIMESTAMP.matcher(trimmed).matches()
                    || trimmed.matches("^\\d+$")) {
                continue;
            }
            trimmed = HTML_TAG.matcher(trimmed).replaceAll("").replace("&nbsp;", " ").trim();
            if (!trimmed.isEmpty()) unique.add(trimmed);
        }
        var result = String.join("\n", unique);
        return result.length() > maxChars ? result.substring(0, maxChars) : result;
    }

    private String readTranscript(Path workDir, int maxChars) throws IOException {
        var builder = new StringBuilder();
        try (var paths = Files.list(workDir)) {
            for (var path :
                    paths.filter(
                                    p ->
                                            p.getFileName()
                                                    .toString()
                                                    .toLowerCase(Locale.ROOT)
                                                    .endsWith(".vtt"))
                            .sorted()
                            .toList()) {
                if (Files.size(path) > MAX_METADATA_BYTES) continue;
                if (!builder.isEmpty()) builder.append('\n');
                builder.append(Files.readString(path, StandardCharsets.UTF_8));
                if (builder.length() >= maxChars * 2L) break;
            }
        }
        return cleanVtt(builder.toString(), maxChars);
    }

    private CoverData readCover(Path workDir) throws IOException {
        try (var paths = Files.list(workDir)) {
            for (var path : paths.sorted().toList()) {
                var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                var mediaType =
                        name.endsWith(".jpg") || name.endsWith(".jpeg")
                                ? "image/jpeg"
                                : name.endsWith(".png")
                                        ? "image/png"
                                        : name.endsWith(".webp") ? "image/webp" : null;
                if (mediaType != null
                        && Files.size(path) > 0
                        && Files.size(path) <= MAX_COVER_BYTES) {
                    return new CoverData(Files.readAllBytes(path), mediaType);
                }
            }
        }
        return null;
    }

    private static Path findSingle(Path dir, String suffix) throws IOException {
        try (var paths = Files.list(dir)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(suffix))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static Duration positiveTimeout(Duration timeout) {
        return timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(45)
                : timeout;
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    // Best-effort cleanup of an isolated temporary directory.
                                }
                            });
        } catch (IOException ignored) {
            // Best-effort cleanup of an isolated temporary directory.
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private static String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static void appendLine(StringBuilder builder, String label, String value) {
        if (value != null && !value.isBlank())
            builder.append(label).append(": ").append(value).append('\n');
    }

    public record VideoSource(
            String text,
            String sourceUrl,
            String title,
            String creator,
            byte[] coverBytes,
            String coverMediaType) {}

    record CoverData(byte[] bytes, String mediaType) {}
}
