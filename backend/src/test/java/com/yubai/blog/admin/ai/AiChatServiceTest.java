package com.yubai.blog.admin.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.yubai.blog.config.AiProperties;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 原 DeepSeekChatServiceTest 的服务层用例（禁用/缺密钥 503、总长 400）迁移至此，
 * 并新增 4A-1 注册表解析路径的用例。
 */
class AiChatServiceTest {

    private static final String MASTER_KEY = "unit-test-master-key-32-characters-long!";

    private AiProperties properties;
    private AiProviderRepository repository;
    private OpenAiCompatibleClient client;
    private AnthropicClient anthropicClient;
    private OpenCodeServerClient opencodeClient;
    private AiChatService service;
    private AiCrypto crypto;
    private AiUsageService usageService;

    private void build(boolean envEnabled, String envKey, String masterKey) {
        properties = new AiProperties();
        properties.setEnabled(envEnabled);
        properties.setApiKey(envKey);
        properties.setBaseUrl("https://api.deepseek.com");
        properties.setModel("deepseek-v4-flash");
        properties.setMaxTotalChars(40000);
        properties.setMaxOutputTokens(2048);
        properties.setMasterKey(masterKey);
        repository = mock(AiProviderRepository.class);
        client = mock(OpenAiCompatibleClient.class);
        anthropicClient = mock(AnthropicClient.class);
        opencodeClient = mock(OpenCodeServerClient.class);
        crypto = new AiCrypto(properties);
        var providerService = new AiProviderService(
            repository, crypto, new AiBaseUrlValidator(properties), client, anthropicClient,
            opencodeClient, properties);
        // 4A-6：用量服务以 mock 注入——预算/审计逻辑由 AiUsageServiceTest 独立覆盖
        usageService = mock(AiUsageService.class);
        service = new AiChatService(properties, providerService, client, anthropicClient,
            opencodeClient, usageService);
    }

    private static ChatRequest request(String content) {
        return new ChatRequest(List.of(new ChatMessage("user", content)));
    }

    @Test
    void disabledServiceThrows503() {
        build(false, "test-key", null);
        var e = assertThrows(AiServiceException.class, () -> service.chat(request("hello")));
        assertEquals(503, e.getStatus().value());
    }

    @Test
    void nullApiKeyThrows503() {
        build(true, null, null);
        var e = assertThrows(AiServiceException.class, () -> service.chat(request("hello")));
        assertEquals(503, e.getStatus().value());
    }

    @Test
    void blankApiKeyThrows503() {
        build(true, "  ", null);
        var e = assertThrows(AiServiceException.class, () -> service.chat(request("hello")));
        assertEquals(503, e.getStatus().value());
    }

    @Test
    void aggregateContentExceedsMaxThrows400() {
        build(true, "test-key", null);
        properties.setMaxInputChars(30000);
        var content = "x".repeat(20001);
        var overLimit = new ChatRequest(List.of(
            new ChatMessage("user", content), new ChatMessage("assistant", content)));
        var e = assertThrows(AiServiceException.class, () -> service.chat(overLimit));
        assertEquals(400, e.getStatus().value());
        verify(client, never()).chat(any(), anyList());
    }

    @Test
    void tooManyMessagesThrows400() {
        // 限额校验收敛到服务层：绕过控制器的调用方同样不能失守
        build(true, "test-key", null);
        properties.setMaxHistoryMessages(3);
        var overLimit = new ChatRequest(List.of(
            new ChatMessage("user", "a"), new ChatMessage("assistant", "b"),
            new ChatMessage("user", "c"), new ChatMessage("assistant", "d")));
        var e = assertThrows(AiServiceException.class, () -> service.chat(overLimit));
        assertEquals(400, e.getStatus().value());
        verify(client, never()).chat(any(), anyList());
    }

    @Test
    void singleMessageTooLongThrows400() {
        build(true, "test-key", null);
        var e = assertThrows(AiServiceException.class, () -> service.chat(request("x".repeat(8001))));
        assertEquals(400, e.getStatus().value());
        verify(client, never()).chat(any(), anyList());
    }

    @Test
    void streamValidatesLimitsToo() {
        build(true, "test-key", null);
        var e = assertThrows(AiServiceException.class,
            () -> service.stream(request("x".repeat(8001)), content -> { }));
        assertEquals(400, e.getStatus().value());
        verify(client, never()).stream(any(), anyList(), any());
    }

    @Test
    void legacyEnvFallbackUsedWhenRegistryEmpty() {
        build(true, "env-key", null);
        when(client.chat(any(), anyList())).thenReturn(new ChatResponse("ok", "deepseek-v4-flash", null));

        var response = service.chat(request("hello"));

        assertEquals("ok", response.content());
        var captor = ArgumentCaptor.forClass(AiEndpoint.class);
        verify(client).chat(captor.capture(), anyList());
        assertEquals("https://api.deepseek.com", captor.getValue().baseUrl());
        assertEquals("env-key", captor.getValue().apiKey());
        assertEquals("deepseek-v4-flash", captor.getValue().model());
    }

