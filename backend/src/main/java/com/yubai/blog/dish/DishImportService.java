package com.yubai.blog.dish;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DishImportService {
    private static final Logger log = LoggerFactory.getLogger(DishImportService.class);

    static final long MAX_COMPRESSED_SIZE = 25L * 1024 * 1024;
    static final int MAX_ENTRIES = 2;
    static final long MAX_UNCOMPRESSED_TOTAL = 40L * 1024 * 1024;
    static final long MAX_ENTRY_SIZE = 10L * 1024 * 1024;
    static final int MAX_COMPRESSION_RATIO = 100;
    static final Duration STAGING_TTL = Duration.ofMinutes(30);
    static final long MAX_ACTIVE_IMPORT_COUNT_PER_OWNER = 10;
    static final long MAX_ACTIVE_IMPORT_BYTES_PER_OWNER = 64L * 1024 * 1024;
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

    private final DishImportStagingRepository stagingRepository;
    private final DishRepository dishRepository;
    private final DishCategoryService categoryService;
    private final DishAssetService assetService;
    private final DishService dishService;
    private final StorageService storageService;
    private final YrecipeArchiveCodec archiveCodec;
    private final DishImportStagingWriter stagingWriter;

    @Autowired
    public DishImportService(
            DishImportStagingRepository stagingRepository,
            DishRepository dishRepository,
            DishCategoryService categoryService,
            DishAssetService assetService,
            DishService dishService,
            StorageService storageService,
            YrecipeArchiveCodec archiveCodec,
            DishImportStagingWriter stagingWriter) {
        this.stagingRepository = stagingRepository;
        this.dishRepository = dishRepository;
        this.categoryService = categoryService;
        this.assetService = assetService;
        this.dishService = dishService;
        this.storageService = storageService;
        this.archiveCodec = archiveCodec;
        this.stagingWriter = stagingWriter;
    }

    DishImportService(
            DishImportStagingRepository stagingRepository,
            DishRepository dishRepository,
            DishCategoryService categoryService,
            DishAssetService assetService,
            DishService dishService,
            StorageService storageService) {
        this(
                stagingRepository,
                dishRepository,
                categoryService,
                assetService,
                dishService,
                storageService,
                new YrecipeArchiveCodec(),
                new DishImportStagingWriter(stagingRepository));
    }

    public DishImportPreviewResponse preview(MultipartFile file) {
        return preview(file, "admin");
    }

    public DishImportPreviewResponse preview(MultipartFile file, String owner) {
        if (file.isEmpty() || file.getSize() > MAX_COMPRESSED_SIZE) {
            throw new InvalidRecipeException("压缩包不能为空且不能超过 25 MB");
        }
        try {
            return previewFromBytes(file.getBytes(), owner);
        } catch (IOException exception) {
            throw new InvalidRecipeException("无法读取上传文件");
        }
    }

    @Transactional(readOnly = true)
    public DishImportPreviewResponse getStagedPreview(UUID token) {
        return getStagedPreview(token, null);
    }

    @Transactional(readOnly = true)
    public DishImportPreviewResponse getStagedPreview(UUID token, String owner) {
        var staging =
                stagingRepository
                        .findByToken(token)
                        .orElseThrow(() -> new NotFoundException("导入会话不存在或已过期"));
        assertOwned(staging, owner);
        if (staging.getExpiresAt().isBefore(Instant.now())
                || staging.isCancelled()
                || staging.isConsumed()) {
            throw new NotFoundException("导入会话不存在或已过期");
        }
        final YrecipePackage pkg;
        try {
            pkg = MAPPER.readValue(staging.getRecipeJson(), YrecipePackage.class);
        } catch (IOException exception) {
            throw new InvalidRecipeException("菜谱数据损坏");
        }
        var matchedCategory = findMatchingCategory(pkg.recipe().categoryHint());
        var slugAvailable =
                pkg.recipe().slug() == null
                        || pkg.recipe().slug().isBlank()
                        || !dishRepository.existsBySlug(pkg.recipe().slug());
        var warnings = new ArrayList<String>();
        if (pkg.recipe().slug() != null && !pkg.recipe().slug().isBlank()) {
            warnings.add(
                    slugAvailable
                            ? "Slug '" + pkg.recipe().slug() + "' 可用"
                            : "Slug '" + pkg.recipe().slug() + "' 已被占用，请修改");
        }
        if (pkg.recipe().categoryHint() != null
                && !pkg.recipe().categoryHint().isBlank()
                && matchedCategory == null) {
            warnings.add("未找到匹配的分类 '" + pkg.recipe().categoryHint() + "'，请从现有分类中选择");
        }
        if (pkg.generation() != null && pkg.generation().warnings() != null)
            warnings.addAll(pkg.generation().warnings());
        return new DishImportPreviewResponse(
                token,
                staging.getExpiresAt(),
                pkg,
                warnings,
                matchedCategory,
                slugAvailable,
                "/api/v1/admin/dish-imports/" + token + "/cover");
    }

    @Transactional
    public DishResponse commit(UUID token, DishImportCommitRequest request) {
        return commit(token, request, null);
    }

    @Transactional
    public DishResponse commit(UUID token, DishImportCommitRequest request, String owner) {
        var staging =
                stagingRepository
                        .findByToken(token)
                        .orElseThrow(() -> new NotFoundException("导入会话不存在或已过期"));

        assertOwned(staging, owner);
        if (staging.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRecipeException("导入会话已过期，请重新上传");
        }

        if (staging.isConsumed()) {
            throw new DataIntegrityViolationException("导入会话已被使用");
        }
        staging.setConsumed(true);
        stagingRepository.save(staging);

        categoryService.requireExisting(request.category());

        YrecipePackage pkg;
        try {
            pkg = MAPPER.readValue(staging.getRecipeJson(), YrecipePackage.class);
        } catch (IOException e) {
            throw new InvalidRecipeException("菜谱数据损坏");
        }
        if (pkg == null || pkg.recipe() == null || pkg.cover() == null) {
            throw new InvalidRecipeException("菜谱数据不完整");
        }

        var recipe = pkg.recipe();
        String imageAlt = pkg.cover().alt() != null ? pkg.cover().alt() : recipe.name();
        String difficulty = recipe.difficulty() != null ? recipe.difficulty() : "家常";
        int baseServings = recipe.baseServings() > 0 ? recipe.baseServings() : 2;

        int displayOrder = dishRepository.maxDisplayOrder() + 1;

        DishResponse dishResponse;
        var stagingStorageKey = staging.getStorageKey();
        if (stagingStorageKey != null) {
            var coverData = storageService.read(stagingStorageKey);
            var ext = YrecipeArchiveCodec.extensionForMediaType(staging.getMediaType());
            var assetKey = "dish-assets/" + UUID.randomUUID() + "/image" + ext;

            var width = 0;
            var height = 0;
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(coverData))) {
                var readers = ImageIO.getImageReaders(input);
                if (readers.hasNext()) {
                    var reader = readers.next();
                    try {
                        reader.setInput(input, true, true);
                        width = reader.getWidth(0);
                        height = reader.getHeight(0);
                    } finally {
                        reader.dispose();
                    }
                }
            } catch (IOException ignored) {
            }

            var asset =
                    assetService.createForDish(
                            0L,
                            assetKey,
                            "cover" + ext,
                            staging.getMediaType(),
                            coverData,
                            width > 0 ? width : null,
                            height > 0 ? height : null);

            var imageUrl = "/api/v1/dish-assets/" + asset.getPublicId();

            dishResponse =
                    dishService.create(
                            new DishRequest(
                                    recipe.name(),
                                    recipe.summary(),
                                    request.category(),
                                    imageUrl,
                                    imageAlt,
                                    recipe.prepMinutes(),
                                    difficulty,
                                    java.math.BigDecimal.ZERO,
                                    false,
                                    request.published(),
                                    displayOrder,
                                    baseServings,
                                    recipe.ingredients(),
                                    recipe.steps()));

            assetService.assignToDish(asset.getId(), dishResponse.id());

            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                deleteQuietly(stagingStorageKey);
                            }

                            @Override
                            public void afterCompletion(int status) {
                                if (status != STATUS_COMMITTED) deleteQuietly(assetKey);
                            }
                        });
            }
        } else {
            dishResponse =
                    dishService.create(
                            new DishRequest(
                                    recipe.name(),
                                    recipe.summary(),
                                    request.category(),
                                    "",
                                    imageAlt,
                                    recipe.prepMinutes(),
                                    difficulty,
                                    java.math.BigDecimal.ZERO,
                                    false,
                                    request.published(),
                                    displayOrder,
                                    baseServings,
                                    recipe.ingredients(),
                                    recipe.steps()));
        }

        return dishResponse;
    }

    @Transactional
    public void cancel(UUID token) {
        cancel(token, null);
    }

    @Transactional
    public void cancel(UUID token, String owner) {
        var staging = stagingRepository.findByToken(token).orElse(null);
        if (staging == null) return;
        assertOwned(staging, owner);
        staging.setCancelled(true);
        stagingRepository.save(staging);
        var storageKey = staging.getStorageKey();
        if (storageKey != null) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                deleteQuietly(storageKey);
                            }
                        });
            } else {
                deleteQuietly(storageKey);
            }
        }
    }

    public ResponseEntity<byte[]> export(long dishId) {
        var dish = dishService.findOne(dishId);
        var assetOpt =
                dishRepository
                        .findById(dishId)
                        .flatMap(
                                d -> {
                                    try {
                                        return java.util.Optional.of(
                                                assetService.findByDishId(d.getId()));
                                    } catch (NotFoundException e) {
                                        return java.util.Optional.<DishAssetEntity>empty();
                                    }
                                });

        if (assetOpt.isEmpty()) {
            var imageUrl = dish.imageUrl();
            if (imageUrl != null && !imageUrl.startsWith("/api/v1/dish-assets/")) {
                throw new InvalidRecipeException("该菜品的图片为外部链接，无法导出为 .yrecipe 包。请先上传自有图片再重试。");
            }
            throw new InvalidRecipeException("该菜品没有关联的图片资源，无法导出。");
        }

        var asset = assetOpt.get();
        var coverData = assetService.readContent(asset.getPublicId());

        var yrecipe =
                new YrecipePackage(
                        "1.0",
                        "yubai.recipe",
                        UUID.randomUUID().toString(),
                        new YrecipePackage.YrecipeContent(
                                dish.name(),
                                dish.slug(),
                                dish.summary(),
                                dish.category(),
                                dish.prepMinutes(),
                                dish.difficulty(),
                                dish.baseServings(),
                                dish.ingredients(),
                                dish.steps()),
                        new YrecipePackage.YrecipeCover(
                                "assets/cover"
                                        + YrecipeArchiveCodec.extFromMediaType(
                                                asset.getMediaType()),
                                dish.imageAlt()),
                        null,
                        null);

        byte[] zipBytes;
        try {
            zipBytes =
                    archiveCodec.buildExportZip(
                            yrecipe,
                            coverData,
                            YrecipeArchiveCodec.extFromMediaType(asset.getMediaType()));
        } catch (IOException e) {
            throw new InvalidRecipeException("生成导出包失败: " + e.getMessage());
        }

        var filename = dish.slug() + ".yrecipe";
        var headers = new HttpHeaders();
        headers.setContentDisposition(
                ContentDisposition.attachment()
                        .filename(filename, java.nio.charset.StandardCharsets.UTF_8)
                        .build());
        headers.setContentType(MediaType.parseMediaType("application/vnd.yubai.recipe+zip"));
        headers.setCacheControl("private, no-store");
        return new ResponseEntity<>(zipBytes, headers, HttpStatus.OK);
    }

    public ResponseEntity<byte[]> downloadStaged(UUID token) {
        return downloadStaged(token, null);
    }

    public ResponseEntity<byte[]> downloadStaged(UUID token, String owner) {
        var staging =
                stagingRepository
                        .findByToken(token)
                        .orElseThrow(() -> new NotFoundException("导入会话不存在"));
        assertOwned(staging, owner);
        if (staging.getExpiresAt().isBefore(Instant.now()) || staging.isCancelled()) {
            throw new NotFoundException("导入会话已过期");
        }
        if (staging.getStorageKey() == null) {
            throw new NotFoundException("菜谱封面不存在");
        }

        final YrecipePackage pkg;
        try {
            pkg = MAPPER.readValue(staging.getRecipeJson(), YrecipePackage.class);
        } catch (IOException exception) {
            throw new InvalidRecipeException("菜谱数据损坏");
        }
        var coverData = storageService.read(staging.getStorageKey());
        final byte[] zipBytes;
        try {
            zipBytes =
                    archiveCodec.buildExportZip(
                            pkg,
                            coverData,
                            YrecipeArchiveCodec.extensionForMediaType(staging.getMediaType()));
        } catch (IOException exception) {
            throw new InvalidRecipeException("生成菜谱包失败: " + exception.getMessage());
        }

        var baseName =
                pkg.recipe() != null
                                && pkg.recipe().slug() != null
                                && pkg.recipe().slug().matches("^[a-z0-9]+(?:-[a-z0-9]+)*$")
                        ? pkg.recipe().slug()
                        : "generated-recipe";
        var headers = new HttpHeaders();
        headers.setContentDisposition(
                ContentDisposition.attachment()
                        .filename(baseName + ".yrecipe", java.nio.charset.StandardCharsets.UTF_8)
                        .build());
        headers.setContentType(MediaType.parseMediaType("application/vnd.yubai.recipe+zip"));
        headers.setCacheControl("private, no-store");
        return new ResponseEntity<>(zipBytes, headers, HttpStatus.OK);
    }

    public byte[] readStagedCover(UUID token) {
        return readStagedCover(token, null);
    }

    public byte[] readStagedCover(UUID token, String owner) {
        var staging =
                stagingRepository
                        .findByToken(token)
                        .orElseThrow(() -> new NotFoundException("导入会话不存在"));
        assertOwned(staging, owner);
        if (staging.getExpiresAt().isBefore(Instant.now())) {
            throw new NotFoundException("导入会话已过期");
        }
        if (staging.getStorageKey() == null) {
            throw new NotFoundException("封面图片不存在");
        }
        return storageService.read(staging.getStorageKey());
    }

    public String getStagedMediaType(UUID token) {
        return getStagedMediaType(token, null);
    }

    public String getStagedMediaType(UUID token, String owner) {
        var staging =
                stagingRepository
                        .findByToken(token)
                        .orElseThrow(() -> new NotFoundException("导入会话不存在"));
        assertOwned(staging, owner);
        if (staging.getExpiresAt().isBefore(Instant.now())) {
            throw new NotFoundException("导入会话已过期");
        }
        return staging.getMediaType();
    }

    public DishImportPreviewResponse previewFromBytes(byte[] zipData) {
        return previewFromBytes(zipData, "admin");
    }

    public DishImportPreviewResponse previewFromBytes(byte[] zipData, String owner) {
        var normalizedOwner = requireOwner(owner);
        if (zipData.length == 0 || zipData.length > MAX_COMPRESSED_SIZE) {
            throw new InvalidRecipeException("压缩包不能为空且不能超过 25 MB");
        }
        var result = archiveCodec.validateAndExtract(zipData);
        var pkg = result.pkg();
        if (pkg == null || pkg.recipe() == null) {
            throw new InvalidRecipeException("菜谱数据不完整");
        }
        if (pkg.cover() == null) {
            throw new InvalidRecipeException("封面信息不能为空");
        }
        var coverData = result.coverData();
        var coverMediaType = result.coverMediaType();
        var width = result.width();
        var height = result.height();
        stagingWriter.assertQuota(normalizedOwner, coverData.length);
        String matchedCategory = findMatchingCategory(pkg.recipe().categoryHint());
        boolean slugAvailable =
                pkg.recipe().slug() == null
                        || pkg.recipe().slug().isBlank()
                        || !dishRepository.existsBySlug(pkg.recipe().slug());

        var ext = YrecipeArchiveCodec.extensionForMediaType(coverMediaType);
        var storageKey = "imports/" + UUID.randomUUID() + "/cover" + ext;
        storageService.store(storageKey, coverData);

        String recipeJson;
        try {
            recipeJson = MAPPER.writeValueAsString(pkg);
        } catch (IOException e) {
            deleteQuietly(storageKey);
            throw new InvalidRecipeException("无法序列化菜谱 JSON");
        }

        DishImportStagingEntity staging;
        try {
            staging =
                    stagingWriter.stage(
                            normalizedOwner,
                            recipeJson,
                            storageKey,
                            coverMediaType,
                            coverData.length,
                            Instant.now().plus(STAGING_TTL));
        } catch (RuntimeException exception) {
            deleteQuietly(storageKey);
            throw exception;
        }

        String coverPreviewUrl = "/api/v1/admin/dish-imports/" + staging.getToken() + "/cover";

        List<String> warnings = new ArrayList<>();
        if (pkg.recipe().slug() != null && !pkg.recipe().slug().isBlank() && slugAvailable) {
            warnings.add("Slug '" + pkg.recipe().slug() + "' 可用");
        } else if (pkg.recipe().slug() != null && !pkg.recipe().slug().isBlank()) {
            warnings.add("Slug '" + pkg.recipe().slug() + "' 已被占用，请修改");
        }
        if (pkg.recipe().categoryHint() != null
                && !pkg.recipe().categoryHint().isBlank()
                && matchedCategory == null) {
            warnings.add("未找到匹配的分类 '" + pkg.recipe().categoryHint() + "'，请从现有分类中选择");
        }
        if (pkg.generation() != null && pkg.generation().warnings() != null) {
            warnings.addAll(pkg.generation().warnings());
        }

        return new DishImportPreviewResponse(
                staging.getToken(),
                staging.getExpiresAt(),
                pkg,
                warnings,
                matchedCategory,
                slugAvailable,
                coverPreviewUrl);
    }

    private static String requireOwner(String owner) {
        var normalized = owner == null ? "" : owner.trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("Resource owner is invalid");
        }
        return normalized;
    }

    private static void assertOwned(DishImportStagingEntity staging, String owner) {
        if (owner != null && !staging.getOwner().equals(requireOwner(owner))) {
            throw new NotFoundException("Dish import does not exist");
        }
    }

    private String findMatchingCategory(String categoryHint) {
        if (categoryHint == null || categoryHint.isBlank()) return null;
        var all = categoryService.findAll();
        var exact = all.stream().filter(c -> c.name().equals(categoryHint)).findFirst();
        if (exact.isPresent()) return exact.get().name();
        var fuzzy =
                all.stream()
                        .filter(
                                c ->
                                        c.name().contains(categoryHint)
                                                || categoryHint.contains(c.name()))
                        .findFirst();
        return fuzzy.map(AdminDishCategory::name).orElse(null);
    }

    static boolean isValidHttpsUrl(String url) {
        return YrecipeArchiveCodec.isValidHttpsUrl(url);
    }

    private void deleteQuietly(String storageKey) {
        try {
            storageService.delete(storageKey);
        } catch (Exception e) {
            log.warn("Failed to clean up storage {}: {}", storageKey, e.toString());
        }
    }
}
