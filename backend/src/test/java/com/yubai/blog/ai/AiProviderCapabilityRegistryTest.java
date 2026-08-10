package com.yubai.blog.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.yubai.blog.admin.ai.AiProviderType;
import org.junit.jupiter.api.Test;

class AiProviderCapabilityRegistryTest {
    private final AiProviderCapabilityRegistry registry = new AiProviderCapabilityRegistry();

    @Test
    void capabilitiesAreExplicitByProtocolAndNeverGuessedFromModelNames() {
        assertThat(registry.capabilities(AiProviderType.OPENAI_RESPONSES))
                .contains(
                        AiProviderCapability.TEXT,
                        AiProviderCapability.VISION,
                        AiProviderCapability.FILE_INPUT);
        assertThat(registry.capabilities(AiProviderType.OPENAI_COMPATIBLE))
                .containsExactly(AiProviderCapability.TEXT);
        assertThat(registry.capabilities(AiProviderType.ANTHROPIC))
                .containsExactly(AiProviderCapability.TEXT);
    }
}
