package com.yubai.blog.admin.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AnthropicClientTest {

    private static final String BASE_URL = "https://api.anthropic.com";
    private static final String MESSAGES_URL = BASE_URL + "/v1/messages";
    private static final String MODELS_URL = BASE_URL + "/v1/models";
    private static final String SUCCESS_JSON = """
        {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-20250514","content":[{"type":"text","text":"Hello from Claude"}],"stop_reason":"end_turn","usage":{"input_tokens":12,"output_tokens":7}}
        """;

    private MockRestServiceServer server;
    private AnthropicClient client;
    private AiEndpoint endpoint;

    @BeforeEach
    void setUp() {
        endpoint = endpoint(BASE_URL);
        var builder = RestClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader(org.springframework.http.HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("x-api-key", "test-key")
            .defaultHeader("anthropic-version", AnthropicClient.ANTHROPIC_VERSION);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AnthropicClient(ignored -> builder.build());
    }

    private static AiEndpoint endpoint(String baseUrl) {
        return new AiEndpoint(1L, AiProviderType.ANTHROPIC, baseUrl, "test-key",
            "claude-sonnet-4-20250514", 60, 2048, 200, 200_000,
            null, null, null, null);
    }

    @Test
    void chatUsesMessagesApiHeadersAndParsesUsage() {
        server.expect(requestTo(MESSAGES_URL))
            .andExpect(method(POST))
            .andExpect(header("x-api-key", "test-key"))
            .andExpect(header("anthropic-version", AnthropicClient.ANTHROPIC_VERSION))
            .andExpect(content().json("""
                {"model":"claude-sonnet-4-20250514","system":"You are a helpful assistant. Provide concise and accurate responses.","messages":[{"role":"user","content":"hi"}],"max_tokens":2048,"stream":false}
                """, false))
            .andRespond(withSuccess(SUCCESS_JSON, APPLICATION_JSON));

        var response = client.chat(endpoint, List.of(new ChatMessage("user", "hi")));

        assertEquals("Hello from Claude", response.content());
        assertEquals("claude-sonnet-4-20250514", response.model());
        assertNotNull(response.usage());
        assertEquals(12, response.usage().promptTokens());
        assertEquals(7, response.usage().completionTokens());
        assertEquals(19, response.usage().totalTokens());
        server.verify();
    }

    @Test
    void mapsPerRequestReasoningEffortToExtendedThinkingBudget() {
        server.expect(requestTo(MESSAGES_URL))
            .andExpect(method(POST))
            .andExpect(content().json("""
                {"model":"claude-sonnet-4-20250514","thinking":{"type":"enabled","budget_tokens":1024},"max_tokens":2048}
                """, false))
            .andRespond(withSuccess(SUCCESS_JSON, APPLICATION_JSON));

        client.chat(endpoint, List.of(new ChatMessage("user", "hi")), "medium");

        server.verify();
    }

    @Test
    void baseUrlWithV1DoesNotDuplicateVersionPath() {
        var v1Endpoint = endpoint(BASE_URL + "/v1");
        server.expect(requestTo(MESSAGES_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess(SUCCESS_JSON, APPLICATION_JSON));

        client.chat(v1Endpoint, List.of(new ChatMessage("user", "hi")));
        server.verify();
    }

    @Test
    void streamEmitsTextDeltasAndCombinesUsage() {
        var body = """
            event: message_start
            data: {"type":"message_start","message":{"model":"claude-sonnet-4-20250514","usage":{"input_tokens":4,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hel"}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"lo"}}

            event: message_delta
            data: {"type":"message_delta","usage":{"output_tokens":2}}

            event: message_stop
            data: {"type":"message_stop"}
            """;
        server.expect(requestTo(MESSAGES_URL))
            .andExpect(method(POST))
            .andExpect(content().json("{\"stream\":true}", false))
            .andRespond(withSuccess(body, MediaType.TEXT_EVENT_STREAM));

        var deltas = new java.util.ArrayList<String>();
        var completed = new AtomicReference<ChatResponse>();
        var listener = new AiStreamListener() {
            @Override public void onDelta(String content) { deltas.add(content); }
            @Override public void onComplete(ChatResponse response) { completed.set(response); }
        };

        var response = client.stream(endpoint, List.of(new ChatMessage("user", "hi")), listener);

        assertEquals(List.of("Hel", "lo"), deltas);
        assertEquals("Hello", response.content());
        assertEquals(6, response.usage().totalTokens());
        assertSame(response, completed.get());
        server.verify();
    }

    @Test
    void listModelsUsesAnthropicModelsEndpoint() {
        server.expect(requestTo(MODELS_URL))
            .andExpect(method(GET))
            .andRespond(withSuccess(
                "{\"data\":[{\"id\":\"claude-sonnet-4-20250514\"},{\"id\":\"claude-3-7-sonnet-latest\"}]}",
                APPLICATION_JSON));

        assertEquals(List.of("claude-sonnet-4-20250514", "claude-3-7-sonnet-latest"),
            client.listModels(endpoint));
        server.verify();
    }

    @Test
    void rateLimitMapsTo429() {
        server.expect(requestTo(MESSAGES_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        var exception = assertThrows(AiServiceException.class,
            () -> client.chat(endpoint, List.of(new ChatMessage("user", "hi"))));
        assertEquals(429, exception.getStatus().value());
    }
}
