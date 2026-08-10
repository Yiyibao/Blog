package com.yubai.blog.ai;

import com.yubai.blog.admin.ai.AiProviderType;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AiProviderCapabilityRegistry {
    public Set<AiProviderCapability> capabilities(AiProviderType providerType) {
        if (providerType == AiProviderType.OPENAI_RESPONSES) {
            return Set.copyOf(
                    EnumSet.of(
                            AiProviderCapability.TEXT,
                            AiProviderCapability.VISION,
                            AiProviderCapability.FILE_INPUT,
                            AiProviderCapability.STRUCTURED_OUTPUT));
        }
        return Set.of(AiProviderCapability.TEXT);
    }
}
