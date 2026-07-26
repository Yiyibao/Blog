package com.yubai.blog.admin.ai;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.config.AiProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 4A-1：AI 供应商注册表。密钥 AES-GCM 加密入库、响应只回显尾 4 位；
 * base_url 经 SSRF 校验；既有 env 配置在注册表为空时 seed 为第一行（保持向后兼容）。
 */
@Service
public class AiProviderService {
    private static final Logger log = LoggerFactory.getLogger(AiProviderService.class);

    private final AiProviderRepository repository;
    private final AiCrypto crypto;
    private final AiBaseUrlValidator baseUrlValidator;
    private final OpenAiCompatibleClient client;
    private final AiProperties properties;

    public AiProviderService(AiProviderRepository repository, AiCrypto crypto,
                             AiBaseUrlValidator baseUrlValidator, OpenAiCompatibleClient client,
                             AiProperties properties) {
        this.repository = repository;
        this.crypto = crypto;
        this.baseUrlValidator = baseUrlValidator;
        this.client = client;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<AiProviderResponse> list() {
        return repository.findAll().stream()
            .map(entity -> AiProviderResponse.from(entity, keyTail(entity)))
            .toList();
    }

    @Transactional
    public AiProviderResponse create(AiProviderRequest request) {
        if (repository.existsByNameIgnoreCase(request.name().trim())) {
            throw new AiServiceException(HttpStatus.CONFLICT, "同名供应商已存在");
        }
        var baseUrl = baseUrlValidator.validate(request.baseUrl());
        String encryptedKey = null;
        if (hasText(request.apiKey())) {
            encryptedKey = crypto.encrypt(request.apiKey().trim());
        }
        var entity = AiProviderEntity.create(
            request.name().trim(),
            baseUrl,
            encryptedKey,
            joinModels(request.models()),
            request.defaultModel().trim(),
            request.enabledOrDefault(),
            request.dailyRequestLimitOrDefault(),
            request.dailyTokenLimitOrDefault());
        if (repository.count() == 0) {
            entity.markDefault(true);
        }
        var saved = repository.save(entity);
        return AiProviderResponse.from(saved, keyTail(saved));
    }

    @Transactional
    public AiProviderResponse update(Long id, AiProviderRequest request) {
        var entity = repository.findById(id)
            .orElseThrow(() -> new NotFoundException("AI 供应商不存在"));
        var newName = request.name().trim();
        if (!entity.getName().equalsIgnoreCase(newName) && repository.existsByNameIgnoreCase(newName)) {
            throw new AiServiceException(HttpStatus.CONFLICT, "同名供应商已存在");
        }
        var baseUrl = baseUrlValidator.validate(request.baseUrl());
        entity.update(newName, baseUrl, joinModels(request.models()), request.defaultModel().trim(),
            request.enabledOrDefault(), request.dailyRequestLimitOrDefault(), request.dailyTokenLimitOrDefault());
        // 密钥留空表示保留原值——界面上密钥只写不回显。
        if (hasText(request.apiKey())) {
            entity.replaceApiKey(crypto.encrypt(request.apiKey().trim()));
        }
        var saved = repository.save(entity);
        return AiProviderResponse.from(saved, keyTail(saved));
    }

    @Transactional
    public void delete(Long id) {
        var entity = repository.findById(id)
            .orElseThrow(() -> new NotFoundException("AI 供应商不存在"));
        var wasDefault = entity.isDefault();
        repository.delete(entity);
        repository.flush();
        if (wasDefault) {
            repository.findFirstByEnabledTrueOrderByIdAsc().ifPresent(next -> {
                next.markDefault(true);
                repository.save(next);
            });
        }
    }

    @Transactional
    public AiProviderResponse setDefault(Long id) {
        var entity = repository.findById(id)
            .orElseThrow(() -> new NotFoundException("AI 供应商不存在"));
        if (!entity.isEnabled()) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST, "已停用的供应商不能设为默认");
        }
        for (var other : repository.findAll()) {
            if (other.isDefault() && !other.getId().equals(entity.getId())) {
                other.markDefault(false);
                repository.save(other);
            }
        }
        entity.markDefault(true);
        var saved = repository.save(entity);
        return AiProviderResponse.from(saved, keyTail(saved));
    }

    /** 连通性测试：以该供应商配置请求 /models；失败作为结果返回而非抛错，便于界面展示。 */
    @Transactional(readOnly = true)
    public AiProviderTestResult testConnection(Long id) {
        var entity = repository.findById(id)
            .orElseThrow(() -> new NotFoundException("AI 供应商不存在"));
        try {
            var endpoint = toEndpoint(entity, entity.getDefaultModel());
            var models = client.listModels(endpoint);
            return new AiProviderTestResult(true, "连接成功", models);
        } catch (AiServiceException exception) {
            return new AiProviderTestResult(false, exception.getMessage(), List.of());
        }
    }

    /**
     * 聊天调用的端点解析：显式 providerId → 注册表默认 → 任一启用行 → 既有 env 配置回退。
     */
    @Transactional(readOnly = true)
    public AiEndpoint resolveEndpoint(Long providerId, String requestedModel) {
        if (providerId != null) {
            var entity = repository.findById(providerId)
                .filter(AiProviderEntity::isEnabled)
                .orElseThrow(() -> new AiServiceException(HttpStatus.BAD_REQUEST, "供应商不存在或已停用"));
            return toEndpoint(entity, resolveModel(entity, requestedModel));
        }
        var registryEntity = repository.findFirstByIsDefaultTrueAndEnabledTrue()
            .or(repository::findFirstByEnabledTrueOrderByIdAsc)
            .orElse(null);
        if (registryEntity != null) {
            return toEndpoint(registryEntity, resolveModel(registryEntity, requestedModel));
        }
        if (properties.isEnabled() && hasText(properties.getApiKey())) {
            return new AiEndpoint(
                properties.getBaseUrl(),
                properties.getApiKey(),
                requestedModel != null && !requestedModel.isBlank() ? requestedModel : properties.getModel(),
                properties.getRequestTimeout(),
                properties.getMaxOutputTokens());
        }
        throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, "AI service is not configured");
    }

    /** 既有 env 配置 seed：注册表为空且 env 已启用时迁移为第一行，保持升级后行为不变。 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedFromLegacyEnv() {
        if (!crypto.isReady() || repository.count() > 0) {
            return;
        }
        if (!properties.isEnabled() || !hasText(properties.getApiKey())) {
            return;
        }
        var entity = AiProviderEntity.create(
            "deepseek",
            properties.getBaseUrl().replaceAll("/+$", ""),
            crypto.encrypt(properties.getApiKey()),
            properties.getModel(),
            properties.getModel(),
            true,
            200,
            200_000);
        entity.markDefault(true);
        repository.save(entity);
        log.info("AI 供应商注册表为空，已从环境变量配置生成默认供应商 deepseek");
    }

    private String resolveModel(AiProviderEntity entity, String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            return entity.getDefaultModel();
        }
        var allowed = AiProviderResponse.parseModels(entity.getModels());
        if (!allowed.isEmpty() && !allowed.contains(requestedModel)) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST, "模型不在该供应商的允许列表中");
        }
        return requestedModel;
    }

    private AiEndpoint toEndpoint(AiProviderEntity entity, String model) {
        String apiKey = null;
        if (entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank()) {
            apiKey = crypto.decrypt(entity.getApiKeyEncrypted());
        }
        return new AiEndpoint(entity.getBaseUrl(), apiKey, model,
            properties.getRequestTimeout(), properties.getMaxOutputTokens());
    }

    private String keyTail(AiProviderEntity entity) {
        if (entity.getApiKeyEncrypted() == null || entity.getApiKeyEncrypted().isBlank() || !crypto.isReady()) {
            return null;
        }
        try {
            var plain = crypto.decrypt(entity.getApiKeyEncrypted());
            return plain.length() <= 4 ? plain : plain.substring(plain.length() - 4);
        } catch (AiServiceException exception) {
            return null;
        }
    }

    private static String joinModels(List<String> models) {
        if (models == null || models.isEmpty()) {
            return "";
        }
        return String.join(",", models.stream().map(String::trim).filter(m -> !m.isEmpty()).toList());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
