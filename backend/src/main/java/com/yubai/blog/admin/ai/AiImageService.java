package com.yubai.blog.admin.ai;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.config.AiImageProperties;
import com.yubai.blog.storage.StorageService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiImageService {
    private static final List<String> SIZE_VALUES = List.of("auto", "1024x1024", "1024x1536", "1536x1024");
    private static final List<String> QUALITY_VALUES = List.of("auto", "low", "medium", "high");
    private static final List<String> ASPECT_VALUES = List.of("auto", "1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3");
    private static final List<String> RESOLUTION_VALUES = List.of("auto", "1k", "2k", "4k");
    private static final int TITLE_CHAR_LIMIT = 10;

    private final AiImageProperties properties;
    private final AiBaseUrlValidator baseUrlValidator;
    private final OpenAiImageClient client;
    private final AiGeneratedImageRepository repository;
    private final AiImageSessionRepository sessionRepository;
    private final StorageService storage;

    public AiImageService(AiImageProperties properties, AiBaseUrlValidator baseUrlValidator,
                          OpenAiImageClient client, AiGeneratedImageRepository repository,
                          AiImageSessionRepository sessionRepository, StorageService storage) {
        this.properties = properties;
        this.baseUrlValidator = baseUrlValidator;
        this.client = client;
        this.repository = repository;
        this.sessionRepository = sessionRepository;
        this.storage = storage;
    }

    public List<AiImageModelResponse> listModels() {
        var result = new ArrayList<AiImageModelResponse>();
        addModels(result, "grok", properties.getGrok());
        addModels(result, "gpt", properties.getGpt());
        return List.copyOf(result);
    }

    @Transactional
    public AiImageGenerateResponse generate(AiImageGenerateRequest request, String owner) {
        if (!properties.isEnabled()) {
            throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, "AI image generation is disabled");
        }
        var prompt = request.prompt().trim();
        if (prompt.length() > Math.max(1, properties.getMaxPromptChars())) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST, "prompt 超出当前服务限制");
        }
        var selected = selectProvider(request.provider(), request.model());
        var count = request.n() == null ? 1 : request.n();
        if (count > Math.max(1, properties.getMaxImages())) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST, "一次最多生成 " + properties.getMaxImages() + " 张图片");
        }
        var endpoint = endpoint(selected.provider(), selected.config(), request.model());
        // 已有会话先校验归属（fail fast，避免无效会话白白消耗上游配额）
        var pendingSession = request.sessionId() == null
            ? null
            : requireOwned(request.sessionId(), owner);
        var options = new AiImageGenerationRequest(
            prompt, count, normalize(request.size(), SIZE_VALUES, "size"),
            normalize(request.quality(), QUALITY_VALUES, "quality"),
            normalize(request.aspectRatio(), ASPECT_VALUES, "aspectRatio"),
            normalize(request.resolution(), RESOLUTION_VALUES, "resolution"));
        var result = client.generate(endpoint, options, properties.getMaxImageBytes());
        var session = pendingSession == null
            ? sessionRepository.save(AiImageSessionEntity.create(owner, titleFrom(prompt)))
            : sessionRepository.save(pendingSession);
        var saved = new ArrayList<AiGeneratedImageResponse>();
        try {
            var generationId = UUID.randomUUID();
            for (var image : result.images()) {
                var publicId = UUID.randomUUID();
                var extension = extension(image.mediaType());
                var storageKey = "ai-generated/" + publicId + extension;
                storage.store(storageKey, image.bytes());
                var entity = AiGeneratedImageEntity.create(
                    session.getId(), generationId, selected.provider(), result.model(), prompt, storageKey,
                    publicId + extension, image.mediaType(), image.bytes().length,
                    sha256hex(image.bytes()), image.width(), image.height());
                saved.add(AiGeneratedImageResponse.from(repository.save(entity)));
            }
        } catch (RuntimeException exception) {
            for (var item : saved) {
                repository.findByPublicId(item.publicId()).ifPresent(entity -> {
                    repository.delete(entity);
                    storage.delete(entity.getStorageKey());
                });
            }
            throw exception;
        }
        if (saved.isEmpty()) throw new AiServiceException(HttpStatus.BAD_GATEWAY, "AI image service returned no images");
        return new AiImageGenerateResponse(session.getId(), session.getTitle(), List.copyOf(saved));
    }

    @Transactional(readOnly = true)
    public List<AiImageSessionResponse> listSessions(String owner) {
        return sessionRepository.findByOwnerOrderByUpdatedAtDesc(owner).stream()
            .map(AiImageSessionResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<AiGeneratedImageResponse> sessionImages(Long sessionId, String owner) {
        var session = requireOwned(sessionId, owner);
        return repository.findBySessionIdOrderByCreatedAtAsc(session.getId()).stream()
            .map(AiGeneratedImageResponse::from)
            .toList();
    }

    @Transactional
    public void deleteSession(Long sessionId, String owner) {
        var session = requireOwned(sessionId, owner);
        for (var entity : repository.findBySessionIdOrderByCreatedAtAsc(session.getId())) {
            repository.delete(entity);
            storage.delete(entity.getStorageKey());
        }
        sessionRepository.delete(session);
    }

    public AiGeneratedImageEntity find(UUID publicId) {
        return repository.findByPublicId(publicId)
            .orElseThrow(() -> new NotFoundException("图片不存在"));
    }

    public byte[] read(UUID publicId) {
        var entity = find(publicId);
        try {
            return storage.read(entity.getStorageKey());
        } catch (RuntimeException exception) {
            throw new NotFoundException("图片内容不存在");
        }
    }

    public void delete(UUID publicId) {
        var entity = find(publicId);
        repository.delete(entity);
        storage.delete(entity.getStorageKey());
    }

    private AiImageSessionEntity requireOwned(Long sessionId, String owner) {
        return sessionRepository.findByIdAndOwner(sessionId, owner)
            .orElseThrow(() -> new NotFoundException("图片会话不存在"));
    }

    /** 取首条提示词规范化后的前十个字（按 Unicode 码点，避免截断代理对）。 */
    static String titleFrom(String content) {
        var compact = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (compact.isEmpty()) return null;
        var builder = new StringBuilder();
        compact.codePoints().limit(TITLE_CHAR_LIMIT).forEach(builder::appendCodePoint);
        return builder.toString();
    }

    private SelectedProvider selectProvider(String requestedProvider, String requestedModel) {
        var provider = requestedProvider == null ? "" : requestedProvider.trim().toLowerCase(Locale.ROOT);
        if (!provider.isBlank() && !provider.equals("grok") && !provider.equals("gpt")) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST, "provider 只能是 grok 或 gpt");
        }
        if (provider.equals("grok")) return selectFrom("grok", properties.getGrok(), requestedModel);
        if (provider.equals("gpt")) return selectFrom("gpt", properties.getGpt(), requestedModel);
        if (requestedModel != null && !requestedModel.isBlank()) {
            if (containsModel(properties.getGrok(), requestedModel)) return selectFrom("grok", properties.getGrok(), requestedModel);
            if (containsModel(properties.getGpt(), requestedModel)) return selectFrom("gpt", properties.getGpt(), requestedModel);
        }
        if (properties.getGrok().isEnabled()) return selectFrom("grok", properties.getGrok(), null);
        return selectFrom("gpt", properties.getGpt(), null);
    }

    private SelectedProvider selectFrom(String provider, AiImageProperties.Provider config, String model) {
        if (!config.isEnabled()) throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, provider + " image provider is disabled");
        var models = models(config);
        if (models.isEmpty()) throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, provider + " image provider has no models");
        var selected = model == null || model.isBlank() ? firstModel(config, models) : model.trim();
        if (!models.contains(selected)) throw new AiServiceException(HttpStatus.BAD_REQUEST, "model 不在当前图片供应商白名单中");
        return new SelectedProvider(provider, config);
    }

    private AiImageEndpoint endpoint(String provider, AiImageProperties.Provider config, String requestedModel) {
        var models = models(config);
        var model = requestedModel == null || requestedModel.isBlank() ? firstModel(config, models) : requestedModel.trim();
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, provider + " image provider is not configured");
        }
        var baseUrl = baseUrlValidator.validate(config.getBaseUrl());
        return new AiImageEndpoint(provider, baseUrl, config.getApiKey(), model, config.getWireApi(),
            config.getHeaderName(), config.getHeaderValue(), Math.max(1, properties.getRequestTimeout()));
    }

    private static void addModels(List<AiImageModelResponse> output, String provider, AiImageProperties.Provider config) {
        if (!config.isEnabled()) return;
        var models = models(config);
        var defaultModel = firstModel(config, models);
        for (var model : models) output.add(new AiImageModelResponse(provider, model, model.equals(defaultModel)));
    }

    private static List<String> models(AiImageProperties.Provider config) {
        var values = new LinkedHashSet<String>();
        if (config.getModels() != null) {
            for (var raw : config.getModels().split(",")) {
                if (!raw.isBlank()) values.add(raw.trim());
            }
        }
        if (values.isEmpty() && config.getDefaultModel() != null && !config.getDefaultModel().isBlank()) {
            values.add(config.getDefaultModel().trim());
        }
        return List.copyOf(values);
    }

    private static String firstModel(AiImageProperties.Provider config, List<String> models) {
        if (config.getDefaultModel() != null && models.contains(config.getDefaultModel().trim())) return config.getDefaultModel().trim();
        return models.isEmpty() ? "" : models.get(0);
    }

    private static boolean containsModel(AiImageProperties.Provider config, String model) {
        return config.isEnabled() && models(config).contains(model.trim());
    }

    private static String normalize(String value, List<String> allowed, String field) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new AiServiceException(HttpStatus.BAD_REQUEST, field + " 不支持该取值");
        return "auto".equals(normalized) ? null : normalized;
    }

    private static String extension(String mediaType) {
        return switch (mediaType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".png";
        };
    }

    private static String sha256hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record SelectedProvider(String provider, AiImageProperties.Provider config) {}
}
