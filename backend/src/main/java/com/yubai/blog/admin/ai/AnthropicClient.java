package com.yubai.blog.admin.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Native Anthropic Messages API client (non-OpenAI-compatible). */
@Component
public class AnthropicClient implements AiClient {
    static final String ANTHROPIC_VERSION = "2023-06-01";
    static final int MAX_RESPONSE_BYTES = 8_000_000;
    private static final String SYSTEM_PROMPT =
        "You are a helpful assistant. Provide concise and accurate responses.";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Function<AiEndpoint, RestClient> restClientFactory;

    @Autowired
    public AnthropicClient() {
        this.restClientFactory = AnthropicClient::buildRestClient;
    }

    AnthropicClient(Function<AiEndpoint, RestClient> restClientFactory) {
        this.restClientFactory = restClientFactory;
    }

    @Override
    public ChatResponse chat(AiEndpoint endpoint, List<ChatMessage> messages) {
        return chat(endpoint, messages, null);
    }

    @Override
    public ChatResponse chat(AiEndpoint endpoint, List<ChatMessage> messages, String reasoningEffort) {
        try {
            var body = restClientFactory.apply(endpoint)
                .post()
                .uri(apiUri(endpoint, "messages"))
                .body(buildMessageBody(endpoint, messages, false, reasoningEffort))
                .retrieve()
                .toEntity(JsonNode.class)
                .getBody();
            return parseChatResponse(endpoint, body);
        } catch (HttpClientErrorException exception) {
            throw mapHttpClientError(exception);
        } catch (HttpServerErrorException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Anthropic service returned an error");
        } catch (ResourceAccessException exception) {
            throw mapResourceAccess(exception);
        } catch (RestClientException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Invalid response from Anthropic service");
        }
    }

    @Override
    public ChatResponse stream(AiEndpoint endpoint, List<ChatMessage> messages, AiStreamListener listener) {
        return stream(endpoint, messages, listener, null);
    }

