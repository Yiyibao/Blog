package com.yubai.blog.admin.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.yubai.blog.config.AiProperties;
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

class DeepSeekChatServiceTest {

    private AiProperties properties;
    private MockRestServiceServer server;
    private DeepSeekChatService service;

    private static final String BASE_URL = "https://api.deepseek.com";
    private static final String COMPLETIONS_URL = BASE_URL + "/chat/completions";
    private static final String SUCCESS_JSON = """
        {"id":"x","object":"chat.completion","created":1,"model":"deepseek-v4-flash","choices":[{"index":0,"message":{"role":"assistant","content":"Hello! How can I help you?"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}
        """;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        properties.setBaseUrl(BASE_URL);
        properties.setMaxTotalChars(40000);
        properties.setMaxOutputTokens(2048);

        var builder = RestClient.builder()
            .baseUrl(COMPLETIONS_URL)
            .defaultHeader(org.springframework.http.HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer test-key");
        server = MockRestServiceServer.bindTo(builder).build();
        service = new DeepSeekChatService(properties, builder.build());
    }

    @Test
    void chatSuccessWithUsage() {
        server.expect(requestTo(COMPLETIONS_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess(SUCCESS_JSON, APPLICATION_JSON));

        var response = service.chat(new ChatRequest(List.of(new ChatMessage("user", "hello"))));

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

        var response = service.chat(new ChatRequest(List.of(new ChatMessage("user", "hello"))));

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

        service.chat(new ChatRequest(List.of(new ChatMessage("user", "hi"))));
        server.verify();
    }

    @Test
    void requestBodyHasExpectedStructure() {
        server.expect(requestTo(COMPLETIONS_URL))
            .andExpect(method(POST))
            .andExpect(content().json("""
                {"model":"deepseek-v4-flash","stream":false,"thinking":{"type":"disabled"},"tool_choice":"none","max_tokens":2048,"messages":[{"role":"system","content":"You are a helpful assistant. Provide concise and accurate responses."},{"role":"user","content":"hi"}]}
                """, false))
            .andRespond(withSuccess(SUCCESS_JSON, APPLICATION_JSON));

        service.chat(new ChatRequest(List.of(new ChatMessage("user", "hi"))));
        server.verify();
    }

    @Test
    void disabledServiceThrows503() {
        properties.setEnabled(false);
        var e = assertThrows(AiServiceException.class,
            () -> service.chat(new ChatRequest(List.of(new ChatMessage("user", "hello")))));
        assertEquals(503, e.getStatus().value());
    }

    @Test
    void nullApiKeyThrows503() {
        properties.setApiKey(null);
        var e = assertThrows(AiServiceException.class,
            () -> service.chat(new ChatRequest(List.of(new ChatMessage("user", "hello")))));
        assertEquals(503, e.getStatus().value());
    }

    @Test
    void blankApiKeyThrows503() {
        properties.setApiKey("  ");
        var e = assertThrows(AiServiceException.class,
            () -> service.chat(new ChatRequest(List.of(new ChatMessage("user", "hello")))));
        assertEquals(503, e.getStatus().value());
    }

    @Test
    void aggregateContentExceedsMaxThrows400() {
        var content = "x".repeat(20001);
        var request = new ChatRequest(List.of(new ChatMessage("user", content), new ChatMessage("assistant", content)));
        var e = assertThrows(AiServiceException.class, () -> service.chat(request));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void provider429MapsTo429() {
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        var e = assertThrows(AiServiceException.class,
            () -> service.chat(new ChatRequest(List.of(new ChatMessage("user", "hello")))));
        assertEquals(429, e.getStatus().value());
    }

    @Test
    void provider5xxMapsTo502() {
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(withServerError());
        var e = assertThrows(AiServiceException.class,
            () -> service.chat(new ChatRequest(List.of(new ChatMessage("user", "hello")))));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void provider4xxMapsTo502() {
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        var e = assertThrows(AiServiceException.class,
            () -> service.chat(new ChatRequest(List.of(new ChatMessage("user", "hello")))));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void timeoutMapsTo504() {
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(r -> {
            throw new ResourceAccessException("timeout", new SocketTimeoutException("Read timed out"));
        });
        var e = assertThrows(AiServiceException.class,
            () -> service.chat(new ChatRequest(List.of(new ChatMessage("user", "hello")))));
        assertEquals(504, e.getStatus().value());
    }

    @Test
    void connectionErrorMapsTo502() {
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(r -> {
            throw new ResourceAccessException("refused", new ConnectException("Connection refused"));
        });
        var e = assertThrows(AiServiceException.class,
            () -> service.chat(new ChatRequest(List.of(new ChatMessage("user", "hello")))));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void emptyChoicesThrows502() {
        var json = SUCCESS_JSON.replace(
            "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Hello! How can I help you?\"},\"finish_reason\":\"stop\"}]",
            "\"choices\":[]");
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(withSuccess(json, APPLICATION_JSON));
        var e = assertThrows(AiServiceException.class,
            () -> service.chat(new ChatRequest(List.of(new ChatMessage("user", "hello")))));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void nullMessageContentThrows502() {
        var json = SUCCESS_JSON.replace("\"content\":\"Hello! How can I help you?\"", "\"content\":null");
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(withSuccess(json, APPLICATION_JSON));
        var e = assertThrows(AiServiceException.class,
            () -> service.chat(new ChatRequest(List.of(new ChatMessage("user", "hello")))));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void blankContentThrows502() {
        var json = SUCCESS_JSON.replace("\"content\":\"Hello! How can I help you?\"", "\"content\":\"  \"");
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(withSuccess(json, APPLICATION_JSON));
        var e = assertThrows(AiServiceException.class,
            () -> service.chat(new ChatRequest(List.of(new ChatMessage("user", "hello")))));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void malformedJsonMapsTo502() {
        server.expect(requestTo(COMPLETIONS_URL)).andRespond(withSuccess("not-json", APPLICATION_JSON));
        var e = assertThrows(AiServiceException.class,
            () -> service.chat(new ChatRequest(List.of(new ChatMessage("user", "hello")))));
        assertEquals(502, e.getStatus().value());
    }
}
