package com.yubai.blog.ai;

import com.yubai.blog.admin.ai.AiEndpoint;
import java.util.List;
import java.util.Set;

public record AiModelPreparedRequest(
        AiEndpoint endpoint,
        Long requestedProviderId,
        List<AiModelInputPart> parts,
        Set<AiProviderCapability> capabilities,
        String requestedModel,
        String requestedReasoningEffort,
        String requiredCapabilities,
        String routeReason) {
    public AiModelPreparedRequest(
            AiEndpoint endpoint,
            Long requestedProviderId,
            List<AiModelInputPart> parts,
            Set<AiProviderCapability> capabilities) {
        this(endpoint, requestedProviderId, parts, capabilities, null, null, null, null);
    }

    public String resolvedReasoningEffort() {
        return requestedReasoningEffort;
    }
}