    @Override
    public ChatResponse stream(AiEndpoint endpoint, List<ChatMessage> messages,
                               AiStreamListener listener, String reasoningEffort) {
        try {
            return restClientFactory.apply(endpoint)
                .post()
                .uri(apiUri(endpoint, "messages"))
                .body(buildMessageBody(endpoint, messages, true, reasoningEffort))
                .exchange((request, response) -> {
                    var status = response.getStatusCode();
                    if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                        throw new AiServiceException(HttpStatus.TOO_MANY_REQUESTS,
                            "Anthropic service rate limit exceeded");
                    }
                    if (status.is4xxClientError()) {
                        throw new AiServiceException(HttpStatus.BAD_GATEWAY,
                            "Anthropic service request failed");
                    }
                    if (status.is5xxServerError()) {
                        throw new AiServiceException(HttpStatus.BAD_GATEWAY,
                            "Anthropic service returned an error");
                    }
                    try {
                        return parseSseStream(response.getBody(), endpoint.model(), listener);
                    } catch (SocketTimeoutException exception) {
                        throw new AiServiceException(HttpStatus.GATEWAY_TIMEOUT,
                            "Anthropic service request timed out");
                    } catch (IOException exception) {
                        throw new AiServiceException(HttpStatus.BAD_GATEWAY,
                            "Unable to reach Anthropic service");
                    }
                });
        } catch (AiServiceException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw mapResourceAccess(exception);
        } catch (RestClientException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY,
                "Invalid response from Anthropic service");
        }
    }

    /** Parse Anthropic SSE events. Package-private for focused unit tests. */
    static ChatResponse parseSseStream(InputStream bodyStream, String fallbackModel,
                                       AiStreamListener listener) throws IOException {
        var reader = new BufferedReader(new InputStreamReader(bodyStream, StandardCharsets.UTF_8));
        var state = new StreamState(fallbackModel);
        var eventName = new StringBuilder();
        var data = new StringBuilder();
        var terminated = false;
        String line;
        while (!terminated && (line = reader.readLine()) != null) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("SSE stream cancelled");
            }
            if (line.isEmpty()) {
                terminated = flushEvent(eventName, data, state, listener);
                continue;
            }
            if (line.startsWith("event:")) {
                var value = line.substring(6);
                eventName.setLength(0);
                eventName.append(value.startsWith(" ") ? value.substring(1) : value);
                continue;
            }
            if (line.startsWith("data:")) {
                var value = line.substring(5);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(value);
            }
        }
        if (!terminated) {
            flushEvent(eventName, data, state, listener);
        }
        if (state.content.isEmpty()) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY,
                "Empty response from Anthropic service");
        }
        var response = new ChatResponse(state.content.toString(), state.model, state.usage());
        listener.onComplete(response);
        return response;
    }

    private static boolean flushEvent(StringBuilder eventName, StringBuilder data,
                                      StreamState state, AiStreamListener listener) {
        if (data.isEmpty()) {
            eventName.setLength(0);
            return false;
        }
        var payload = data.toString().trim();
        data.setLength(0);
        var event = eventName.toString().trim();
        eventName.setLength(0);
        if (payload.isEmpty()) {
            return false;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY,
                "Invalid response from Anthropic service");
        }
        if (event.isEmpty()) {
            event = text(node, "type");
        }
        if (event == null) {
            event = "";
        }
        if ("error".equals(event)) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY,
                "Anthropic service returned an error");
        }
        switch (event) {
            case "message_start" -> {
                var message = node.get("message");
                if (message != null && message.isObject()) {
                    var model = message.get("model");
                    if (model != null && !model.isNull() && !model.asText().isBlank()) {
                        state.model = model.asText();
                    }
                    var usage = message.get("usage");
                    if (usage != null && usage.isObject()) {
                        state.hasUsage = true;
                        state.inputTokens = getInt(usage, "input_tokens");
                        state.outputTokens = getInt(usage, "output_tokens");
                    }
                }
            }
            case "content_block_delta" -> {
                var delta = node.get("delta");
                if (delta != null && "text_delta".equals(text(delta, "type"))) {
                    var value = text(delta, "text");
                    if (value != null && !value.isEmpty()) {
                        state.content.append(value);
                        listener.onDelta(value);
                    }
                }
            }
            case "message_delta" -> {
                var usage = node.get("usage");
                if (usage != null && usage.isObject()) {
                    state.hasUsage = true;
                    state.outputTokens = getInt(usage, "output_tokens");
                    if (usage.has("input_tokens")) {
                        state.inputTokens = getInt(usage, "input_tokens");
                    }
                }
            }
            case "message_stop", "content_block_start", "content_block_stop", "ping" -> {
                // No text is emitted by these events.
            }
            default -> {
                // Unknown events are ignored for forward compatibility.
            }
        }
        return "message_stop".equals(event);
    }

    private static final class StreamState {
        final StringBuilder content = new StringBuilder();
        String model;
        int inputTokens;
        int outputTokens;
        boolean hasUsage;

        StreamState(String fallbackModel) {
            this.model = fallbackModel;
        }

        ChatResponse.Usage usage() {
            return inputTokens > 0 || outputTokens > 0 || hasUsage
                ? new ChatResponse.Usage(inputTokens, outputTokens, inputTokens + outputTokens)
                : null;
        }
    }

    @Override
    public List<String> listModels(AiEndpoint endpoint) {
        try {
            var body = restClientFactory.apply(endpoint)
                .get()
                .uri(apiUri(endpoint, "models"))
                .retrieve()
                .toEntity(JsonNode.class)
                .getBody();
            var models = new ArrayList<String>();
            if (body != null && body.get("data") != null && body.get("data").isArray()) {
                for (var item : body.get("data")) {
                    var id = item.get("id");
                    if (id != null && !id.isNull() && models.size() < 50) {
                        models.add(id.asText());
                    }
                }
            }
            return models;
        } catch (HttpClientErrorException exception) {
            throw mapHttpClientError(exception);
        } catch (HttpServerErrorException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Anthropic service returned an error");
        } catch (ResourceAccessException exception) {
            throw mapResourceAccess(exception);
        } catch (RestClientException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY,
                "Invalid response from Anthropic service");
        }
    }

    static URI apiUri(AiEndpoint endpoint, String resource) {
        var base = endpoint.baseUrl().replaceAll("/+$", "");
        var baseUri = URI.create(base);
        var path = baseUri.getPath();
        var suffix = path != null && (path.equals("/v1") || path.endsWith("/v1"))
            ? "/" + resource
            : "/v1/" + resource;
        return URI.create(base + suffix);
    }

    private static Map<String, Object> buildMessageBody(AiEndpoint endpoint,
                                                         List<ChatMessage> messages,
                                                         boolean stream,
                                                         String reasoningEffort) {
        var payload = new ArrayList<Map<String, String>>();
        for (var message : messages) {
            payload.add(Map.of("role", message.role(), "content", message.content()));
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("model", endpoint.model());
        body.put("system", SYSTEM_PROMPT);
        body.put("messages", payload);
        body.put("max_tokens", endpoint.maxOutputTokens());
        body.put("stream", stream);
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            if ("none".equals(reasoningEffort)) {
                body.put("thinking", Map.of("type", "disabled"));
            } else {
                var budgetTokens = reasoningBudgetTokens(reasoningEffort, endpoint.maxOutputTokens());
                body.put("thinking", Map.of("type", "enabled", "budget_tokens", budgetTokens));
                // Anthropic requires max_tokens to be greater than the
                // thinking budget, so preserve the configured output cap when
                // possible and raise it only enough to fit the requested tier.
                body.put("max_tokens", Math.max(endpoint.maxOutputTokens(), budgetTokens + 1));
            }
        }
        return body;
    }

    private static int reasoningBudgetTokens(String reasoningEffort, int maxOutputTokens) {
        var cap = Math.max(2, maxOutputTokens);
        return switch (reasoningEffort) {
            case "minimal" -> Math.max(1, cap / 8);
            case "low" -> Math.max(1, cap / 4);
            case "medium" -> Math.max(1, cap / 2);
            case "high" -> Math.max(1, cap - cap / 8);
            case "xhigh" -> cap - 1;
            default -> throw new AiServiceException(HttpStatus.BAD_REQUEST, "Invalid reasoning effort");
        };
    }

    private static ChatResponse parseChatResponse(AiEndpoint endpoint, JsonNode body) {
        if (body == null) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY,
                "Empty response from Anthropic service");
        }
        var content = new StringBuilder();
        var blocks = body.get("content");
        if (blocks != null && blocks.isArray()) {
            for (var block : blocks) {
                if ("text".equals(text(block, "type"))) {
                    var value = text(block, "text");
                    if (value != null) {
                        content.append(value);
                    }
                }
            }
        }
        if (content.isEmpty() || content.toString().isBlank()) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY,
                "Empty response from Anthropic service");
        }
        var model = text(body, "model");
        var usageNode = body.get("usage");
        ChatResponse.Usage usage = null;
        if (usageNode != null && usageNode.isObject()) {
            var input = getInt(usageNode, "input_tokens");
            var output = getInt(usageNode, "output_tokens");
            usage = new ChatResponse.Usage(input, output, input + output);
        }
        return new ChatResponse(content.toString(),
            model == null || model.isBlank() ? endpoint.model() : model, usage);
    }

    private static String text(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static int getInt(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || value.isNull() ? 0 : value.asInt(0);
    }

    private static AiServiceException mapHttpClientError(HttpClientErrorException exception) {
        if (exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
            return new AiServiceException(HttpStatus.TOO_MANY_REQUESTS,
                "Anthropic service rate limit exceeded");
        }
        return new AiServiceException(HttpStatus.BAD_GATEWAY,
            "Anthropic service request failed");
    }

    private static AiServiceException mapResourceAccess(ResourceAccessException exception) {
        if (exception.getCause() instanceof SocketTimeoutException) {
            return new AiServiceException(HttpStatus.GATEWAY_TIMEOUT,
                "Anthropic service request timed out");
        }
        return new AiServiceException(HttpStatus.BAD_GATEWAY,
            "Unable to reach Anthropic service");
    }

    private static RestClient buildRestClient(AiEndpoint endpoint) {
        var factory = new NoRedirectRequestFactory();
        var timeoutMillis = (int) Duration.ofSeconds(endpoint.requestTimeoutSeconds()).toMillis();
        factory.setConnectTimeout(timeoutMillis);
        factory.setReadTimeout(timeoutMillis);
        var builder = RestClient.builder()
            .requestFactory(factory)
            .baseUrl(endpoint.baseUrl().replaceAll("/+$", ""))
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
            .requestInterceptor((request, body, execution) -> capBody(execution.execute(request, body)));
        if (endpoint.apiKey() != null && !endpoint.apiKey().isBlank()) {
            builder.defaultHeader("x-api-key", endpoint.apiKey());
        }
        return builder.build();
    }

    private static final class NoRedirectRequestFactory extends SimpleClientHttpRequestFactory {
        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
            super.prepareConnection(connection, httpMethod);
            connection.setInstanceFollowRedirects(false);
        }
    }

    private static ClientHttpResponse capBody(ClientHttpResponse response) throws IOException {
        var declaredLength = response.getHeaders().getContentLength();
        if (declaredLength > MAX_RESPONSE_BYTES) {
            response.close();
            throw new IOException("Anthropic response exceeds size limit");
        }
        return new ClientHttpResponse() {
            @Override
            public InputStream getBody() throws IOException {
                return new BoundedInputStream(response.getBody(), MAX_RESPONSE_BYTES);
            }

            @Override
            public HttpHeaders getHeaders() {
                return response.getHeaders();
            }

            @Override
            public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
                return response.getStatusCode();
            }

            @Override
            public String getStatusText() throws IOException {
                return response.getStatusText();
            }

            @Override
            public void close() {
                response.close();
            }
        };
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long limit;
        private long consumed;

        BoundedInputStream(InputStream delegate, long limit) {
            super(delegate);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            var value = super.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            var bytesRead = super.read(buffer, offset, length);
            if (bytesRead > 0) {
                count(bytesRead);
            }
            return bytesRead;
        }

        private void count(long bytesRead) throws IOException {
            consumed += bytesRead;
            if (consumed > limit) {
                throw new IOException("Anthropic response exceeds size limit");
            }
        }
    }
}