    @Test
    void registryProviderPreferredOverEnvAndKeyDecrypted() {
        build(true, "env-key", MASTER_KEY);
        var entity = AiProviderEntity.create("kimi", "https://api.moonshot.cn/v1",
            crypto.encrypt("sk-registry-key"), "kimi-k2", "kimi-k2", true, 200, 200_000, AiProviderType.OPENAI_COMPATIBLE);
        entity.markDefault(true);
        when(repository.findFirstByIsDefaultTrueAndEnabledTrue()).thenReturn(Optional.of(entity));
        when(client.chat(any(), anyList())).thenReturn(new ChatResponse("ok", "kimi-k2", null));

        service.chat(request("hello"));

        var captor = ArgumentCaptor.forClass(AiEndpoint.class);
        verify(client).chat(captor.capture(), anyList());
        assertEquals("https://api.moonshot.cn/v1", captor.getValue().baseUrl());
        assertEquals("sk-registry-key", captor.getValue().apiKey());
        assertEquals("kimi-k2", captor.getValue().model());
    }

    @Test
    void registryAnthropicProviderUsesNativeClient() {
        build(false, null, MASTER_KEY);
        var entity = AiProviderEntity.create("claude", "https://api.anthropic.com",
            crypto.encrypt("sk-ant-registry-key"), "claude-sonnet-4-20250514",
            "claude-sonnet-4-20250514", true, 200, 200_000, AiProviderType.ANTHROPIC);
        entity.markDefault(true);
        when(repository.findFirstByIsDefaultTrueAndEnabledTrue()).thenReturn(Optional.of(entity));
        when(anthropicClient.chat(any(), anyList()))
            .thenReturn(new ChatResponse("ok", "claude-sonnet-4-20250514", null));

        var response = service.chat(request("hello"));

        assertEquals("ok", response.content());
        var captor = ArgumentCaptor.forClass(AiEndpoint.class);
        verify(anthropicClient).chat(captor.capture(), anyList());
        verifyNoInteractions(client);
        assertEquals(AiProviderType.ANTHROPIC, captor.getValue().providerType());
        assertEquals("sk-ant-registry-key", captor.getValue().apiKey());
    }

    @Test
    void explicitProviderIdResolvedWithRequestedModel() {
        build(false, null, MASTER_KEY);
        var entity = AiProviderEntity.create("glm", "https://open.bigmodel.cn/api/paas/v4",
            crypto.encrypt("sk-glm"), "glm-4-flash,glm-4-plus", "glm-4-flash", true, 200, 200_000, AiProviderType.OPENAI_COMPATIBLE);
        when(repository.findById(7L)).thenReturn(Optional.of(entity));
        when(client.chat(any(), anyList())).thenReturn(new ChatResponse("ok", "glm-4-plus", null));

        service.chat(new ChatRequest(List.of(new ChatMessage("user", "hi")), 7L, "glm-4-plus"));

        var captor = ArgumentCaptor.forClass(AiEndpoint.class);
        verify(client).chat(captor.capture(), anyList());
        assertEquals("glm-4-plus", captor.getValue().model());
    }

    @Test
    void modelOutsideAllowListRejected() {
        build(false, null, MASTER_KEY);
        var entity = AiProviderEntity.create("glm", "https://open.bigmodel.cn/api/paas/v4",
            crypto.encrypt("sk-glm"), "glm-4-flash", "glm-4-flash", true, 200, 200_000, AiProviderType.OPENAI_COMPATIBLE);
        when(repository.findById(7L)).thenReturn(Optional.of(entity));

        var e = assertThrows(AiServiceException.class,
            () -> service.chat(new ChatRequest(List.of(new ChatMessage("user", "hi")), 7L, "gpt-4o")));
        assertEquals(400, e.getStatus().value());
        verify(client, never()).chat(any(), anyList());
    }

    @Test
    void unknownOrDisabledProviderIdRejected() {
        build(false, null, MASTER_KEY);
        when(repository.findById(99L)).thenReturn(Optional.empty());
        var e = assertThrows(AiServiceException.class,
            () -> service.chat(new ChatRequest(List.of(new ChatMessage("user", "hi")), 99L, null)));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void registryKeyWithoutMasterKeyFails503() {
        build(false, null, MASTER_KEY);
        var encrypted = crypto.encrypt("sk-x");
        var entity = AiProviderEntity.create("p", "https://api.deepseek.com",
            encrypted, "m", "m", true, 200, 200_000, AiProviderType.OPENAI_COMPATIBLE);
        entity.markDefault(true);
        // 重新构建为无主密钥环境：DB 里有密文但无法解密 → 503 而非明文降级
        build(false, null, null);
        when(repository.findFirstByIsDefaultTrueAndEnabledTrue()).thenReturn(Optional.of(entity));
        var e = assertThrows(AiServiceException.class, () -> service.chat(request("hello")));
        assertEquals(503, e.getStatus().value());
    }
}
