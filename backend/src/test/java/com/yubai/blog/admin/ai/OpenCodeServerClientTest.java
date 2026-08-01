package com.yubai.blog.admin.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.HttpMethod.DELETE;
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

class OpenCodeServerClientTest {

    private MockRestServiceServer server;
    private OpenCodeServerClient client;
    private AiEndpoint endpoint;

    private static final String BASE_URL = "http://127.0.0.1:8080";
    private static final String SESSION_URL = BASE_URL + "/session";
    private static final String PROVIDER_URL = BASE_URL + "/provider";
    private static final String SESSION_ID = "test-session-uuid";
    private static final String MESSAGE_URL = BASE_URL + "/session/" + SESSION_ID + "/message";
    private static final String DELETE_URL = BASE_URL + "/session/" + SESSION_ID;

    private static final String SUCCESS_RESPONSE_JSON = """
        {"info":{"id":"msg-1","sessionID":"test-session-uuid","role":"assistant","modelID":"deepseek-v4-flash","providerID":"opencode-go","tokens":{"input":10,"output":20,"reasoning":0,"cache":{"read":0,"write":0}}},"parts":[{"type":"text","text":"Hello! How can I help you?"}]}
        """;

    @BeforeEach
    void setUp() {
        endpoint = new AiEndpoint(null, AiProviderType.OPENCODE_SERVER, BASE_URL, null, "deepseek-v4-flash",
            60, 2048, 0, 0, "admin", "secret", "blog-ai", "opencode-go");
        var builder = RestClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader(org.springframework.http.HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(org.springframework.http.HttpHeaders.AUTHORIZATION,
                "Basic " + java.util.Base64.getEncoder().encodeToString("admin:secret".getBytes()));
        server = MockRestServiceServer.bindTo(builder).build();
        var restClient = builder.build();
        client = new OpenCodeServerClient(ignored -> restClient);
    }

    // ===== Auth header =====

    @Test
    void sendsBasicAuthHeader() {
        expectSessionCreate();
        expectMessage();
        expectSessionDelete();

        client.chat(endpoint, List.of(new ChatMessage("user", "hello")));

        server.verify();
    }

    // ===== Session lifecycle: create → message → delete =====

    @Test
    void chatSuccessWithUsage() {
        expectSessionCreate();
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess(SUCCESS_RESPONSE_JSON, APPLICATION_JSON));
        expectSessionDelete();

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
        expectSessionCreate();
        var json = SUCCESS_RESPONSE_JSON.replace(
            "\"tokens\":{\"input\":10,\"output\":20,\"reasoning\":0,\"cache\":{\"read\":0,\"write\":0}}",
            "\"tokens\":null");
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess(json, APPLICATION_JSON));
        expectSessionDelete();

        var response = client.chat(endpoint, List.of(new ChatMessage("user", "hello")));

