package com.yubai.blog.admin.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.yubai.blog.config.AiProperties;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiProviderServiceTest {

    private static final String MASTER_KEY = "unit-test-master-key-32-characters-long!";

    private AiProperties properties;
    private AiProviderRepository repository;
    private OpenAiCompatibleClient client;
    private AiProviderService service;

    private void build(String masterKey, boolean allowLocal, boolean envEnabled, String envKey) {
        properties = new AiProperties();
        properties.setMasterKey(masterKey);
        properties.setAllowLocalEndpoints(allowLocal);
        properties.setEnabled(envEnabled);
        properties.setApiKey(envKey);
        repository = mock(AiProviderRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        client = mock(OpenAiCompatibleClient.class);
        service = new AiProviderService(repository, new AiCrypto(properties),
            new AiBaseUrlValidator(properties), client, properties);
    }

    private static AiProviderRequest request(String name, String baseUrl, String apiKey) {
        return new AiProviderRequest(name, baseUrl, apiKey,
            List.of("model-a", "model-b"), "model-a", true, null, null);
    }

    @Test
    void createEncryptsKeyAndNeverEchoesPlaintext() {
        build(MASTER_KEY, false, false, null);
        when(repository.count()).thenReturn(0L);

        var response = service.create(request("deepseek", "https://93.184.216.34/v1", "sk-secret-key-9123"));

        var captor = ArgumentCaptor.forClass(AiProviderEntity.class);
        verify(repository).save(captor.capture());
        var stored = captor.getValue().getApiKeyEncrypted();
        assertNotNull(stored);
        assertTrue(stored.startsWith("v1:"));
        assertFalse(stored.contains("sk-secret-key-9123"));
        assertTrue(response.hasKey());
        assertEquals("9123", response.keyTail());
        assertTrue(response.isDefault());
    }

    @Test
    void duplicateNameRejectedWith409() {
        build(MASTER_KEY, false, false, null);
        when(repository.existsByNameIgnoreCase("deepseek")).thenReturn(true);
        var e = assertThrows(AiServiceException.class,
            () -> service.create(request("deepseek", "https://93.184.216.34", "sk")));
        assertEquals(409, e.getStatus().value());
    }

    @Test
    void createWithKeyButNoMasterKeyFails503() {
        build(null, false, false, null);
        var e = assertThrows(AiServiceException.class,
            () -> service.create(request("p", "https://93.184.216.34", "sk-x")));
        assertEquals(503, e.getStatus().value());
    }

    @Test
    void keylessProviderAllowedWithoutMasterKey() {
        build(null, true, false, null);
        when(repository.count()).thenReturn(0L);
        var response = service.create(request("ollama", "http://127.0.0.1:11434/v1", null));
        assertFalse(response.hasKey());
        assertNull(response.keyTail());
    }

    @Test
    void createRejectsPrivateBaseUrlWithoutLocalFlag() {
        build(MASTER_KEY, false, false, null);
        var e = assertThrows(AiServiceException.class,
            () -> service.create(request("ollama", "http://127.0.0.1:11434", null)));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void updateWithBlankKeyKeepsExistingSecret() {
        build(MASTER_KEY, false, false, null);
        var entity = AiProviderEntity.create("p", "https://93.184.216.34", "v1:existing-cipher",
            "model-a", "model-a", true, 200, 200_000);
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        service.update(1L, new AiProviderRequest("p", "https://93.184.216.34", "  ",
            List.of("model-a"), "model-a", true, null, null));

        assertEquals("v1:existing-cipher", entity.getApiKeyEncrypted());
    }

    @Test
    void setDefaultRejectsDisabledProvider() {
        build(MASTER_KEY, false, false, null);
        var entity = AiProviderEntity.create("p", "https://93.184.216.34", null,
            "", "model-a", false, 200, 200_000);
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        var e = assertThrows(AiServiceException.class, () -> service.setDefault(1L));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void deletingDefaultPromotesNextEnabledProvider() {
        build(MASTER_KEY, false, false, null);
        var deleted = AiProviderEntity.create("a", "https://93.184.216.34", null, "", "m", true, 200, 200_000);
        deleted.markDefault(true);
        var next = AiProviderEntity.create("b", "https://93.184.216.34", null, "", "m", true, 200, 200_000);
        when(repository.findById(1L)).thenReturn(Optional.of(deleted));
        when(repository.findFirstByEnabledTrueOrderByIdAsc()).thenReturn(Optional.of(next));

        service.delete(1L);

        verify(repository).delete(deleted);
        assertTrue(next.isDefault());
    }

    @Test
    void seedFromLegacyEnvEncryptsAndMarksDefault() {
        build(MASTER_KEY, false, true, "sk-legacy-env-key");
        when(repository.count()).thenReturn(0L);

        service.seedFromLegacyEnv();

        var captor = ArgumentCaptor.forClass(AiProviderEntity.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals("deepseek", saved.getName());
        assertTrue(saved.isDefault());
        assertTrue(saved.getApiKeyEncrypted().startsWith("v1:"));
        assertFalse(saved.getApiKeyEncrypted().contains("sk-legacy-env-key"));
    }

    @Test
    void seedSkippedWithoutMasterKeyOrWhenRegistryNotEmpty() {
        build(null, false, true, "sk-legacy-env-key");
        service.seedFromLegacyEnv();
        verify(repository, never()).save(any());

        build(MASTER_KEY, false, true, "sk-legacy-env-key");
        when(repository.count()).thenReturn(3L);
        service.seedFromLegacyEnv();
        verify(repository, never()).save(any());
    }

    @Test
    void testConnectionReturnsFailureAsResultInsteadOfThrowing() {
        build(MASTER_KEY, false, false, null);
        var entity = AiProviderEntity.create("p", "https://93.184.216.34", null, "", "m", true, 200, 200_000);
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        when(client.listModels(any())).thenThrow(
            new AiServiceException(org.springframework.http.HttpStatus.BAD_GATEWAY, "Unable to reach AI service"));

        var result = service.testConnection(1L);

        assertFalse(result.ok());
        assertEquals("Unable to reach AI service", result.message());
    }
}
