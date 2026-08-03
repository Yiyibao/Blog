package com.yubai.blog.admin.ai;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.config.AiProperties;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final String ANTHROPIC_ENV_PROVIDER_NAME = "Anthropic (env)";
    private static final String RESPONSES_ENV_PROVIDER_NAME = "GPT (Responses)";

    private final AiProviderRepository repository;
    private final AiCrypto crypto;
    private final AiBaseUrlValidator baseUrlValidator;
    private final OpenAiCompatibleClient openaiClient;
    private final OpenAiResponsesClient responsesClient;
    private final AnthropicClient anthropicClient;
    private final OpenCodeServerClient opencodeClient;
    private final AiProperties properties;

    @Autowired
    public AiProviderService(AiProviderRepository repository, AiCrypto crypto,
                             AiBaseUrlValidator baseUrlValidator, OpenAiCompatibleClient openaiClient,
                             AnthropicClient anthropicClient, OpenCodeServerClient opencodeClient,
                             OpenAiResponsesClient responsesClient, AiProperties properties) {
        this.repository = repository;
        this.crypto = crypto;
        this.baseUrlValidator = baseUrlValidator;
        this.openaiClient = openaiClient;
        this.responsesClient = responsesClient;
        this.anthropicClient = anthropicClient;
        this.opencodeClient = opencodeClient;
        this.properties = properties;
    }

    /** Source-compatible constructor used by focused unit tests and small integrations. */
    public AiProviderService(AiProviderRepository repository, AiCrypto crypto,
                             AiBaseUrlValidator baseUrlValidator, OpenAiCompatibleClient openaiClient,
                             AnthropicClient anthropicClient, OpenCodeServerClient opencodeClient,
                             AiProperties properties) {
        this(repository, crypto, baseUrlValidator, openaiClient, anthropicClient, opencodeClient,
            new OpenAiResponsesClient(properties), properties);
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
        var providerType = request.providerTypeOrDefault();
        var baseUrl = providerType == AiProviderType.OPENCODE_SERVER
            ? baseUrlValidator.validateForOpenCodeServer(request.baseUrl())
            : baseUrlValidator.validate(request.baseUrl());
        // OPENCODE_SERVER 不需要 DB apiKey（使用 env Basic password）
        String encryptedKey = null;
        if (providerType != AiProviderType.OPENCODE_SERVER && hasText(request.apiKey())) {
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
            request.dailyTokenLimitOrDefault(),
            request.providerTypeOrDefault());
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
        var providerType = request.providerTypeOrDefault();
        var baseUrl = providerType == AiProviderType.OPENCODE_SERVER
            ? baseUrlValidator.validateForOpenCodeServer(request.baseUrl())
            : baseUrlValidator.validate(request.baseUrl());
        entity.update(newName, baseUrl, joinModels(request.models()), request.defaultModel().trim(),
            request.enabledOrDefault(), request.dailyRequestLimitOrDefault(), request.dailyTokenLimitOrDefault(),
            providerType);
        // OPENCODE_SERVER 忽略/清除 DB apiKey（使用 env Basic password）
        if (providerType == AiProviderType.OPENCODE_SERVER) {
            entity.replaceApiKey(null);
        } else if (hasText(request.apiKey())) {
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
        // 先落库清除旧默认，再写新默认——V16 的部分唯一索引不允许瞬时双默认
        repository.flush();
        entity.markDefault(true);
        var saved = repository.save(entity);
        return AiProviderResponse.from(saved, keyTail(saved));
    }

    /**
     * 连通性测试：按供应商类型调用对应客户端的 listModels。
     * 失败作为结果返回而非抛错，便于界面展示。
     * 刻意不加 @Transactional——外部 HTTP 最长阻塞 requestTimeout 秒，
     * 包在事务里会长时间占用连接池连接，几次并发点击即可耗尽 HikariCP。
     */
    public AiProviderTestResult testConnection(Long id) {
        var entity = repository.findById(id)
            .orElseThrow(() -> new NotFoundException("AI 供应商不存在"));
        try {
            var endpoint = toEndpoint(entity, entity.getDefaultModel());
            var client = clientFor(entity.getProviderType());
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
        seedFromAnthropicEnv();
        seedFromResponsesEnv();
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
            200_000,
            AiProviderType.OPENAI_COMPATIBLE);
        entity.markDefault(true);
        repository.save(entity);
        log.info("AI 供应商注册表为空，已从环境变量配置生成默认供应商 deepseek");
    }

    /**
     * Materialize the native Anthropic environment configuration as an encrypted
     * provider row. This keeps the existing provider selector and usage budgets
     * working while ensuring the plaintext token only lives in the process env.
     */
    @Transactional
    public void seedFromAnthropicEnv() {
        if (!hasText(properties.getAnthropicBaseUrl()) || !hasText(properties.getAnthropicAuthToken())) {
            return;
        }
        if (!crypto.isReady()) {
            log.warn("ANTHROPIC_AUTH_TOKEN is configured but APP_AI_MASTER_KEY is unavailable; provider was not materialized");
            return;
        }
        var baseUrl = baseUrlValidator.validate(properties.getAnthropicBaseUrl());
        var configuredModel = hasText(properties.getAnthropicModel())
            ? properties.getAnthropicModel().trim()
            : "";
        var models = normalizeModels(properties.getAnthropicModels(), configuredModel);
        var model = configuredModel;
        if (!hasText(model) || !AiProviderResponse.parseModels(models).contains(model)) {
            model = firstModel(models);
        }
        var encryptedKey = crypto.encrypt(properties.getAnthropicAuthToken().trim());
        var hadActiveDefault = repository.findFirstByIsDefaultTrueAndEnabledTrue().isPresent();
        var entity = repository.findByNameIgnoreCase(ANTHROPIC_ENV_PROVIDER_NAME).orElse(null);
        var shouldMakeDefault = !hadActiveDefault || (entity != null && entity.isDefault());
        if (entity == null) {
            entity = AiProviderEntity.create(
                ANTHROPIC_ENV_PROVIDER_NAME,
                baseUrl,
                encryptedKey,
                models,
                model,
                true,
                200,
                200_000,
                AiProviderType.ANTHROPIC);
            entity.markDefault(false);
            repository.save(entity);
            repository.flush();
        } else {
            entity.update(ANTHROPIC_ENV_PROVIDER_NAME, baseUrl, models, model,
                true, 200, 200_000, AiProviderType.ANTHROPIC);
            entity.replaceApiKey(encryptedKey);
            repository.save(entity);
        }

        // Only claim the default slot when there is no active default already.
        // This lets an administrator keep OpenCode (or another provider) as the
        // default across restarts while the env-backed Anthropic row is refreshed.
        if (shouldMakeDefault) {
            for (var other : repository.findAll()) {
                if (other.isDefault() && other != entity) {
                    other.markDefault(false);
                    repository.save(other);
                }
            }
            repository.flush();
            entity.markDefault(true);
            repository.save(entity);
        }
        repository.flush();
        log.info("Anthropic provider materialized from environment configuration at {} (default={})", baseUrl, entity.isDefault());
    }

    /**
     * Materialize the environment-backed OpenAI Responses relay as a normal
     * provider row while preserving an existing active default such as OpenCode.
     */
    @Transactional
    public void seedFromResponsesEnv() {
        if (!properties.isResponsesEnabled()
            || !hasText(properties.getResponsesBaseUrl())
            || !hasText(properties.getResponsesApiKey())) {
            return;
        }
        if (!crypto.isReady()) {
            log.warn("APP_AI_RESPONSES_API_KEY is configured but APP_AI_MASTER_KEY is unavailable; provider was not materialized");
            return;
        }
        var baseUrl = baseUrlValidator.validate(properties.getResponsesBaseUrl());
        var configuredModel = hasText(properties.getResponsesModel())
            ? properties.getResponsesModel().trim()
            : "";
        var models = normalizeModels(properties.getResponsesModels(), configuredModel);
        var model = configuredModel;
        if (!hasText(model) || !AiProviderResponse.parseModels(models).contains(model)) {
            model = firstModel(models);
        }
        var encryptedKey = crypto.encrypt(properties.getResponsesApiKey().trim());
        var hadActiveDefault = repository.findFirstByIsDefaultTrueAndEnabledTrue().isPresent();
        var entity = repository.findByNameIgnoreCase(RESPONSES_ENV_PROVIDER_NAME).orElse(null);
        var shouldMakeDefault = !hadActiveDefault || (entity != null && entity.isDefault());
        if (entity == null) {
            entity = AiProviderEntity.create(
                RESPONSES_ENV_PROVIDER_NAME,
                baseUrl,
                encryptedKey,
                models,
                model,
                true,
                200,
                200_000,
                AiProviderType.OPENAI_RESPONSES);
            entity.markDefault(false);
            repository.save(entity);
            repository.flush();
        } else {
            entity.update(RESPONSES_ENV_PROVIDER_NAME, baseUrl, models, model,
                true, 200, 200_000, AiProviderType.OPENAI_RESPONSES);
            entity.replaceApiKey(encryptedKey);
            repository.save(entity);
        }

        if (shouldMakeDefault) {
            for (var other : repository.findAll()) {
                if (other.isDefault() && other != entity) {
                    other.markDefault(false);
                    repository.save(other);
                }
            }
            repository.flush();
            entity.markDefault(true);
            repository.save(entity);
        }
        repository.flush();
        log.info("OpenAI Responses provider materialized from environment configuration at {} (default={})",
            baseUrl, entity.isDefault());
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
        if (entity.getProviderType() != AiProviderType.OPENCODE_SERVER
            && entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank()) {
            apiKey = crypto.decrypt(entity.getApiKeyEncrypted());
        }
        // 4A-6：providerId 与日限额随端点携带，供用量审计与预算检查
        return new AiEndpoint(entity.getId(), entity.getProviderType(), entity.getBaseUrl(), apiKey, model,
            properties.getRequestTimeout(), properties.getMaxOutputTokens(),
            entity.getDailyRequestLimit(), entity.getDailyTokenLimit(),
            properties.getOpencodeUsername(), properties.getOpencodePassword(),
            properties.getOpencodeAgent(), properties.getOpencodeProviderId());
    }

    private AiClient clientFor(AiProviderType providerType) {
        if (providerType == AiProviderType.OPENCODE_SERVER) {
            return opencodeClient;
        }
        if (providerType == AiProviderType.OPENAI_RESPONSES) {
            return responsesClient;
        }
        if (providerType == AiProviderType.ANTHROPIC) {
            return anthropicClient;
        }
        return openaiClient;
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

    private static String normalizeModels(String raw, String fallback) {
        var values = raw == null ? List.<String>of() : Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .distinct()
            .toList();
        if (!values.isEmpty()) {
            return String.join(",", values);
        }
        return hasText(fallback) ? fallback.trim() : "claude-sonnet-5";
    }

    private static String firstModel(String models) {
        return Arrays.stream(models.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .findFirst()
            .orElse("claude-sonnet-5");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
