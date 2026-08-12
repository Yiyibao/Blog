package com.yubai.blog.dish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.dish.YrecipePackage.YrecipeGeneration;
import com.yubai.blog.dish.YrecipePackage.YrecipeSource;
import com.yubai.blog.storage.StorageService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DishImportServiceTest {

    @Mock DishImportStagingRepository stagingRepository;
    @Mock DishRepository dishRepository;
    @Mock DishCategoryService categoryService;
    @Mock DishAssetService assetService;
    @Mock DishService dishService;
    @Mock StorageService storageService;
    @InjectMocks DishImportService importService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- helper ----

    private static DishAssetEntity createAssetEntityWithId(
            long id,
            String storageKey,
            String fileName,
            String mediaType,
            long byteSize,
            String sha256,
            Integer width,
            Integer height) {
        var entity =
                DishAssetEntity.create(
                        storageKey, fileName, mediaType, byteSize, sha256, width, height);
        try {
            var field = DishAssetEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return entity;
    }

    private static byte[] buildTinyPng(int width, int height) {
        try {
            var image =
                    new java.awt.image.BufferedImage(
                            width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] buildZipFromMap(Map<String, byte[]> entries) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos, java.nio.charset.StandardCharsets.UTF_8)) {
            for (var e : entries.entrySet()) {
                var ze = new ZipEntry(e.getKey());
                var data = e.getValue();
                if (data != null) {
                    ze.setSize(data.length);
                    zos.putNextEntry(ze);
                    zos.write(data);
                } else {
                    zos.putNextEntry(ze);
                }
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private static YrecipePackage validPackage() {
        return new YrecipePackage(
                "1.0",
                "yubai.recipe",
                UUID.randomUUID().toString(),
                new YrecipePackage.YrecipeContent(
                        "Test Recipe",
                        null,
                        "Test summary",
                        null,
                        30,
                        "家常",
                        2,
                        List.of("ingredient 1"),
                        List.of("step 1")),
                new YrecipePackage.YrecipeCover("assets/cover.png", "alt text"),
                null,
                null);
    }

    private static YrecipePackage validPackageWithSlug(String slug) {
        return new YrecipePackage(
                "1.0",
                "yubai.recipe",
                UUID.randomUUID().toString(),
                new YrecipePackage.YrecipeContent(
                        "Test Recipe",
                        slug,
                        "Test summary",
                        null,
                        30,
                        "家常",
                        2,
                        List.of("ingredient 1"),
                        List.of("step 1")),
                new YrecipePackage.YrecipeCover("assets/cover.png", "alt text"),
                null,
                null);
    }

    private static YrecipePackage validPackageWithCategoryHint(String hint) {
        return new YrecipePackage(
                "1.0",
                "yubai.recipe",
                UUID.randomUUID().toString(),
                new YrecipePackage.YrecipeContent(
                        "Test Recipe",
                        null,
                        "Test summary",
                        hint,
                        30,
                        "家常",
                        2,
                        List.of("ingredient 1"),
                        List.of("step 1")),
                new YrecipePackage.YrecipeCover("assets/cover.png", "alt text"),
                null,
                null);
    }

    private static YrecipePackage validPackageWithSource(YrecipeSource source) {
        return new YrecipePackage(
                "1.0",
                "yubai.recipe",
                UUID.randomUUID().toString(),
                new YrecipePackage.YrecipeContent(
                        "Test Recipe",
                        null,
                        "Test summary",
                        null,
                        30,
                        "家常",
                        2,
                        List.of("ingredient 1"),
                        List.of("step 1")),
                new YrecipePackage.YrecipeCover("assets/cover.png", "alt text"),
                source,
                null);
    }

    private static YrecipePackage validPackageWithGeneration(YrecipeGeneration generation) {
        return new YrecipePackage(
                "1.0",
                "yubai.recipe",
                UUID.randomUUID().toString(),
                new YrecipePackage.YrecipeContent(
                        "Test Recipe",
                        null,
                        "Test summary",
                        null,
                        30,
                        "家常",
                        2,
                        List.of("ingredient 1"),
                        List.of("step 1")),
                new YrecipePackage.YrecipeCover("assets/cover.png", "alt text"),
                null,
                generation);
    }

    private static byte[] buildZipFromPackage(YrecipePackage pkg) throws IOException {
        return buildZipFromPackageWithCover(pkg, buildTinyPng(2, 2));
    }

    private static byte[] buildZipFromPackageWithCover(YrecipePackage pkg, byte[] coverData)
            throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("recipe.json", MAPPER.writeValueAsBytes(pkg));
        entries.put(pkg.cover().path(), coverData);
        return buildZipFromMap(entries);
    }

    private MultipartFile mockZip(byte[] zipBytes) throws IOException {
        var file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(zipBytes == null || zipBytes.length == 0);
        when(file.getSize()).thenReturn(zipBytes != null ? (long) zipBytes.length : 0L);
        when(file.getBytes()).thenReturn(zipBytes);
        return file;
    }

    private MultipartFile mockOverSizedFile() throws IOException {
        var file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(DishImportService.MAX_COMPRESSED_SIZE + 1);
        return file;
    }

    @Test
    void validPreviewChecksOwnerQuotaUnderDatabaseLock() throws IOException {
        when(stagingRepository.countByOwnerAndConsumedFalseAndCancelledFalseAndExpiresAtAfter(
                        eq("alice"), any()))
                .thenReturn(DishImportService.MAX_ACTIVE_IMPORT_COUNT_PER_OWNER);

        assertThatThrownBy(
                        () ->
                                importService.previewFromBytes(
                                        buildZipFromPackage(validPackage()), "alice"))
                .isInstanceOf(InvalidRecipeException.class)
                .hasMessageContaining("quota");

        verify(stagingRepository).lockOwnerQuota("alice");
        verify(stagingRepository, never()).save(any());
        verify(storageService, never()).store(anyString(), any());
    }

    @SuppressWarnings("unchecked")
    private static <T> T reflectionInvoke(
            String methodName, Class<?>[] paramTypes, Object target, Object... args)
            throws Exception {
        var method = DishImportService.class.getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        return (T) method.invoke(target, args);
    }

    // ===== isValidHttpsUrl =====

    @Test
    void isValidHttpsUrl_acceptsHttps() {
        assertThat(DishImportService.isValidHttpsUrl("https://example.com")).isTrue();
        assertThat(DishImportService.isValidHttpsUrl("https://example.com/path?q=1")).isTrue();
    }

    @Test
    void isValidHttpsUrl_rejectsHttp() {
        assertThat(DishImportService.isValidHttpsUrl("http://example.com")).isFalse();
    }

    @Test
    void isValidHttpsUrl_rejectsNull() {
        assertThat(DishImportService.isValidHttpsUrl(null)).isFalse();
    }

    @Test
    void isValidHttpsUrl_rejectsBlank() {
        assertThat(DishImportService.isValidHttpsUrl("")).isFalse();
        assertThat(DishImportService.isValidHttpsUrl("  ")).isFalse();
    }

    // ===== automatic dish slug =====

    @Test
    void dishSlug_keepsReadableAsciiFromMixedName() {
        assertThat(DishSlug.fromName("番茄炒鸡蛋 recipe")).isEqualTo("recipe");
    }

    @Test
    void dishSlug_generatesStableHashForOnlyChinese() {
        assertThat(DishSlug.fromName("番茄炒鸡蛋")).matches("dish-[0-9a-f]{12}");
    }

    @Test
    void dishSlug_handlesAsciiOnly() {
        assertThat(DishSlug.fromName("My Great Recipe!")).isEqualTo("my-great-recipe");
    }

    @Test
    void dishSlug_handlesMixedInput() {
        assertThat(DishSlug.fromName("  Hello  世界 World  ")).isEqualTo("hello-world");
    }

    // ===== preview – ZIP structure =====

    @Test
    void preview_validZipReturnsResponse() throws IOException {
        var zipBytes = buildZipFromPackage(validPackage());
        var file = mockZip(zipBytes);
        when(stagingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = importService.preview(file);

        assertThat(result).isNotNull();
        assertThat(result.token()).isNotNull();
        assertThat(result.recipe()).isNotNull();
        assertThat(result.warnings()).isNotNull();
        verify(storageService).store(anyString(), any());
        verify(stagingRepository).save(any());
    }

    @Test
    void preview_emptyFileThrows() throws IOException {
        var file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(true);
        when(file.getSize()).thenReturn(0L);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_oversizedFileThrows() throws IOException {
        var file = mockOverSizedFile();

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_getBytesIOExceptionThrows() throws IOException {
        var file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(100L);
        when(file.getBytes()).thenThrow(new IOException("read error"));

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_tooManyEntriesThrows() throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("recipe.json", MAPPER.writeValueAsBytes(validPackage()));
        entries.put("assets/cover.png", buildTinyPng(2, 2));
        entries.put("extra.txt", "extra".getBytes());
        var zipBytes = buildZipFromMap(entries);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_directoryEntryThrows() throws IOException {
        var pkg = validPackage();
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("recipe.json", MAPPER.writeValueAsBytes(pkg));
        entries.put("assets/", null);
        entries.put(pkg.cover().path(), buildTinyPng(2, 2));
        var zipBytes = buildZipFromMap(entries);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_backslashInEntryNameThrows() throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("recipe.json", MAPPER.writeValueAsBytes(validPackage()));
        entries.put("assets\\cover.png", buildTinyPng(2, 2));
        var zipBytes = buildZipFromMap(entries);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_absolutePathEntryThrows() throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("recipe.json", MAPPER.writeValueAsBytes(validPackage()));
        entries.put("/assets/cover.png", buildTinyPng(2, 2));
        var zipBytes = buildZipFromMap(entries);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_pathTraversalEntryThrows() throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("recipe.json", MAPPER.writeValueAsBytes(validPackage()));
        entries.put("assets/../cover.png", buildTinyPng(2, 2));
        var zipBytes = buildZipFromMap(entries);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_nulInEntryNameThrows() throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos, java.nio.charset.StandardCharsets.UTF_8)) {
            var ze = new ZipEntry("recipe.json");
            ze.setSize(1);
            zos.putNextEntry(ze);
            zos.write(42);
            zos.closeEntry();
            var ze2 = new ZipEntry("assets/cover.png\u0000");
            ze2.setSize(1);
            zos.putNextEntry(ze2);
            zos.write(42);
            zos.closeEntry();
        }
        var file = mockZip(baos.toByteArray());

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_extraUnexpectedFileThrows() throws IOException {
        var pkg = validPackage();
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("recipe.json", MAPPER.writeValueAsBytes(pkg));
        entries.put("assets/cover.png", buildTinyPng(2, 2));
        entries.put("unexpected.txt", "data".getBytes());
        var zipBytes = buildZipFromMap(entries);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_allowedOnlyRecipeJsonAndCover() throws IOException {
        var pkg = validPackage();
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);
        when(stagingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = importService.preview(file);

        assertThat(result).isNotNull();
        assertThat(result.token()).isNotNull();
    }

    @Test
    void preview_missingRecipeJsonThrows() throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("assets/cover.png", buildTinyPng(2, 2));
        var zipBytes = buildZipFromMap(entries);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_invalidRecipeJsonThrows() throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("recipe.json", "not json at all".getBytes());
        entries.put("assets/cover.png", buildTinyPng(2, 2));
        var zipBytes = buildZipFromMap(entries);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_missingCoverInZipThrows() throws IOException {
        var pkg = validPackage();
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("recipe.json", MAPPER.writeValueAsBytes(pkg));
        var zipBytes = buildZipFromMap(entries);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_unsupportedImageFormatThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "Test",
                                null,
                                "Summary",
                                null,
                                30,
                                "家常",
                                2,
                                List.of("a"),
                                List.of("b")),
                        new YrecipePackage.YrecipeCover("assets/cover.gif", null),
                        null,
                        null);
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("recipe.json", MAPPER.writeValueAsBytes(pkg));
        entries.put("assets/cover.gif", new byte[] {'G', 'I', 'F'});
        var zipBytes = buildZipFromMap(entries);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_mismatchedMagicBytesThrows() throws IOException {
        var pkg = validPackage();
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("recipe.json", MAPPER.writeValueAsBytes(pkg));
        entries.put(pkg.cover().path(), "this is not a png".getBytes());
        var zipBytes = buildZipFromMap(entries);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    // ===== preview – image dimension =====

    @Test
    void preview_coverDimensionTooLargeThrows() throws IOException {
        var pkg = validPackage();
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("recipe.json", MAPPER.writeValueAsBytes(pkg));
        entries.put(pkg.cover().path(), buildTinyPng(8001, 1));
        var zipBytes = buildZipFromMap(entries);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_coverTotalPixelsTooLargeThrows() throws IOException {
        var pkg = validPackage();
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("recipe.json", MAPPER.writeValueAsBytes(pkg));
        entries.put(pkg.cover().path(), buildTinyPng(5000, 5000));
        var zipBytes = buildZipFromMap(entries);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    // ===== preview – slug availability =====

    @Test
    void preview_returnsSlugAvailableTrue() throws IOException {
        var pkg = validPackageWithSlug("my-recipe");
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);
        when(dishRepository.existsBySlug("my-recipe")).thenReturn(false);
        when(stagingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = importService.preview(file);

        assertThat(result.slugAvailable()).isTrue();
    }

    @Test
    void preview_returnsSlugAvailableFalse() throws IOException {
        var pkg = validPackageWithSlug("taken-slug");
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);
        when(dishRepository.existsBySlug("taken-slug")).thenReturn(true);
        when(stagingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = importService.preview(file);

        assertThat(result.slugAvailable()).isFalse();
    }

    @Test
    void preview_nullSlugIsAvailable() throws IOException {
        var pkg = validPackage();
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);
        when(stagingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = importService.preview(file);

        assertThat(result.slugAvailable()).isTrue();
    }

    // ===== preview – category matching =====

    @Test
    void preview_categoryHintExactMatch() throws IOException {
        var pkg = validPackageWithCategoryHint("川菜");
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);
        when(categoryService.findAll())
                .thenReturn(
                        List.of(
                                new AdminDishCategory(1, "川菜", "chuan-cai", "", 5, 3),
                                new AdminDishCategory(2, "粤菜", "yue-cai", "", 3, 2)));
        when(stagingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = importService.preview(file);

        assertThat(result.categoryMatch()).isEqualTo("川菜");
    }

    @Test
    void preview_categoryHintFuzzyMatch() throws IOException {
        var pkg = validPackageWithCategoryHint("川");
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);
        when(categoryService.findAll())
                .thenReturn(List.of(new AdminDishCategory(1, "川菜", "chuan-cai", "", 5, 3)));
        when(stagingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = importService.preview(file);

        assertThat(result.categoryMatch()).isEqualTo("川菜");
    }

    @Test
    void preview_categoryHintNoMatch() throws IOException {
        var pkg = validPackageWithCategoryHint("西餐");
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);
        when(categoryService.findAll())
                .thenReturn(List.of(new AdminDishCategory(1, "川菜", "chuan-cai", "", 5, 3)));
        when(stagingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = importService.preview(file);

        assertThat(result.categoryMatch()).isNull();
    }

    @Test
    void preview_categoryHintNullNoMatchAttempt() throws IOException {
        var pkg = validPackage();
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);
        when(stagingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = importService.preview(file);

        assertThat(result.categoryMatch()).isNull();
    }

    // ===== preview – package validation: schema/kind/packageId =====

    @Test
    void preview_invalidSchemaVersionThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "2.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        validPackage().recipe(),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_invalidKindThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "other.kind",
                        UUID.randomUUID().toString(),
                        validPackage().recipe(),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_invalidPackageIdThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        "not-a-uuid",
                        validPackage().recipe(),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    // ===== preview – package validation: recipe name =====

    @Test
    void preview_nullNameThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                null,
                                null,
                                "summary",
                                null,
                                30,
                                "家常",
                                2,
                                List.of("a"),
                                List.of("b")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_blankNameThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "  ",
                                null,
                                "summary",
                                null,
                                30,
                                "家常",
                                2,
                                List.of("a"),
                                List.of("b")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_nameTooLongThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "x".repeat(121),
                                null,
                                "summary",
                                null,
                                30,
                                "家常",
                                2,
                                List.of("a"),
                                List.of("b")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_nameWithTrailingWhitespaceThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name ",
                                null,
                                "summary",
                                null,
                                30,
                                "家常",
                                2,
                                List.of("a"),
                                List.of("b")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    // ===== preview – package validation: summary =====

    @Test
    void preview_nullSummaryThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name", null, null, null, 30, "家常", 2, List.of("a"), List.of("b")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_blankSummaryThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name", null, "", null, 30, "家常", 2, List.of("a"), List.of("b")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_summaryTooLongThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name",
                                null,
                                "x".repeat(1001),
                                null,
                                30,
                                "家常",
                                2,
                                List.of("a"),
                                List.of("b")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    // ===== preview – package validation: ingredients =====

    @Test
    void preview_nullIngredientsThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name", null, "summary", null, 30, "家常", 2, null, List.of("a")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_emptyIngredientsThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name",
                                null,
                                "summary",
                                null,
                                30,
                                "家常",
                                2,
                                List.of(),
                                List.of("a")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_tooManyIngredientsThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name",
                                null,
                                "summary",
                                null,
                                30,
                                "家常",
                                2,
                                Collections.nCopies(31, "a"),
                                List.of("a")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_blankIngredientThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name",
                                null,
                                "summary",
                                null,
                                30,
                                "家常",
                                2,
                                List.of(""),
                                List.of("a")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_ingredientTooLongThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name",
                                null,
                                "summary",
                                null,
                                30,
                                "家常",
                                2,
                                List.of("x".repeat(241)),
                                List.of("a")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    // ===== preview – package validation: steps =====

    @Test
    void preview_nullStepsThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name", null, "summary", null, 30, "家常", 2, List.of("a"), null),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_emptyStepsThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name",
                                null,
                                "summary",
                                null,
                                30,
                                "家常",
                                2,
                                List.of("a"),
                                List.of()),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_tooManyStepsThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name",
                                null,
                                "summary",
                                null,
                                30,
                                "家常",
                                2,
                                List.of("a"),
                                Collections.nCopies(31, "step x")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_blankStepThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name",
                                null,
                                "summary",
                                null,
                                30,
                                "家常",
                                2,
                                List.of("a"),
                                List.of("")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_stepTooLongThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name",
                                null,
                                "summary",
                                null,
                                30,
                                "家常",
                                2,
                                List.of("a"),
                                List.of("x".repeat(2001))),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    // ===== preview – package validation: prepMinutes =====

    @Test
    void preview_prepMinutesTooLowThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name",
                                null,
                                "summary",
                                null,
                                0,
                                "家常",
                                2,
                                List.of("a"),
                                List.of("b")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_prepMinutesTooHighThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name",
                                null,
                                "summary",
                                null,
                                1441,
                                "家常",
                                2,
                                List.of("a"),
                                List.of("b")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    // ===== preview – package validation: difficulty =====

    @Test
    void preview_nullDifficultyThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name",
                                null,
                                "summary",
                                null,
                                30,
                                null,
                                2,
                                List.of("a"),
                                List.of("b")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_blankDifficultyThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name",
                                null,
                                "summary",
                                null,
                                30,
                                "",
                                2,
                                List.of("a"),
                                List.of("b")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_invalidDifficultyThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name",
                                null,
                                "summary",
                                null,
                                30,
                                "地狱级",
                                2,
                                List.of("a"),
                                List.of("b")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    // ===== preview – package validation: baseServings =====

    @Test
    void preview_baseServingsTooLowThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name",
                                null,
                                "summary",
                                null,
                                30,
                                "家常",
                                0,
                                List.of("a"),
                                List.of("b")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    // ===== preview – package validation: categoryHint =====

    @Test
    void preview_categoryHintTooLongThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                "name",
                                null,
                                "summary",
                                "x".repeat(61),
                                30,
                                "家常",
                                2,
                                List.of("a"),
                                List.of("b")),
                        validPackage().cover(),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    // ===== preview – package validation: cover =====

    @Test
    void preview_nullCoverThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        validPackage().recipe(),
                        null,
                        null,
                        null);
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("recipe.json", MAPPER.writeValueAsBytes(pkg));
        entries.put("assets/cover.png", buildTinyPng(2, 2));
        var zipBytes = buildZipFromMap(entries);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_blankCoverPathThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        validPackage().recipe(),
                        new YrecipePackage.YrecipeCover("", null),
                        null,
                        null);
        var zipBytes = buildZipFromPackageWithCover(pkg, buildTinyPng(2, 2));
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_invalidCoverPathThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        validPackage().recipe(),
                        new YrecipePackage.YrecipeCover("assets/cover.bmp", null),
                        null,
                        null);
        var zipBytes = buildZipFromPackageWithCover(pkg, buildTinyPng(2, 2));
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    // ===== preview – package validation: cover alt =====

    @Test
    void preview_coverAltTooLongThrows() throws IOException {
        var pkg =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        validPackage().recipe(),
                        new YrecipePackage.YrecipeCover("assets/cover.png", "x".repeat(241)),
                        null,
                        null);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    // ===== preview – package validation: source =====

    @Test
    void preview_invalidSourceTypeThrows() throws IOException {
        var source = new YrecipeSource("invalid", null, null, null, null);
        var pkg = validPackageWithSource(source);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_validSourceTypesAreAccepted() throws IOException {
        for (var type : List.of("manual", "website", "book", "ai")) {
            var source = new YrecipeSource(type, "https://example.com", "title", "creator", 100L);
            var pkg = validPackageWithSource(source);
            var zipBytes = buildZipFromPackage(pkg);
            var file = mockZip(zipBytes);
            when(stagingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            var result = importService.preview(file);
            assertThat(result).isNotNull();
        }
    }

    @Test
    void preview_sourceUrlNotHttpsThrows() throws IOException {
        var source = new YrecipeSource("website", "http://example.com", null, null, null);
        var pkg = validPackageWithSource(source);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_sourceTitleTooLongThrows() throws IOException {
        var source =
                new YrecipeSource("website", "https://example.com", "x".repeat(501), null, null);
        var pkg = validPackageWithSource(source);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_sourceCreatorTooLongThrows() throws IOException {
        var source =
                new YrecipeSource("website", "https://example.com", null, "x".repeat(201), null);
        var pkg = validPackageWithSource(source);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_negativeCapturedAtSecondsThrows() throws IOException {
        var source = new YrecipeSource("website", "https://example.com", null, null, -1L);
        var pkg = validPackageWithSource(source);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    // ===== preview – package validation: generation =====

    @Test
    void preview_generationInvalidCreatedAtThrows() throws IOException {
        var generation = new YrecipeGeneration("gen", "prov", "model", "not-a-date", null, null);
        var pkg = validPackageWithGeneration(generation);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_generationValidCreatedAtAccepted() throws IOException {
        var generation =
                new YrecipeGeneration("gen", "prov", "model", Instant.now().toString(), 0.5, null);
        var pkg = validPackageWithGeneration(generation);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);
        when(stagingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = importService.preview(file);
        assertThat(result).isNotNull();
    }

    @Test
    void preview_generationConfidenceTooLowThrows() throws IOException {
        var generation = new YrecipeGeneration(null, null, null, null, -0.1, null);
        var pkg = validPackageWithGeneration(generation);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_generationConfidenceTooHighThrows() throws IOException {
        var generation = new YrecipeGeneration(null, null, null, null, 1.1, null);
        var pkg = validPackageWithGeneration(generation);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_generationConfidenceNullAccepted() throws IOException {
        var generation = new YrecipeGeneration(null, null, null, null, null, null);
        var pkg = validPackageWithGeneration(generation);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);
        when(stagingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = importService.preview(file);
        assertThat(result).isNotNull();
    }

    @Test
    void preview_generationTooManyWarningsThrows() throws IOException {
        var generation =
                new YrecipeGeneration(
                        null, null, null, null, null, Collections.nCopies(11, "warn"));
        var pkg = validPackageWithGeneration(generation);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);

        assertThatThrownBy(() -> importService.preview(file))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void preview_generationWarningsInPreviewResponse() throws IOException {
        var generation =
                new YrecipeGeneration(null, null, null, null, null, List.of("warn1", "warn2"));
        var pkg = validPackageWithGeneration(generation);
        var zipBytes = buildZipFromPackage(pkg);
        var file = mockZip(zipBytes);
        when(stagingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = importService.preview(file);
        assertThat(result.warnings()).contains("warn1", "warn2");
    }

    // ===== commit =====

    @Test
    void commit_successfulFlow() throws IOException {
        var token = UUID.randomUUID();
        var pkg = validPackage();
        var staging =
                DishImportStagingEntity.create(
                        MAPPER.writeValueAsString(pkg),
                        "imports/key/cover.png",
                        "image/png",
                        Instant.now().plusSeconds(3600));
        var request = new DishImportCommitRequest("川菜");

        when(stagingRepository.findByToken(token)).thenReturn(Optional.of(staging));
        doNothing().when(categoryService).requireExisting("川菜");
        when(dishRepository.maxDisplayOrder()).thenReturn(5);
        var dishResponse =
                new DishResponse(
                        1L,
                        "test-recipe",
                        "Test Recipe",
                        "summary",
                        "川菜",
                        null,
                        "alt",
                        30,
                        "家常",
                        java.math.BigDecimal.ZERO,
                        false,
                        false,
                        6,
                        0,
                        0,
                        2,
                        List.of("a"),
                        List.of("b"),
                        Instant.now(),
                        Instant.now());
        when(dishService.create(any())).thenReturn(dishResponse);
        when(storageService.read(staging.getStorageKey())).thenReturn(buildTinyPng(2, 2));
        var assetEntity =
                createAssetEntityWithId(
                        1L,
                        "dish-assets/uuid/image.png",
                        "cover.png",
                        "image/png",
                        100,
                        "abc",
                        2,
                        2);
        when(assetService.createForDish(
                        eq(0L), anyString(), anyString(), eq("image/png"), any(), eq(2), eq(2)))
                .thenReturn(assetEntity);

        var result = importService.commit(token, request);

        assertThat(result.id()).isEqualTo(1L);
        verify(stagingRepository).save(staging);
    }

    @Test
    void commit_canPublishImportedDishForMenuSelection() throws IOException {
        var token = UUID.randomUUID();
        var staging =
                DishImportStagingEntity.create(
                        MAPPER.writeValueAsString(validPackage()),
                        null,
                        null,
                        Instant.now().plusSeconds(3600));
        when(stagingRepository.findByToken(token)).thenReturn(Optional.of(staging));
        when(dishRepository.maxDisplayOrder()).thenReturn(0);
        when(dishService.create(any())).thenReturn(mock(DishResponse.class));

        var captor = ArgumentCaptor.forClass(DishRequest.class);
        importService.commit(token, new DishImportCommitRequest("川菜", true));
        verify(dishService).create(captor.capture());

        assertThat(captor.getValue().published()).isTrue();
    }

    @Test
    void commit_expiredTokenThrows() {
        var token = UUID.randomUUID();
        var staging =
                DishImportStagingEntity.create("{}", null, null, Instant.now().minusSeconds(60));
        when(stagingRepository.findByToken(token)).thenReturn(Optional.of(staging));

        assertThatThrownBy(() -> importService.commit(token, new DishImportCommitRequest("川菜")))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void commit_alreadyConsumedThrows() {
        var token = UUID.randomUUID();
        var staging =
                DishImportStagingEntity.create("{}", null, null, Instant.now().plusSeconds(3600));
        staging.setConsumed(true);
        when(stagingRepository.findByToken(token)).thenReturn(Optional.of(staging));

        assertThatThrownBy(() -> importService.commit(token, new DishImportCommitRequest("川菜")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void commit_nonExistentTokenThrows() {
        var token = UUID.randomUUID();
        when(stagingRepository.findByToken(token)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> importService.commit(token, new DishImportCommitRequest("川菜")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void commit_leavesSlugForDishServiceGeneration() throws IOException {
        var token = UUID.randomUUID();
        var pkg = validPackage();
        var staging =
                DishImportStagingEntity.create(
                        MAPPER.writeValueAsString(pkg),
                        "imports/k/cover.png",
                        "image/png",
                        Instant.now().plusSeconds(3600));
        var request = new DishImportCommitRequest("川菜");

        when(stagingRepository.findByToken(token)).thenReturn(Optional.of(staging));
        doNothing().when(categoryService).requireExisting("川菜");
        when(dishRepository.maxDisplayOrder()).thenReturn(0);
        var dishResponse =
                new DishResponse(
                        1L,
                        "test-recipe",
                        "Test Recipe",
                        "summary",
                        "川菜",
                        null,
                        "alt",
                        30,
                        "家常",
                        java.math.BigDecimal.ZERO,
                        false,
                        false,
                        1,
                        0,
                        0,
                        2,
                        List.of("a"),
                        List.of("b"),
                        Instant.now(),
                        Instant.now());
        when(dishService.create(any())).thenReturn(dishResponse);
        when(storageService.read(staging.getStorageKey())).thenReturn(buildTinyPng(2, 2));
        var assetEntity =
                createAssetEntityWithId(
                        1L,
                        "dish-assets/uuid/image.png",
                        "cover.png",
                        "image/png",
                        100,
                        "abc",
                        2,
                        2);
        when(assetService.createForDish(
                        eq(0L), anyString(), anyString(), eq("image/png"), any(), eq(2), eq(2)))
                .thenReturn(assetEntity);

        var captor = ArgumentCaptor.forClass(DishRequest.class);
        importService.commit(token, request);
        verify(dishService).create(captor.capture());

        assertThat(captor.getValue().name()).isEqualTo("Test Recipe");
    }

    // ===== cancel =====

    @Test
    void cancel_nonExistentTokenDoesNothing() {
        var token = UUID.randomUUID();
        when(stagingRepository.findByToken(token)).thenReturn(Optional.empty());

        importService.cancel(token);

        verify(stagingRepository, never()).delete(any());
    }

    @Test
    void cancel_existingTokenMarksCancelled() {
        var token = UUID.randomUUID();
        var staging =
                DishImportStagingEntity.create(
                        "{}", "imports/k/cover.png", "image/png", Instant.now().plusSeconds(3600));
        when(stagingRepository.findByToken(token)).thenReturn(Optional.of(staging));

        importService.cancel(token);

        assertThat(staging.isCancelled()).isTrue();
        verify(stagingRepository).save(staging);
    }

    @Test
    void cancel_existingTokenWithoutStorageKeyStillMarksCancelled() {
        var token = UUID.randomUUID();
        var staging =
                DishImportStagingEntity.create("{}", null, null, Instant.now().plusSeconds(3600));
        when(stagingRepository.findByToken(token)).thenReturn(Optional.of(staging));

        importService.cancel(token);

        assertThat(staging.isCancelled()).isTrue();
        verify(stagingRepository).save(staging);
    }

    // ===== export =====

    @Test
    void export_dishWithExternalImageUrlThrows() {
        var dishResponse =
                new DishResponse(
                        1L,
                        "test",
                        "Test",
                        "summary",
                        "川菜",
                        "https://external.com/img.jpg",
                        null,
                        30,
                        "家常",
                        java.math.BigDecimal.ZERO,
                        false,
                        true,
                        1,
                        0,
                        0,
                        2,
                        List.of("a"),
                        List.of("b"),
                        Instant.now(),
                        Instant.now());
        when(dishService.findOne(1L)).thenReturn(dishResponse);
        var dishEntity =
                new DishEntity() {
                    @Override
                    public Long getId() {
                        return 1L;
                    }
                };
        when(dishRepository.findById(1L)).thenReturn(Optional.of(dishEntity));
        when(assetService.findByDishId(1L)).thenThrow(new NotFoundException("图片不存在"));

        assertThatThrownBy(() -> importService.export(1L))
                .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void export_dishWithNoAssetAndInternalPlaceholderThrows() {
        var dishResponse =
                new DishResponse(
                        1L,
                        "test",
                        "Test",
                        "summary",
                        "川菜",
                        "/api/v1/dish-assets/abc",
                        null,
                        30,
                        "家常",
                        java.math.BigDecimal.ZERO,
                        false,
                        true,
                        1,
                        0,
                        0,
                        2,
                        List.of("a"),
                        List.of("b"),
                        Instant.now(),
                        Instant.now());
        when(dishService.findOne(1L)).thenReturn(dishResponse);
        var dishEntity =
                new DishEntity() {
                    @Override
                    public Long getId() {
                        return 1L;
                    }
                };
        when(dishRepository.findById(1L)).thenReturn(Optional.of(dishEntity));
        when(assetService.findByDishId(1L)).thenThrow(new NotFoundException("图片不存在"));

        assertThatThrownBy(() -> importService.export(1L))
                .isInstanceOf(InvalidRecipeException.class);
    }
}
