package com.yubai.blog.admin.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.config.AiProperties;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
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

/** Client for the OpenAI Responses API and compatible relays. */
@Component
public class OpenAiResponsesClient implements AiClient {
    static final int MAX_RESPONSE_BYTES = 8_000_000;
    private static final String SYSTEM_PROMPT =
        "You are a helpful assistant. Provide concise and accurate responses.";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Function<AiEndpoint, RestClient> restClientFactory;
    private final String reasoningEffort;
    private final boolean storeResponses;

    @Autowired
    public OpenAiResponsesClient(AiProperties properties) {
        this(endpoint -> buildRestClient(endpoint, properties), properties);
    }

    OpenAiResponsesClient(Function<AiEndpoint, RestClient> restClientFactory) {
        this(restClientFactory, new AiProperties());
    }

    OpenAiResponsesClient(Function<AiEndpoint, RestClient> restClientFactory, AiProperties properties) {
        this.restClientFactory = restClientFactory;
        this.reasoningEffort = properties.getResponsesReasoningEffort();
        this.storeResponses = properties.isResponsesStore();
    }

    @Override
    public ChatResponse chat(AiEndpoint endpoint, List<ChatMessage> messages) {
        return chat(endpoint, messages, null);
    }

    @Override
    public ChatResponse chat(AiEndpoint endpoint, List<ChatMessage> messages, String reasoningEffortOverride) {
        try {
            var body = restClientFactory.apply(endpoint)
                .post()
                .uri("/responses")
                .body(buildResponseBody(endpoint, messages, false, reasoningEffortOverride))
                .retrieve()
                .toEntity(JsonNode.class)
                .getBody();
            return parseResponse(endpoint, body);
        } catch (HttpClientErrorException exception) {
            throw mapHttpClientError(exception);
        } catch (HttpServerErrorException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "AI service returned an error");
        } catch (ResourceAccessException exception) {
            throw mapResourceAccess(exception);
        } catch (RestClientException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Invalid response from AI service");
        }
    }

    @Override
    public ChatResponse stream(AiEndpoint endpoint, List<ChatMessage> messages, AiStreamListener listener) {
        return stream(endpoint, messages, listener, null);
    }

    @Override
    public ChatResponse stream(AiEndpoint endpoint, List<ChatMessage> messages,
                               AiStreamListener listener, String reasoningEffortOverride) {
        try {
            return restClientFactory.apply(endpoint)
                .post()
                .uri("/responses")
                .body(buildResponseBody(endpoint, messages, true, reasoningEffortOverride))
                .exchange((request, response) -> {
                    var status = response.getStatusCode();
                    if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                        throw new AiServiceException(HttpStatus.TOO_MANY_REQUESTS, "AI service rate limit exceeded");
                    }
                    if (status.is4xxClientError()) {
                        throw new AiServiceException(HttpStatus.BAD_GATEWAY, "AI service request failed");
                    }
                    if (status.is5xxServerError()) {
                        throw new AiServiceException(HttpStatus.BAD_GATEWAY, "AI service returned an error");
                    }
                    try {
                        return parseSseStream(response.getBody(), endpoint.model(), listener);
                    } catch (SocketTimeoutException exception) {
                        throw new AiServiceException(HttpStatus.GATEWAY_TIMEOUT, "AI service request timed out");
                    } catch (IOException exception) {
                        throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Unable to reach AI service");
                    }
                });
        } catch (AiServiceException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw mapResourceAccess(exception);
        } catch (RestClientException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Invalid response from AI service");
        }
    }

    @Override
    public List<String> listModels(AiEndpoint endpoint) {
        try {
            var body = restClientFactory.apply(endpoint)
                .get()
                .uri("/models")
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
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "AI service returned an error");
        } catch (ResourceAccessException exception) {
            throw mapResourceAccess(exception);
        } catch (RestClientException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Invalid response from AI service");
        }
    }

    private Map<String, Object> buildResponseBody(AiEndpoint endpoint, List<ChatMessage> messages,
                                                   boolean stream, String reasoningEffortOverride) {
        var input = messages.stream()
            .map(OpenAiResponsesClient::toInputItem)
            .toList();
        var body = new LinkedHashMap<String, Object>();
        body.put("model", endpoint.model());
        body.put("instructions", SYSTEM_PROMPT);
        body.put("input", input);
        body.put("stream", stream);
        body.put("max_output_tokens", endpoint.maxOutputTokens());
        var effort = hasText(reasoningEffortOverride) ? reasoningEffortOverride.trim() : reasoningEffort;
        if (hasText(effort)) {
            body.put("reasoning", Map.of("effort", effort.trim()));
        }
        body.put("store", storeResponses);
        return body;
    }

    private static Map<String, Object> toInputItem(ChatMessage message) {
        var contentType = "assistant".equals(message.role()) ? "output_text" : "input_text";
        return Map.of(
            "role", message.role(),
            "content", List.of(Map.of("type", contentType, "text", message.content())));
    }

    static ChatResponse parseResponse(AiEndpoint endpoint, JsonNode body) {
        if (body == null) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Empty response from AI service");
        }
        var content = extractText(body);
        if (content.isBlank()) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Empty response from AI service");
        }
        var model = textValue(body.get("model"));
        var usage = parseUsage(body.get("usage"));
        return new ChatResponse(content, model == null ? endpoint.model() : model, usage);
    }

    static ChatResponse parseSseStream(InputStream bodyStream, String fallbackModel,
                                       AiStreamListener listener) throws IOException {
        var reader = new BufferedReader(new InputStreamReader(bodyStream, StandardCharsets.UTF_8));
        var state = new StreamState(fallbackModel);
        var data = new StringBuilder();
        String eventName = null;
        var terminated = false;
        String line;
        while (!terminated && (line = reader.readLine()) != null) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("SSE stream cancelled");
            }
            if (line.isEmpty()) {
                terminated = flushEvent(eventName, data, state, listener);
                eventName = null;
                continue;
            }
            if (line.startsWith("event:")) {
                eventName = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                var piece = line.substring(5);
                if (piece.startsWith(" ")) {
                    piece = piece.substring(1);
                }
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(piece);
            }
        }
        if (!terminated) {
            flushEvent(eventName, data, state, listener);
        }
        if (state.content.isEmpty()) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Empty response from AI service");
        }
        var response = new ChatResponse(state.content.toString(), state.model, state.usage);
        listener.onComplete(response);
        return response;
    }

    private static boolean flushEvent(String eventName, StringBuilder data, StreamState state,
                                      AiStreamListener listener) {
        if (data.isEmpty()) {
            return false;
        }
        var payload = data.toString().trim();
        data.setLength(0);
        if (payload.isEmpty()) {
            return false;
        }
        if ("[DONE]".equals(payload)) {
            return true;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Invalid response from AI service");
        }
        var type = textValue(node.get("type"));
        if (type == null) {
            type = eventName;
        }
        if ("response.output_text.delta".equals(type)) {
            var delta = textValue(node.get("delta"));
            if (delta != null && !delta.isEmpty()) {
                state.content.append(delta);
                listener.onDelta(delta);
            }
        } else if ("response.completed".equals(type)) {
            updateStateFromResponse(node.get("response"), state);
        } else if ("response.failed".equals(type) || "response.incomplete".equals(type)) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "AI service returned an incomplete response");
        }
        return false;
    }

    private static void updateStateFromResponse(JsonNode response, StreamState state) {
        if (response == null || response.isNull()) {
            return;
        }
        var model = textValue(response.get("model"));
        if (model != null && !model.isBlank()) {
            state.model = model;
        }
        var usage = parseUsage(response.get("usage"));
        if (usage != null) {
            state.usage = usage;
        }
    }

    private static String extractText(JsonNode body) {
        var direct = textValue(body.get("output_text"));
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        var output = body.get("output");
        if (output == null || !output.isArray()) {
            return "";
        }
        var result = new StringBuilder();
        for (var item : output) {
            var content = item.get("content");
            if (content == null || !content.isArray()) {
                continue;
            }
            for (var part : content) {
                if (!"output_text".equals(textValue(part.get("type")))) {
                    continue;
                }
                var text = textValue(part.get("text"));
                if (text != null) {
                    result.append(text);
                }
            }
        }
        return result.toString();
    }

    private static ChatResponse.Usage parseUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isNull()) {
            return null;
        }
        return new ChatResponse.Usage(
            getInt(usageNode, "input_tokens", "prompt_tokens"),
            getInt(usageNode, "output_tokens", "completion_tokens"),
            getInt(usageNode, "total_tokens"));
    }

    private static int getInt(JsonNode node, String... fields) {
        for (var field : fields) {
            var value = node.get(field);
            if (value != null && value.isNumber()) {
                return value.asInt(0);
            }
        }
        return 0;
    }

    private static String textValue(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static RestClient buildRestClient(AiEndpoint endpoint, AiProperties properties) {
        var factory = new NoRedirectRequestFactory();
        var timeoutMillis = (int) Duration.ofSeconds(endpoint.requestTimeoutSeconds()).toMillis();
        factory.setConnectTimeout(timeoutMillis);
        factory.setReadTimeout(timeoutMillis);
        var builder = RestClient.builder()
            .requestFactory(factory)
            .baseUrl(endpoint.baseUrl().replaceAll("/+$", ""))
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .requestInterceptor((request, body, execution) -> capBody(execution.execute(request, body)));
        if (endpoint.apiKey() != null && !endpoint.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + endpoint.apiKey());
        }
        if (hasText(properties.getResponsesHeaderName()) && hasText(properties.getResponsesHeaderValue())) {
            builder.defaultHeader(properties.getResponsesHeaderName().trim(), properties.getResponsesHeaderValue());
        }
        return builder.build();
    }

    private static AiServiceException mapHttpClientError(HttpClientErrorException exception) {
        if (exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
            return new AiServiceException(HttpStatus.TOO_MANY_REQUESTS, "AI service rate limit exceeded");
        }
        return new AiServiceException(HttpStatus.BAD_GATEWAY, "AI service request failed");
    }

    private static AiServiceException mapResourceAccess(ResourceAccessException exception) {
        if (exception.getCause() instanceof SocketTimeoutException) {
            return new AiServiceException(HttpStatus.GATEWAY_TIMEOUT, "AI service request timed out");
        }
        return new AiServiceException(HttpStatus.BAD_GATEWAY, "Unable to reach AI service");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static final class StreamState {
        final StringBuilder content = new StringBuilder();
        String model;
        ChatResponse.Usage usage;

        StreamState(String fallbackModel) {
            this.model = fallbackModel;
        }
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
            throw new IOException("AI response exceeds size limit");
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
                throw new IOException("AI response exceeds size limit");
            }
        }
    }
}
