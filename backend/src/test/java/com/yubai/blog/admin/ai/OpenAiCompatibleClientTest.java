package com.yubai.blog.admin.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 原 DeepSeekChatServiceTest 的 HTTP 层用例整体迁移至此（4A-1 供应商抽象重构），
 * 断言逐条保留；服务层用例（禁用/缺密钥/总长）见 AiChatServiceTest。
 */
class OpenAiCompatibleClientTest {

    private MockRestServiceServer server;
    private OpenAiCompatibleClient client;
    private AiEndpoint endpoint;

    private static final String BASE_URL = "https://api.deepseek.com";
    private static final String COMPLETIONS_URL = BASE_URL + "/chat/completions";
    private static final String MODELS_URL = BASE_URL + "/models";
    private static final String SUCCESS_JSON = """
        {"id":"x","object":"chat.completion","created":1,"model":"deepseek-v4-flash","choices":[{"index":0,"message":{"role":"assistant","content":"Hello! How can I help you?"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}
        """;

    @BeforeEach
    void setUp() {
        endpoint = new AiEndpoint(BASE_URL, "test-key", "deepseek-v4-flash", 60, 2048);
        var builder = RestClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader(org.springframework.http.HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer test-key");
        server = MockRestServiceServer.bindTo(builder).build();
        var restClient = builder.build();
        client = new OpenAiCompatibleClient(ignored -> restClient);
    }

    @Test
    void chatSuccessWithUsage() {
        server.expect(requestTo(COMPLETIONS_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess(SUCCESS_JSON, APPLICATION_JSON));

        var response = client.chat(endpoint, List.of(new ChatMessage("user", "hello")));

        assertEquals("Hello! How can I help you?", response.content());
        assertEquals("deepseek-v4-flash", response.model());
        assertNotNull(response.usage());
        assertEquals(10, response.usage().promptTokens());
        assertEquals(20, response.usage().completionTokens());
        assertEquals(30, response.usage().totalTokens());
        server.verify();
    }

    @Test
    void chatSuccessWithoutUsage() {
        var json = SUCCESS_JSON.replace(
            "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}",
            "\"usage\":null");
        server.expect(requestTo(COMPLETIONS_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess(json, APPLICATION_JSON));

        var response = client.chat(endpoint, List.of(new ChatMessage("user", "hello")));

        assertEquals("Hello! How can I help you?", response.content());
        assertNull(response.usage());
        server.verify();
    }

    @Test
    void sendsAuthHeader() {
        server.expect(requestTo(COMPLETIONS_URL))
            .andExpect(method(POST))
            .andExpect(header("Authorization", "Bearer test-key"))
            .andRespond(withSuccess(SUCCESS_JSON, APPLICATION_JSON));

        client.chat(endpoint, List.of(new ChatMessage("user", "hi")));
        server.verify();
    }

    @Test
    void requestBodyHasExpectedStructure() {
        server.expect(requestTo(COMPLETIONS_URL))
            .andExpect(method(POST))
            .andExpect(content().json("""
                {"model":"deepseek-v4-flash","stream":false,"thinking":{"type":"disabled"},"max_tokens":2048,"messages":[{"role":"system","content":"You are a helpful assistant. Provide concise and accurate responses."},{"role":"user","content":"hi"}]}
                """, false))
            // tool_choice 不能在未附带 tools 时下发（OpenAI 会 400），已从请求体移除
            .andExpect(jsonPath("$.tool_choice").doesNotExist())
            .andRespond(withSuccess(SUCCESS_JSON, APPLICATION_JSON));

        client.chat(endpoint, List.of(new ChatMessage("user", "hi")));
        server.verify();
    }

    @Test
    void usesEndpointModelInRequestBody() {
        var customEndpoint = new AiEndpoint(BASE_URL, "test-key", "glm-4-flash", 60, 1024);
        server.expect(requestTo(COMPLETIONS_URL))
            .andExpect(method(POST))
            .andExpect(content().json("{\"model\":\"glm-4-flash\",\"max_tokens\":1024}", false))
            .andRespond(withSuccess(SUCCESS_JSON, APPLICATION_JSON));

        client.chat(customEndpoint, List.of(new ChatMessage("user", "hi")));
        server.verify();
    }

    @Test
    void provider429MapsTo429() {
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(429, e.getStatus().value());
    }

    @Test
    void provider5xxMapsTo502() {
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(withServerError());
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void provider4xxMapsTo502() {
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void timeoutMapsTo504() {
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(r -> {
            throw new ResourceAccessException("timeout", new SocketTimeoutException("Read timed out"));
        });
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(504, e.getStatus().value());
    }

    @Test
    void connectionErrorMapsTo502() {
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(r -> {
            throw new ResourceAccessException("refused", new ConnectException("Connection refused"));
        });
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void emptyChoicesThrows502() {
        var json = SUCCESS_JSON.replace(
            "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Hello! How can I help you?\"},\"finish_reason\":\"stop\"}]",
            "\"choices\":[]");
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(withSuccess(json, APPLICATION_JSON));
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void nullMessageContentThrows502() {
        var json = SUCCESS_JSON.replace("\"content\":\"Hello! How can I help you?\"", "\"content\":null");
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(withSuccess(json, APPLICATION_JSON));
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void blankContentThrows502() {
        var json = SUCCESS_JSON.replace("\"content\":\"Hello! How can I help you?\"", "\"content\":\"  \"");
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(withSuccess(json, APPLICATION_JSON));
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void malformedJsonMapsTo502() {
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(withSuccess("not-json", APPLICATION_JSON));
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(502, e.getStatus().value());
    }

    // ===== 4A-2：SSE 流式 =====

    private static final String SSE_BODY = """
        : keep-alive comment

        data: {"id":"x","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"role":"assistant"}}]}

        data: {"id":"x","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"content":"Hel"}}]}

        data: {"id":"x","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"content":"lo"}}]}

        data: {"id":"x","model":"deepseek-v4-flash","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}

        data: [DONE]
        """;

    private record CollectingListener(java.util.List<String> deltas,
                                      java.util.concurrent.atomic.AtomicReference<ChatResponse> completed)
        implements AiStreamListener {
        CollectingListener() {
            this(new java.util.ArrayList<>(), new java.util.concurrent.atomic.AtomicReference<>());
        }

        @Override
        public void onDelta(String content) {
            deltas.add(content);
        }

        @Override
        public void onComplete(ChatResponse response) {
            completed.set(response);
        }
    }

    @Test
    void streamEmitsDeltasInOrderAndCompletes() {
        server.expect(requestTo(COMPLETIONS_URL))
            .andExpect(method(POST))
            .andExpect(content().json("{\"stream\":true}", false))
            .andRespond(withSuccess(SSE_BODY, MediaType.TEXT_EVENT_STREAM));

        var listener = new CollectingListener();
        var response = client.stream(endpoint, List.of(new ChatMessage("user", "hi")), listener);

        assertEquals(List.of("Hel", "lo"), listener.deltas());
        assertEquals("Hello", response.content());
        assertEquals("deepseek-v4-flash", response.model());
        assertNotNull(response.usage());
        assertEquals(5, response.usage().totalTokens());
        assertEquals(response, listener.completed().get());
        server.verify();
    }

    @Test
    void streamParserToleratesEofWithoutDoneMarker() throws Exception {
        var body = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n";
        var listener = new CollectingListener();
        var response = OpenAiCompatibleClient.parseSseStream(
            new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "fallback-model", listener);
        assertEquals("partial", response.content());
        assertEquals("fallback-model", response.model());
    }

    @Test
    void streamWithoutAnyDeltaThrows502() {
        var body = "data: [DONE]\n";
        var listener = new CollectingListener();
        var e = assertThrows(AiServiceException.class, () -> OpenAiCompatibleClient.parseSseStream(
            new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "m", listener));
        assertEquals(502, e.getStatus().value());
        assertNull(listener.completed().get());
    }

    @Test
    void streamMalformedChunkThrows502() {
        var body = "data: not-json\n";
        var listener = new CollectingListener();
        var e = assertThrows(AiServiceException.class, () -> OpenAiCompatibleClient.parseSseStream(
            new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "m", listener));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void streamUpstream429MapsTo429() {
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        var e = assertThrows(AiServiceException.class,
            () -> client.stream(endpoint, List.of(new ChatMessage("user", "hi")), new CollectingListener()));
        assertEquals(429, e.getStatus().value());
    }

    @Test
    void streamUpstream5xxMapsTo502() {
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(withServerError());
        var e = assertThrows(AiServiceException.class,
            () -> client.stream(endpoint, List.of(new ChatMessage("user", "hi")), new CollectingListener()));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void nonDeepSeekEndpointOmitsThinkingField() {
        // thinking 是 DeepSeek 私有字段，发给 OpenAI 等供应商会被 400 拒绝
        var openAiEndpoint = new AiEndpoint("https://api.openai.com/v1", "test-key", "gpt-4o-mini", 60, 2048);
        server.expect(requestTo(COMPLETIONS_URL))
            .andExpect(method(POST))
            .andExpect(jsonPath("$.thinking").doesNotExist())
            .andExpect(jsonPath("$.tool_choice").doesNotExist())
            .andRespond(withSuccess(SUCCESS_JSON, APPLICATION_JSON));

        client.chat(openAiEndpoint, List.of(new ChatMessage("user", "hi")));
        server.verify();
    }

    @Test
    void deepSeekHostDetection() {
        assertTrue(OpenAiCompatibleClient.isDeepSeekEndpoint("https://api.deepseek.com"));
        assertTrue(OpenAiCompatibleClient.isDeepSeekEndpoint("https://api.deepseek.com/v1"));
        assertFalse(OpenAiCompatibleClient.isDeepSeekEndpoint("https://api.openai.com/v1"));
        assertFalse(OpenAiCompatibleClient.isDeepSeekEndpoint("https://open.bigmodel.cn/api/paas/v4"));
        // host 之外出现 deepseek 字样不应误判
        assertFalse(OpenAiCompatibleClient.isDeepSeekEndpoint("https://example.com/deepseek"));
    }

    @Test
    void streamParserJoinsMultiLineDataPerSseSpec() throws Exception {
        // SSE 规范：一个事件可由多个 data: 行组成，按换行拼接后再解析
        var body = """
            data: {"choices":[{"delta":
            data: {"content":"Hi"}}]}

            data: [DONE]
            """;
        var listener = new CollectingListener();
        var response = OpenAiCompatibleClient.parseSseStream(
            new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "m", listener);
        assertEquals("Hi", response.content());
        assertEquals(List.of("Hi"), listener.deltas());
    }

    @Test
    void streamParserStopsWhenThreadInterrupted() {
        // 客户端断开后 emitter 取消工作线程：解析循环应尽早退出，停止消耗上游 token
        var body = "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\ndata: [DONE]\n";
        var listener = new CollectingListener();
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.IOException.class, () -> OpenAiCompatibleClient.parseSseStream(
                new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "m", listener));
        } finally {
            // 清除中断标志，避免污染后续测试
            Thread.interrupted();
        }
        assertEquals(List.of(), listener.deltas());
    }

    @Test
    void listModelsParsesIds() {
        server.expect(requestTo(MODELS_URL))
            .andExpect(method(GET))
            .andRespond(withSuccess(
                "{\"object\":\"list\",\"data\":[{\"id\":\"deepseek-v4-flash\"},{\"id\":\"deepseek-reasoner\"}]}",
                APPLICATION_JSON));

        var models = client.listModels(endpoint);
        assertEquals(List.of("deepseek-v4-flash", "deepseek-reasoner"), models);
        server.verify();
    }

    @Test
    void listModelsUpstreamErrorMapsTo502() {
        server.expect(requestTo(MODELS_URL)).andRespond(withServerError());
        var e = assertThrows(AiServiceException.class, () -> client.listModels(endpoint));
        assertEquals(502, e.getStatus().value());
    }
}
