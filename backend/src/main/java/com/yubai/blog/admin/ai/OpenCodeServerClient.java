package com.yubai.blog.admin.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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

@Component
public class OpenCodeServerClient implements AiClient {

    static final int MAX_RESPONSE_BYTES = 8_000_000;
    private static final String SYSTEM_PROMPT =
        "Answer the user's current request directly and completely in this response. "
            + "Do not announce plans, future work, connection-test progress, or ask the user to start another run. "
            + "Provide concise and accurate responses.";
    private static final String MAX_STEPS_MARKER = "Maximum steps for this agent have been reached.";

    private final Function<AiEndpoint, RestClient> restClientFactory;

    public OpenCodeServerClient() {
        this.restClientFactory = OpenCodeServerClient::buildRestClient;
    }

    OpenCodeServerClient(Function<AiEndpoint, RestClient> restClientFactory) {
        this.restClientFactory = restClientFactory;
    }

    public ChatResponse chat(AiEndpoint endpoint, List<ChatMessage> messages) {
        validateCredentials(endpoint);
        var client = restClientFactory.apply(endpoint);
        var sessionId = createSession(client);
        try {
            return sendMessage(client, endpoint, sessionId, messages);
        } finally {
            deleteSessionQuietly(client, sessionId);
        }
    }

    public ChatResponse stream(AiEndpoint endpoint, List<ChatMessage> messages, AiStreamListener listener) {
        validateCredentials(endpoint);
        var client = restClientFactory.apply(endpoint);
        var sessionId = createSession(client);
        try {
            var response = sendMessage(client, endpoint, sessionId, messages);
            listener.onDelta(response.content());
            listener.onComplete(response);
            return response;
        } finally {
            if (Thread.currentThread().isInterrupted()) {
                abortSessionQuietly(client, sessionId);
            }
            deleteSessionQuietly(client, sessionId);
        }
    }

