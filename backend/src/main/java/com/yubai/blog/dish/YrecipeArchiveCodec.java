package com.yubai.blog.dish;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.note.InvalidNoteFileException;
import com.yubai.blog.note.NoteAttachmentService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
public class YrecipeArchiveCodec {
    static final int MAX_ENTRIES = 2;
    static final long MAX_UNCOMPRESSED_TOTAL = 40L * 1024 * 1024;
    static final long MAX_ENTRY_SIZE = 10L * 1024 * 1024;
    static final int MAX_COMPRESSION_RATIO = 100;
    static final int MAX_PIXEL_DIMENSION = 8000;
    static final long MAX_TOTAL_PIXELS = 20_000_000L;
    static final Set<String> ALLOWED_COVER_PATHS =
            Set.of(
                    "assets/cover.jpg",
                    "assets/cover.jpeg",
                    "assets/cover.png",
                    "assets/cover.webp");
    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    record ExtractResult(
            YrecipePackage pkg, byte[] coverData, String coverMediaType, int width, int height) {}

    ExtractResult validateAndExtract(byte[] zipData) {
        try (var zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            Map<String, byte[]> entries = new HashMap<>();
            ZipEntry entry;
            long totalUncompressed = 0;
            int entryCount = 0;
            Set<String> entryNames = new HashSet<>();

            // Note: JDK ZipInputStream does not expose encryption, symlink, or
            // other extra-field flags from ZipEntry. Encrypted entries, symlink
            // entries, or entries with other platform-specific attributes are
            // not detected by this stream-based validator. These limitations
            // are inherent to the ZipInputStream API.
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new InvalidRecipeException("压缩包内文件数量不能超过 " + MAX_ENTRIES);
                }

                validateEntryName(entry);

                if (!entryNames.add(entry.getName())) {
                    throw new InvalidRecipeException("压缩包内存在重复文件名: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    throw new InvalidRecipeException("压缩包不应包含目录: " + entry.getName());
                }

                if (entry.getMethod() == ZipEntry.DEFLATED && entry.getCompressedSize() == 0) {
                    throw new InvalidRecipeException("压缩条目异常: " + entry.getName());
                }

                if (entry.getSize() > MAX_ENTRY_SIZE) {
                    throw new InvalidRecipeException("单文件大小不能超过 10 MB: " + entry.getName());
                }

                var uncompressed = readEntryBytes(zis, entry);
                if (uncompressed.length > MAX_ENTRY_SIZE) {
                    throw new InvalidRecipeException("单文件解压后大小不能超过 10 MB: " + entry.getName());
                }

                if (entry.getCompressedSize() > 0) {
                    var ratio = (double) uncompressed.length / entry.getCompressedSize();
                    if (ratio > MAX_COMPRESSION_RATIO) {
                        throw new InvalidRecipeException("压缩比异常，可能为 ZIP 炸弹: " + entry.getName());
                    }
                }

                totalUncompressed += uncompressed.length;
                if (totalUncompressed > MAX_UNCOMPRESSED_TOTAL) {
                    throw new InvalidRecipeException("解压后总大小不能超过 40 MB");
                }

                entries.put(entry.getName(), uncompressed);
                zis.closeEntry();
            }

            var recipeJsonData = entries.remove("recipe.json");
            if (recipeJsonData == null) {
                throw new InvalidRecipeException("缺少 recipe.json");
            }

            YrecipePackage pkg;
            try {
                pkg = MAPPER.readValue(recipeJsonData, YrecipePackage.class);
            } catch (IOException e) {
                throw new InvalidRecipeException("recipe.json 格式不合法: " + e.getMessage());
            }

            validatePackage(pkg);

            var coverPath = pkg.cover().path();

            var coverData = entries.remove(coverPath);
            if (coverData == null) {
                throw new InvalidRecipeException("缺少封面图片: " + coverPath);
            }

            if (!entries.isEmpty()) {
                throw new InvalidRecipeException(
                        "压缩包包含多余文件: " + String.join(", ", entries.keySet()));
            }

            var ext = coverPath.substring(coverPath.lastIndexOf('.'));
            var mediaType =
                    switch (ext) {
                        case ".jpg", ".jpeg" -> "image/jpeg";
                        case ".png" -> "image/png";
                        case ".webp" -> "image/webp";
                        default -> throw new InvalidRecipeException("不支持的图片格式: " + ext);
                    };

            if (!NoteAttachmentService.matchesMagicBytes(coverData, mediaType)) {
                throw new InvalidRecipeException("封面图片内容与扩展名不匹配");
            }

            int width, height;
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(coverData))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new InvalidRecipeException("无法识别封面图片");
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                    if (width > MAX_PIXEL_DIMENSION || height > MAX_PIXEL_DIMENSION) {
                        throw new InvalidRecipeException(
                                "封面图片尺寸不能超过 " + MAX_PIXEL_DIMENSION + "×" + MAX_PIXEL_DIMENSION);
                    }
                    if ((long) width * height > MAX_TOTAL_PIXELS) {
                        throw new InvalidRecipeException("封面图片总像素不能超过 " + MAX_TOTAL_PIXELS);
                    }
                } finally {
                    reader.dispose();
                }
            } catch (InvalidNoteFileException | InvalidRecipeException e) {
                throw e;
            } catch (IOException e) {
                throw new InvalidRecipeException("无法读取封面图片");
            }

            return new ExtractResult(pkg, coverData, mediaType, width, height);
        } catch (InvalidRecipeException e) {
            throw e;
        } catch (IOException e) {
            throw new InvalidRecipeException("无法读取压缩包");
        }
    }

    private void validateEntryName(ZipEntry entry) {
        var name = entry.getName();
        if (name.isEmpty()) {
            throw new InvalidRecipeException("文件名为空");
        }
        if (name.charAt(0) == '\0') {
            throw new InvalidRecipeException("文件名以空字符开头: " + name);
        }
        if (name.contains("\\")) {
            throw new InvalidRecipeException("文件名包含反斜杠: " + name);
        }
        if (name.startsWith("/")) {
            throw new InvalidRecipeException("文件名包含绝对路径: " + name);
        }
        // segment-aware path traversal check (split by / and check each segment)
        for (var segment : name.split("/")) {
            if ("..".equals(segment)) {
                throw new InvalidRecipeException("文件名包含路径遍历: " + name);
            }
        }
        if (name.indexOf('\0') >= 0) {
            throw new InvalidRecipeException("文件名包含非法字符");
        }
        // Note: JDK ZipInputStream does not expose encryption or symlink flags
        // on ZipEntry, so detection of encrypted/symlink entries is not
        // performed at the stream level.
    }

    private void validatePackage(YrecipePackage pkg) {
        if (!"1.0".equals(pkg.schemaVersion())) {
            throw new InvalidRecipeException("不支持的 schemaVersion: " + pkg.schemaVersion());
        }
        if (!"yubai.recipe".equals(pkg.kind())) {
            throw new InvalidRecipeException("不支持的 kind: " + pkg.kind());
        }
        try {
            UUID.fromString(pkg.packageId());
        } catch (IllegalArgumentException e) {
            throw new InvalidRecipeException("packageId 不是有效的 UUID: " + pkg.packageId());
        }
        if (pkg.recipe() == null) {
            throw new InvalidRecipeException("菜谱内容不能为空");
        }
        var recipe = pkg.recipe();
        if (recipe.name() == null || recipe.name().isBlank()) {
            throw new InvalidRecipeException("菜谱名称不能为空");
        }
        requireClean(recipe.name(), "菜谱名称");
        if (recipe.name().length() > 120) {
            throw new InvalidRecipeException("菜谱名称不能超过 120 个字符");
        }
        if (recipe.slug() != null
                && !recipe.slug().isBlank()
                && !recipe.slug().matches("^[a-z0-9]+(?:-[a-z0-9]+)*$")) {
            throw new InvalidRecipeException("Slug 格式不合法: " + recipe.slug());
        }
        if (recipe.summary() == null || recipe.summary().isBlank()) {
            throw new InvalidRecipeException("菜谱简介不能为空");
        }
        requireClean(recipe.summary(), "菜谱简介");
        if (recipe.summary().length() > 1000) {
            throw new InvalidRecipeException("菜谱简介不能超过 1000 个字符");
        }
        if (recipe.ingredients() == null || recipe.ingredients().isEmpty()) {
            throw new InvalidRecipeException("食材清单不能为空");
        }
        if (recipe.ingredients().size() > 30) {
            throw new InvalidRecipeException("食材数量不能超过 30 项");
        }
        for (var ing : recipe.ingredients()) {
            if (ing == null || ing.isBlank() || ing.length() > 240) {
                throw new InvalidRecipeException("食材项不合法");
            }
            requireClean(ing, "食材项");
        }
        if (recipe.steps() == null || recipe.steps().isEmpty()) {
            throw new InvalidRecipeException("制作步骤不能为空");
        }
        if (recipe.steps().size() > 30) {
            throw new InvalidRecipeException("步骤数量不能超过 30 项");
        }
        for (var step : recipe.steps()) {
            if (step == null || step.isBlank() || step.length() > 2000) {
                throw new InvalidRecipeException("步骤内容不合法");
            }
            requireClean(step, "步骤内容");
        }
        if (recipe.prepMinutes() < 1 || recipe.prepMinutes() > 1440) {
            throw new InvalidRecipeException("准备时间必须在 1-1440 分钟之间");
        }
        if (recipe.categoryHint() != null) {
            requireClean(recipe.categoryHint(), "分类提示");
            if (recipe.categoryHint().length() > 60) {
                throw new InvalidRecipeException("分类提示不能超过 60 个字符");
            }
        }
        if (recipe.difficulty() == null || recipe.difficulty().isBlank()) {
            throw new InvalidRecipeException("难度不能为空");
        }
        requireClean(recipe.difficulty(), "难度");
        if (!Set.of("简单", "家常", "进阶").contains(recipe.difficulty())) {
            throw new InvalidRecipeException("难度值不合法: " + recipe.difficulty());
        }
        if (recipe.baseServings() < 1) {
            throw new InvalidRecipeException("份数必须大于等于 1");
        }
        if (pkg.cover() == null) {
            throw new InvalidRecipeException("封面信息不能为空");
        }
        if (pkg.cover().path() == null || pkg.cover().path().isBlank()) {
            throw new InvalidRecipeException("封面路径不能为空");
        }
        requireClean(pkg.cover().path(), "封面路径");
        if (!ALLOWED_COVER_PATHS.contains(pkg.cover().path())) {
            throw new InvalidRecipeException("封面路径不合法，仅支持: " + ALLOWED_COVER_PATHS);
        }
        if (pkg.cover().alt() == null || pkg.cover().alt().isBlank()) {
            throw new InvalidRecipeException("封面替代文本不能为空");
        }
        requireClean(pkg.cover().alt(), "封面替代文本");
        if (pkg.cover().alt().length() > 240) {
            throw new InvalidRecipeException("封面替代文本不能超过 240 个字符");
        }
        if (pkg.source() != null) {
            if (pkg.source().type() != null) {
                requireClean(pkg.source().type(), "来源类型");
                if (!Set.of("manual", "website", "book", "ai").contains(pkg.source().type())) {
                    throw new InvalidRecipeException("来源类型不合法: " + pkg.source().type());
                }
            }
            if (pkg.source().url() != null) {
                requireClean(pkg.source().url(), "来源 URL");
                if (!isValidHttpsUrl(pkg.source().url())) {
                    throw new InvalidRecipeException("来源 URL 必须是 HTTPS 链接");
                }
            }
            if (pkg.source().title() != null) {
                requireClean(pkg.source().title(), "来源标题");
                if (pkg.source().title().length() > 500) {
                    throw new InvalidRecipeException("来源标题不能超过 500 个字符");
                }
            }
            if (pkg.source().creator() != null) {
                requireClean(pkg.source().creator(), "来源创建者");
                if (pkg.source().creator().length() > 200) {
                    throw new InvalidRecipeException("来源创建者不能超过 200 个字符");
                }
            }
            if (pkg.source().capturedAtSeconds() != null && pkg.source().capturedAtSeconds() < 0) {
                throw new InvalidRecipeException("采集时间不能为负");
            }
        }
        if (pkg.generation() != null) {
            if (pkg.generation().generator() != null) {
                requireClean(pkg.generation().generator(), "生成器名称");
                if (pkg.generation().generator().length() > 100) {
                    throw new InvalidRecipeException("生成器名称不能超过 100 个字符");
                }
            }
            if (pkg.generation().provider() != null) {
                requireClean(pkg.generation().provider(), "生成器提供商");
                if (pkg.generation().provider().length() > 100) {
                    throw new InvalidRecipeException("生成器提供商不能超过 100 个字符");
                }
            }
            if (pkg.generation().model() != null) {
                requireClean(pkg.generation().model(), "模型名称");
                if (pkg.generation().model().length() > 100) {
                    throw new InvalidRecipeException("模型名称不能超过 100 个字符");
                }
            }
            if (pkg.generation().createdAt() != null) {
                try {
                    Instant.parse(pkg.generation().createdAt());
                } catch (Exception e) {
                    throw new InvalidRecipeException("创建时间格式不合法: " + pkg.generation().createdAt());
                }
            }
            if (pkg.generation().confidence() != null
                    && (pkg.generation().confidence() < 0 || pkg.generation().confidence() > 1)) {
                throw new InvalidRecipeException("置信度必须在 0-1 之间");
            }
            if (pkg.generation().warnings() != null && pkg.generation().warnings().size() > 10) {
                throw new InvalidRecipeException("警告数量不能超过 10 条");
            }
        }
    }

    byte[] buildExportZip(YrecipePackage pkg, byte[] coverData, String ext) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos, java.nio.charset.StandardCharsets.UTF_8)) {
            var entry = new ZipEntry("assets/cover" + ext);
            entry.setSize(coverData.length);
            zos.putNextEntry(entry);
            zos.write(coverData);
            zos.closeEntry();

            var jsonBytes = MAPPER.writeValueAsBytes(pkg);
            var jsonEntry = new ZipEntry("recipe.json");
            jsonEntry.setSize(jsonBytes.length);
            zos.putNextEntry(jsonEntry);
            zos.write(jsonBytes);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private static byte[] readEntryBytes(ZipInputStream zis, ZipEntry entry) throws IOException {
        var baos = new ByteArrayOutputStream();
        var buf = new byte[8192];
        int len;
        long total = 0;
        while ((len = zis.read(buf)) > 0) {
            total += len;
            if (total > MAX_ENTRY_SIZE) {
                throw new InvalidRecipeException("单文件解压后大小不能超过 10 MB: " + entry.getName());
            }
            baos.write(buf, 0, len);
        }
        return baos.toByteArray();
    }

    static String extensionForMediaType(String mediaType) {
        if (mediaType == null) return ".jpg";
        return switch (mediaType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    static String extFromMediaType(String mediaType) {
        return extensionForMediaType(mediaType);
    }

    static boolean isValidHttpsUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            var parsed = new java.net.URI(url);
            return "https".equals(parsed.getScheme()) && parsed.isAbsolute();
        } catch (Exception e) {
            return false;
        }
    }

    private static void requireClean(String value, String fieldName) {
        if (value != null && (value.contains("\0") || !value.equals(value.trim()))) {
            throw new InvalidRecipeException(fieldName + " 包含非法字符或前后空白");
        }
    }
}
