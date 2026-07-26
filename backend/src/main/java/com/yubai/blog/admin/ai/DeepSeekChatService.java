package com.yubai.blog.admin.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.yubai.blog.config.AiProperties;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class DeepSeekChatService {
    private final AiProperties properties;
    private final RestClient restClient;

    @Autowired
    public DeepSeekChatService(AiProperties properties) {
        this.properties = properties;
        this.restClient = buildRestClient();
    }

    DeepSeekChatService(AiProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @SuppressWarnings("deprecation")
    private RestClient buildRestClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout((int) Duration.ofSeconds(properties.getRequestTimeout()).toMillis());
        factory.setConnectTimeout((int) Duration.ofSeconds(properties.getRequestTimeout()).toMillis());
        var baseUrl = properties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        return RestClient.builder()
            .requestFactory(factory)
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
            .build();
    }

    public ChatResponse chat(ChatRequest request) {
        if (!properties.isEnabled() || properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, "AI service is not configured");
        }

        var totalChars = request.messages().stream().mapToInt(m -> m.content().length()).sum();
        if (totalChars > properties.getMaxTotalChars()) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST,
                "Total content length exceeds maximum of " + properties.getMaxTotalChars());
        }

        try {
            var responseEntity = restClient.post()
                .body(buildDeepSeekRequest(request))
                .retrieve()
                .toEntity(JsonNode.class);
            return parseResponse(responseEntity.getBody());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new AiServiceException(HttpStatus.TOO_MANY_REQUESTS, "AI service rate limit exceeded");
            }
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "AI service request failed");
        } catch (HttpServerErrorException e) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "AI service returned an error");
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                throw new AiServiceException(HttpStatus.GATEWAY_TIMEOUT, "AI service request timed out");
            }
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Unable to reach AI service");
        } catch (RestClientException e) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Invalid response from AI service");
        }
    }

    private Map<String, Object> buildDeepSeekRequest(ChatRequest request) {
        var messages = new ArrayList<Map<String, String>>();
        messages.add(Map.of("role", "system", "content", "You are a helpful assistant. Provide concise and accurate responses."));
        for (var msg : request.messages()) {
            messages.add(Map.of("role", msg.role(), "content", msg.content()));
        }
        return Map.of(
            "model", properties.getModel(),
            "messages", messages,
            "thinking", Map.of("type", "disabled"),
            "tool_choice", "none",
            "stream", false,
            "max_tokens", properties.getMaxOutputTokens()
        );
    }

    private ChatResponse parseResponse(JsonNode body) {
        if (body == null) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Empty response from AI service");
        }
        var choices = body.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Invalid response from AI service");
        }
        var choice = choices.get(0);
        var message = choice.get("message");
        if (message == null) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Invalid response from AI service");
        }
        var content = message.get("content");
        if (content == null || content.isNull() || content.asText().isBlank()) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Empty response from AI service");
        }
        var modelNode = body.get("model");
        var usageNode = body.get("usage");
        ChatResponse.Usage usage = null;
        if (usageNode != null && !usageNode.isNull()) {
            var promptTokens = getInt(usageNode, "prompt_tokens");
            var completionTokens = getInt(usageNode, "completion_tokens");
            var totalTokens = getInt(usageNode, "total_tokens");
            usage = new ChatResponse.Usage(promptTokens, completionTokens, totalTokens);
        }
        return new ChatResponse(
            content.asText(),
            modelNode != null ? modelNode.asText() : properties.getModel(),
            usage
        );
    }

    private static int getInt(JsonNode node, String field) {
        var value = node.get(field);
        return value != null ? value.asInt(0) : 0;
    }
}