        assertEquals("Hello! How can I help you?", response.content());
        assertNull(response.usage());
        server.verify();
    }

    @Test
    void requestBodyHasExpectedStructure() {
        expectSessionCreate();
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andExpect(content().json("""
                {"model":{"providerID":"opencode-go","modelID":"deepseek-v4-flash"},"agent":"blog-ai","system":"Answer the user's current request directly and completely in this response. Do not announce plans, future work, connection-test progress, or ask the user to start another run. Provide concise and accurate responses.","tools":{},"parts":[{"type":"text","text":"hello"}]}
                """, false))
            .andRespond(withSuccess(SUCCESS_RESPONSE_JSON, APPLICATION_JSON));
        expectSessionDelete();

        client.chat(endpoint, List.of(new ChatMessage("user", "hello")));
        server.verify();
    }

    @Test
    void forcedMaxStepsSummaryMapsToRetryableUpstreamError() {
        expectSessionCreate();
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess("""
                {"info":{"id":"msg-limit"},"parts":[{"type":"text","text":"Maximum steps for this agent have been reached.\\n\\nRemaining tasks not completed: none.\\n\\nRecommendation for next steps: start another run."}]}
                """, APPLICATION_JSON));
        expectSessionDelete();

        var error = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "test connection"))));

        assertEquals(502, error.getStatus().value());
        assertEquals("AI response limit reached. Please retry.", error.getMessage());
        server.verify();
    }

    @Test
    void requestBodyDoesNotSendMaxTokens() {
        expectSessionCreate();
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andExpect(content().json("""
                {"model":{"providerID":"opencode-go","modelID":"deepseek-v4-flash"},"agent":"blog-ai","tools":{},"parts":[{"type":"text","text":"hello"}]}
                """, false))
            .andExpect(request -> {
                var body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsBytes();
                assertFalse(new String(body).contains("maxTokens"), "Body must not contain maxTokens");
            })
            .andRespond(withSuccess(SUCCESS_RESPONSE_JSON, APPLICATION_JSON));
        expectSessionDelete();

        client.chat(endpoint, List.of(new ChatMessage("user", "hello")));
        server.verify();
    }

    @Test
    void chatMultipleMessagesPreservesHistory() {
        expectSessionCreate();
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andExpect(content().json("""
                {"parts":[{"type":"text","text":"user: first"},{"type":"text","text":"second"}]}
                """, false))
            .andRespond(withSuccess(SUCCESS_RESPONSE_JSON, APPLICATION_JSON));
        expectSessionDelete();

        client.chat(endpoint, List.of(
            new ChatMessage("user", "first"),
            new ChatMessage("assistant", "second")));
        server.verify();
    }

    @Test
    void chatFoldsMultipleHistoryTurns() {
        expectSessionCreate();
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andExpect(content().json("""
                {"parts":[{"type":"text","text":"user: a\\nassistant: b\\nuser: c"},{"type":"text","text":"d"}]}
                """, false))
            .andRespond(withSuccess(SUCCESS_RESPONSE_JSON, APPLICATION_JSON));
        expectSessionDelete();

        client.chat(endpoint, List.of(
            new ChatMessage("user", "a"),
            new ChatMessage("assistant", "b"),
            new ChatMessage("user", "c"), // history folded
            new ChatMessage("user", "d"))); // final turn clear
        server.verify();
    }

    // ===== Credential validation (503 before network) =====

    @Test
    void blankUsernameReturns503BeforeAnyRequest() {
        var bad = new AiEndpoint(null, AiProviderType.OPENCODE_SERVER, BASE_URL, null, "deepseek-v4-flash",
            60, 2048, 0, 0, "", "secret", "blog-ai", "opencode-go");
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(bad, List.of(new ChatMessage("user", "hello"))));
        assertEquals(503, e.getStatus().value());
    }

    @Test
    void blankPasswordReturns503BeforeAnyRequest() {
        var bad = new AiEndpoint(null, AiProviderType.OPENCODE_SERVER, BASE_URL, null, "deepseek-v4-flash",
            60, 2048, 0, 0, "admin", "", "blog-ai", "opencode-go");
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(bad, List.of(new ChatMessage("user", "hello"))));
        assertEquals(503, e.getStatus().value());
    }

    @Test
    void nullCredentialsReturns503BeforeAnyRequest() {
        var bad = new AiEndpoint(null, AiProviderType.OPENCODE_SERVER, BASE_URL, null, "deepseek-v4-flash",
            60, 2048, 0, 0, null, null, "blog-ai", "opencode-go");
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(bad, List.of(new ChatMessage("user", "hello"))));
        assertEquals(503, e.getStatus().value());
    }

    @Test
    void listModelsReturns503WhenCredentialsMissing() {
        var bad = new AiEndpoint(null, AiProviderType.OPENCODE_SERVER, BASE_URL, null, "deepseek-v4-flash",
            60, 2048, 0, 0, "", "secret", "blog-ai", "opencode-go");
        var e = assertThrows(AiServiceException.class, () -> client.listModels(bad));
        assertEquals(503, e.getStatus().value());
    }

    // ===== Error mapping =====

    @Test
    void createSession429MapsTo429() {
        server.expect(requestTo(SESSION_URL))
            .andExpect(method(POST))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(429, e.getStatus().value());
    }

    @Test
    void createSession5xxMapsTo502() {
        server.expect(requestTo(SESSION_URL))
            .andExpect(method(POST))
            .andRespond(withServerError());
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void createSessionTimeoutMapsTo504() {
        server.expect(requestTo(SESSION_URL))
            .andExpect(method(POST))
            .andRespond(r -> {
                throw new ResourceAccessException("timeout", new SocketTimeoutException("connect timed out"));
            });
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(504, e.getStatus().value());
    }

    @Test
    void createSessionConnectionErrorMapsTo502() {
        server.expect(requestTo(SESSION_URL))
            .andExpect(method(POST))
            .andRespond(r -> {
                throw new ResourceAccessException("refused", new ConnectException("Connection refused"));
            });
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void message4xxMapsTo502() {
        expectSessionCreate();
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        expectSessionDelete();
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void message5xxMapsTo502() {
        expectSessionCreate();
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andRespond(withServerError());
        expectSessionDelete();
        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(502, e.getStatus().value());
    }

    // ===== Info error detection =====

    @Test
    void infoErrorMapsTo502() {
        expectSessionCreate();
        var errorJson = """
            {"info":{"id":"msg-1","sessionID":"test-session-uuid","role":"assistant","error":{"type":"api_error","message":"upstream failed"}},"parts":[{"type":"text","text":"partial"}]}
            """;
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess(errorJson, APPLICATION_JSON));
        expectSessionDelete();

        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(502, e.getStatus().value());
        // error message must not contain raw upstream error object
        assertFalse(e.getMessage().contains("upstream failed"));
        assertFalse(e.getMessage().contains("api_error"));
    }

    // ===== Cleanup on failure =====

    @Test
    void sessionDeletedOnMessageError() {
        expectSessionCreate();
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andRespond(withServerError());
        server.expect(requestTo(DELETE_URL))
            .andExpect(method(DELETE))
            .andRespond(withSuccess());

        assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        server.verify();
    }

    @Test
    void sessionDeletedEvenWhenDeleteFails() {
        expectSessionCreate();
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess(SUCCESS_RESPONSE_JSON, APPLICATION_JSON));
        server.expect(requestTo(DELETE_URL))
            .andExpect(method(DELETE))
            .andRespond(withServerError());

        var response = client.chat(endpoint, List.of(new ChatMessage("user", "hello")));
        assertEquals("Hello! How can I help you?", response.content());
        server.verify();
    }

    // ===== Stream (delta fallback) =====

    @Test
    void streamEmitsSingleDeltaAndCompletes() {
        expectSessionCreate();
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess(SUCCESS_RESPONSE_JSON, APPLICATION_JSON));
        expectSessionDelete();

        var deltas = new java.util.ArrayList<String>();
        var completed = new java.util.concurrent.atomic.AtomicReference<ChatResponse>();
        var response = client.stream(endpoint, List.of(new ChatMessage("user", "hello")),
            new AiStreamListener() {
                @Override
                public void onDelta(String content) {
                    deltas.add(content);
                }

                @Override
                public void onComplete(ChatResponse r) {
                    completed.set(r);
                }
            });

        assertEquals(List.of("Hello! How can I help you?"), deltas);
        assertEquals("Hello! How can I help you?", response.content());
        assertNotNull(completed.get());
        assertEquals(response, completed.get());
        server.verify();
    }

    // ===== List models (GET /provider) =====

    @Test
    void listModelsReturnsModelsForConfiguredProvider() {
        var providerJson = """
            {"all":[{"id":"opencode-go","models":{"deepseek-v4-flash":{"id":"deepseek-v4-flash"},"deepseek-v3":{"id":"deepseek-v3"}}}],"default":{},"connected":["opencode-go"]}
            """;
        server.expect(requestTo(PROVIDER_URL))
            .andExpect(method(GET))
            .andRespond(withSuccess(providerJson, APPLICATION_JSON));

        var models = client.listModels(endpoint);

        assertEquals(2, models.size());
        assertTrue(models.contains("deepseek-v4-flash"));
        assertTrue(models.contains("deepseek-v3"));
        server.verify();
    }

    @Test
    void listModelsWithNonMatchingProviderReturnsEmptyModels() {
        var providerJson = """
            {"all":[{"id":"other-provider","models":{"some-model":{"id":"some-model"}}}],"default":{},"connected":["other-provider"]}
            """;
        server.expect(requestTo(PROVIDER_URL))
            .andExpect(method(GET))
            .andRespond(withSuccess(providerJson, APPLICATION_JSON));

        var e = assertThrows(AiServiceException.class, () -> client.listModels(endpoint));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void listModelsRejectsProviderNotInConnected() {
        var providerJson = """
            {"all":[{"id":"opencode-go","models":{"deepseek-v4-flash":{"id":"deepseek-v4-flash"}}}],"default":{},"connected":["other-provider"]}
            """;
        server.expect(requestTo(PROVIDER_URL))
            .andExpect(method(GET))
            .andRespond(withSuccess(providerJson, APPLICATION_JSON));

        var e = assertThrows(AiServiceException.class, () -> client.listModels(endpoint));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void listModelsUpstreamErrorMapsTo502() {
        server.expect(requestTo(PROVIDER_URL))
            .andExpect(method(GET))
            .andRespond(withServerError());
        var e = assertThrows(AiServiceException.class, () -> client.listModels(endpoint));
        assertEquals(502, e.getStatus().value());
    }

    // ===== Response parsing edge cases =====

    @Test
    void emptyPartsThrows502() {
        expectSessionCreate();
        var json = SUCCESS_RESPONSE_JSON.replace(
            "\"parts\":[{\"type\":\"text\",\"text\":\"Hello! How can I help you?\"}]",
            "\"parts\":[]");
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess(json, APPLICATION_JSON));
        expectSessionDelete();

        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void blankTextInPartsThrows502() {
        expectSessionCreate();
        var json = """
            {"info":{"id":"msg-1","sessionID":"test-session-uuid","role":"assistant","modelID":"deepseek-v4-flash","providerID":"opencode-go"},"parts":[{"type":"text","text":"  "}]}
            """;
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess(json, APPLICATION_JSON));
        expectSessionDelete();

        var e = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hello"))));
        assertEquals(502, e.getStatus().value());
    }

    // ===== Abort on interruption =====

    @Test
    void streamAbortsOnInterruption() {
        expectSessionCreate();
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess(SUCCESS_RESPONSE_JSON, APPLICATION_JSON));

        var abortUrl = BASE_URL + "/session/" + SESSION_ID + "/abort";
        server.expect(requestTo(abortUrl))
            .andExpect(method(POST))
            .andRespond(withSuccess());
        expectSessionDelete();

        Thread.currentThread().interrupt();
        try {
            client.stream(endpoint, List.of(new ChatMessage("user", "hello")), new AiStreamListener() {
                @Override
                public void onDelta(String content) {
                }
            });
        } finally {
            Thread.interrupted();
        }
        server.verify();
    }

    private void expectSessionCreate() {
        server.expect(requestTo(SESSION_URL))
            .andExpect(method(POST))
            .andExpect(content().json("{}", false))
            .andRespond(withSuccess("{\"id\":\"" + SESSION_ID + "\"}", APPLICATION_JSON));
    }

    private void expectMessage() {
        server.expect(requestTo(MESSAGE_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess(SUCCESS_RESPONSE_JSON, APPLICATION_JSON));
    }

    private void expectSessionDelete() {
        server.expect(requestTo(DELETE_URL))
            .andExpect(method(DELETE))
            .andRespond(withSuccess());
    }
}