    public List<String> listModels(AiEndpoint endpoint) {
        validateCredentials(endpoint);
        var client = restClientFactory.apply(endpoint);
        try {
            var body = client.get()
                .uri("/provider")
                .retrieve()
                .toEntity(JsonNode.class)
                .getBody();
            if (body == null) {
                throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Empty provider response from OpenCode Server");
            }
            var all = body.get("all");
            if (all == null || !all.isArray()) {
                throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Invalid provider response from OpenCode Server");
            }
            var providerId = endpoint.opencodeProviderId() != null ? endpoint.opencodeProviderId() : "opencode-go";
            var connected = body.get("connected");
            if (connected == null || !connected.isArray()
                || !containsText(connected, providerId)) {
                throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Provider not connected: " + providerId);
            }
            List<String> models = new ArrayList<>();
            for (var provider : all) {
                if (provider.has("id") && providerId.equals(provider.get("id").asText())) {
                    var modelsObj = provider.get("models");
                    if (modelsObj != null && modelsObj.isObject()) {
                        modelsObj.fieldNames().forEachRemaining(models::add);
                    }
                }
            }
            if (models.isEmpty()) {
                throw new AiServiceException(HttpStatus.BAD_GATEWAY, "No models found for provider: " + providerId);
            }
            return models;
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new AiServiceException(HttpStatus.TOO_MANY_REQUESTS, "OpenCode Server rate limit exceeded");
            }
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "OpenCode Server request failed");
        } catch (HttpServerErrorException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "OpenCode Server returned an error");
        } catch (ResourceAccessException exception) {
            if (exception.getCause() instanceof SocketTimeoutException) {
                throw new AiServiceException(HttpStatus.GATEWAY_TIMEOUT, "OpenCode Server request timed out");
            }
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Unable to reach OpenCode Server");
        } catch (RestClientException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Invalid response from OpenCode Server");
        }
    }

    private void validateCredentials(AiEndpoint endpoint) {
        if (endpoint.opencodeUsername() == null || endpoint.opencodeUsername().isBlank()
            || endpoint.opencodePassword() == null || endpoint.opencodePassword().isBlank()) {
            throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE,
                "OpenCode Server username and password must be configured");
        }
    }

    private String createSession(RestClient client) {
        try {
            var body = client.post()
                .uri("/session")
                .body(Map.of())
                .retrieve()
                .toEntity(JsonNode.class)
                .getBody();
            if (body == null || body.get("id") == null || body.get("id").isNull()) {
                throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Empty session id from OpenCode Server");
            }
            return body.get("id").asText();
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new AiServiceException(HttpStatus.TOO_MANY_REQUESTS, "OpenCode Server rate limit exceeded");
            }
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "OpenCode Server request failed");
        } catch (HttpServerErrorException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "OpenCode Server returned an error");
        } catch (ResourceAccessException exception) {
            if (exception.getCause() instanceof SocketTimeoutException) {
                throw new AiServiceException(HttpStatus.GATEWAY_TIMEOUT, "OpenCode Server request timed out");
            }
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Unable to reach OpenCode Server");
        } catch (RestClientException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Invalid response from OpenCode Server");
        }
    }

    private ChatResponse sendMessage(RestClient client, AiEndpoint endpoint, String sessionId,
                                     List<ChatMessage> messages) {
        try {
            var requestBody = buildMessageBody(endpoint, messages);
            var body = client.post()
                .uri("/session/{id}/message", sessionId)
                .body(requestBody)
                .retrieve()
                .toEntity(JsonNode.class)
                .getBody();
            return parseMessageResponse(endpoint, body);
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new AiServiceException(HttpStatus.TOO_MANY_REQUESTS, "OpenCode Server rate limit exceeded");
            }
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "OpenCode Server request failed");
        } catch (HttpServerErrorException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "OpenCode Server returned an error");
        } catch (ResourceAccessException exception) {
            if (exception.getCause() instanceof SocketTimeoutException) {
                throw new AiServiceException(HttpStatus.GATEWAY_TIMEOUT, "OpenCode Server request timed out");
            }
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Unable to reach OpenCode Server");
        } catch (RestClientException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Invalid response from OpenCode Server");
        }
    }

    private void deleteSessionQuietly(RestClient client, String sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            client.delete()
                .uri("/session/{id}", sessionId)
                .retrieve()
                .toBodilessEntity();
        } catch (RuntimeException exception) {
        }
    }

    private void abortSessionQuietly(RestClient client, String sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            client.post()
                .uri("/session/{id}/abort", sessionId)
                .retrieve()
                .toBodilessEntity();
        } catch (RuntimeException exception) {
        }
    }

    private Map<String, Object> buildMessageBody(AiEndpoint endpoint, List<ChatMessage> messages) {
        var model = new LinkedHashMap<String, String>();
        model.put("providerID", endpoint.opencodeProviderId() != null ? endpoint.opencodeProviderId() : "opencode-go");
        model.put("modelID", endpoint.model());

        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("agent", endpoint.opencodeAgent() != null ? endpoint.opencodeAgent() : "blog-ai");
        body.put("system", SYSTEM_PROMPT);
        body.put("tools", Map.of());
        body.put("parts", buildParts(messages));
        return body;
    }

    private List<Map<String, Object>> buildParts(List<ChatMessage> messages) {
        var parts = new ArrayList<Map<String, Object>>();
        if (messages.isEmpty()) {
            parts.add(Map.of("type", "text", "text", ""));
            return parts;
        }
        if (messages.size() > 1) {
            var historyBuilder = new StringBuilder();
            for (int i = 0; i < messages.size() - 1; i++) {
                var msg = messages.get(i);
                if (!historyBuilder.isEmpty()) {
                    historyBuilder.append("\n");
                }
                historyBuilder.append(msg.role()).append(": ").append(msg.content());
            }
            parts.add(Map.of("type", "text", "text", historyBuilder.toString()));
        }
        var last = messages.get(messages.size() - 1);
        parts.add(Map.of("type", "text", "text", last.content()));
        return parts;
    }

    private ChatResponse parseMessageResponse(AiEndpoint endpoint, JsonNode body) {
        if (body == null) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Empty response from OpenCode Server");
        }
        var info = body.get("info");
        if (info != null && !info.isNull() && info.has("error") && !info.get("error").isNull()) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "OpenCode Server returned an error");
        }
        var parts = body.get("parts");
        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Invalid response from OpenCode Server");
        }
        var contentBuilder = new StringBuilder();
        for (var part : parts) {
            var type = part.has("type") ? part.get("type").asText("") : "";
            if ("text".equals(type)) {
                var text = part.get("text");
                if (text != null && !text.isNull()) {
                    contentBuilder.append(text.asText());
                }
            }
        }
        var content = contentBuilder.toString();
        if (content.isBlank()) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Empty response from OpenCode Server");
        }
        if (isForcedTerminationSummary(content)) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "AI response limit reached. Please retry.");
        }
        ChatResponse.Usage usage = null;
        if (info != null && !info.isNull()) {
            var tokens = info.get("tokens");
            if (tokens != null && !tokens.isNull()) {
                var input = getInt(tokens, "input");
                var output = getInt(tokens, "output");
                usage = new ChatResponse.Usage(input, output, input + output);
            }
        }
        var modelName = info != null && info.has("modelID")
            ? info.get("modelID").asText(endpoint.model()) : endpoint.model();
        return new ChatResponse(content, modelName, usage);
    }

    private boolean isForcedTerminationSummary(String content) {
        return content.contains(MAX_STEPS_MARKER)
            && content.contains("Remaining tasks not completed:")
            && content.contains("Recommendation for next steps:");
    }

    private static int getInt(JsonNode node, String field) {
        var value = node.get(field);
        return value != null ? value.asInt(0) : 0;
    }

    private static boolean containsText(JsonNode array, String text) {
        for (var element : array) {
            if (element.isTextual() && text.equals(element.asText())) {
                return true;
            }
        }
        return false;
    }

    private static RestClient buildRestClient(AiEndpoint endpoint) {
        var factory = new NoRedirectRequestFactory();
        var timeoutMillis = (int) Duration.ofSeconds(endpoint.requestTimeoutSeconds()).toMillis();
        factory.setConnectTimeout(timeoutMillis);
        factory.setReadTimeout(timeoutMillis);
        var baseUrl = endpoint.baseUrl().replaceAll("/+$", "");
        var builder = RestClient.builder()
            .requestFactory(factory)
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .requestInterceptor((request, body, execution) -> capBody(execution.execute(request, body)));
        if (endpoint.opencodeUsername() != null && endpoint.opencodePassword() != null) {
            var credentials = endpoint.opencodeUsername() + ":" + endpoint.opencodePassword();
            var encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
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
            throw new IOException("AI response exceeded size limit");
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
            public org.springframework.http.HttpStatusCode getStatusCode() {
                try {
                    return response.getStatusCode();
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            }

            @Override
            public String getStatusText() {
                try {
                    return response.getStatusText();
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
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
                throw new IOException("AI response exceeded size limit");
            }
        }
    }
}
