package com.yubai.blog.admin.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
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

/**
 * 4A-1：OpenAI 兼容协议客户端。DeepSeek、OpenAI、通义、智谱、Kimi、本地 Ollama 等
 * 均走此协议；出网加固：禁跟随重定向、响应体积上限、连接/读取双超时。
 * 系统提示词后端固化，前端不可控。
 */
@Component
public class OpenAiCompatibleClient {
    static final int MAX_RESPONSE_BYTES = 2_000_000;
    private static final String SYSTEM_PROMPT =
        "You are a helpful assistant. Provide concise and accurate responses.";

    private final Function<AiEndpoint, RestClient> restClientFactory;

    @Autowired
    public OpenAiCompatibleClient() {
        this.restClientFactory = OpenAiCompatibleClient::buildRestClient;
    }

    OpenAiCompatibleClient(Function<AiEndpoint, RestClient> restClientFactory) {
        this.restClientFactory = restClientFactory;
    }

    public ChatResponse chat(AiEndpoint endpoint, List<ChatMessage> messages) {
        try {
            var body = restClientFactory.apply(endpoint)
                .post()
                .uri("/chat/completions")
                .body(buildChatBody(endpoint, messages))
                .retrieve()
                .toEntity(JsonNode.class)
                .getBody();
            return parseChatResponse(endpoint, body);
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new AiServiceException(HttpStatus.TOO_MANY_REQUESTS, "AI service rate limit exceeded");
            }
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "AI service request failed");
        } catch (HttpServerErrorException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "AI service returned an error");
        } catch (ResourceAccessException exception) {
            if (exception.getCause() instanceof SocketTimeoutException) {
                throw new AiServiceException(HttpStatus.GATEWAY_TIMEOUT, "AI service request timed out");
            }
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Unable to reach AI service");
        } catch (RestClientException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Invalid response from AI service");
        }
    }

    /** 供「测试连通」使用：GET /models，返回模型 id 列表（最多 50 个）。 */
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
            if (exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new AiServiceException(HttpStatus.TOO_MANY_REQUESTS, "AI service rate limit exceeded");
            }
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "AI service request failed");
        } catch (HttpServerErrorException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "AI service returned an error");
        } catch (ResourceAccessException exception) {
            if (exception.getCause() instanceof SocketTimeoutException) {
                throw new AiServiceException(HttpStatus.GATEWAY_TIMEOUT, "AI service request timed out");
            }
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Unable to reach AI service");
        } catch (RestClientException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Invalid response from AI service");
        }
    }

    private static Map<String, Object> buildChatBody(AiEndpoint endpoint, List<ChatMessage> messages) {
        var payload = new ArrayList<Map<String, String>>();
        payload.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        for (var message : messages) {
            payload.add(Map.of("role", message.role(), "content", message.content()));
        }
        return Map.of(
            "model", endpoint.model(),
            "messages", payload,
            "thinking", Map.of("type", "disabled"),
            "tool_choice", "none",
            "stream", false,
            "max_tokens", endpoint.maxOutputTokens()
        );
    }

    private static ChatResponse parseChatResponse(AiEndpoint endpoint, JsonNode body) {
        if (body == null) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Empty response from AI service");
        }
        var choices = body.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Invalid response from AI service");
        }
        var message = choices.get(0).get("message");
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
            usage = new ChatResponse.Usage(
                getInt(usageNode, "prompt_tokens"),
                getInt(usageNode, "completion_tokens"),
                getInt(usageNode, "total_tokens"));
        }
        return new ChatResponse(
            content.asText(),
            modelNode != null ? modelNode.asText() : endpoint.model(),
            usage
        );
    }

    private static int getInt(JsonNode node, String field) {
        var value = node.get(field);
        return value != null ? value.asInt(0) : 0;
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
            .requestInterceptor((request, body, execution) -> capBody(execution.execute(request, body)));
        if (endpoint.apiKey() != null && !endpoint.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + endpoint.apiKey());
        }
        return builder.build();
    }

    /** 出网加固：禁止跟随重定向，防止校验过的 base_url 被 302 转向内网。 */
    private static final class NoRedirectRequestFactory extends SimpleClientHttpRequestFactory {
        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
            super.prepareConnection(connection, httpMethod);
            connection.setInstanceFollowRedirects(false);
        }
    }

    /** 出网加固：响应体积上限，异常上游不能拖垮内存。 */
    private static ClientHttpResponse capBody(ClientHttpResponse response) throws IOException {
        var declaredLength = response.getHeaders().getContentLength();
        if (declaredLength > MAX_RESPONSE_BYTES) {
            response.close();
            throw new IOException("AI 响应超出大小上限");
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
                throw new IOException("AI 响应超出大小上限");
            }
        }
    }
}
