package com.yubai.blog.ai;

import com.yubai.blog.admin.ai.AiEndpoint;
import com.yubai.blog.admin.ai.AiProviderModelRepository;
import com.yubai.blog.admin.ai.AiProviderType;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AiProviderCapabilityRegistry {
    private final AiProviderModelRepository modelRepository;

    public AiProviderCapabilityRegistry() {
        this.modelRepository = null;
    }

    @Autowired
    public AiProviderCapabilityRegistry(AiProviderModelRepository modelRepository) {
        this.modelRepository = modelRepository;
    }

    public Set<AiProviderCapability> capabilities(AiEndpoint endpoint) {
        if (modelRepository != null && endpoint.providerId() != null) {
            var row =
                    modelRepository.findByProviderIdAndModelAndEnabledTrue(
                            endpoint.providerId(), endpoint.model());
            if (row.isPresent()) return row.get().capabilities();
        }
        return capabilities(endpoint.providerType());
    }

    public Set<String> reasoningEfforts(AiEndpoint endpoint) {
        if (modelRepository != null && endpoint.providerId() != null) {
            var row =
                    modelRepository.findByProviderIdAndModelAndEnabledTrue(
                            endpoint.providerId(), endpoint.model());
            if (row.isPresent()) return row.get().reasoningEfforts();
        }
        return endpoint.providerType() == AiProviderType.OPENAI_RESPONSES
                ? Set.of("none", "low", "medium", "high", "xhigh", "max")
                : Set.of("none");
    }

    public Set<AiProviderCapability> capabilities(AiProviderType providerType) {
        if (providerType == AiProviderType.OPENAI_RESPONSES) {
            return Set.copyOf(
                    EnumSet.of(
                            AiProviderCapability.TEXT,
                            AiProviderCapability.VISION,
                            AiProviderCapability.FILE_INPUT,
                            AiProviderCapability.STRUCTURED_OUTPUT,
                            AiProviderCapability.REASONING,
                            AiProviderCapability.TOOL_CALLING,
                            AiProviderCapability.IMAGE_GENERATION));
        }
        return Set.of(AiProviderCapability.TEXT);
    }
}
