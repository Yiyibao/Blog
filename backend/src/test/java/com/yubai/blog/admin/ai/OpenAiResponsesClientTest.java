package com.yubai.blog.admin.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.yubai.blog.config.AiProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiResponsesClientTest {

    private static final String BASE_URL = "https://xinyue.example";
    private static final String RESPONSES_URL = BASE_URL + "/responses";
    private static final String MODELS_URL = BASE_URL + "/models";
    private static final String SUCCESS_JSON = """
        {"object":"response","model":"gpt-5.5","output":[{"type":"message","content":[{"type":"output_text","text":"Hello! How can I help you?"}]}],"usage":{"input_tokens":10,"output_tokens":20,"total_tokens":30}}
        """;

    private MockRestServiceServer server;
    private OpenAiResponsesClient client;
    private AiEndpoint endpoint;

    @BeforeEach
    void setUp() {
        endpoint = new AiEndpoint(null, AiProviderType.OPENAI_RESPONSES, BASE_URL, "test-key",
            "gpt-5.5", 60, 2048, 200, 200_000, null, null, null, null);
        var builder = RestClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader(org.springframework.http.HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer test-key")
            .defaultHeader("x-openai-actor-authorization", "local-image-extension");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiResponsesClient(ignored -> builder.build());
    }

    @Test
    void chatParsesOutputTextAndUsage() {
        server.expect(requestTo(RESPONSES_URL))
            .andExpect(method(POST))
            .andExpect(header("Authorization", "Bearer test-key"))
            .andExpect(header("x-openai-actor-authorization", "local-image-extension"))
            .andRespond(withSuccess(SUCCESS_JSON, APPLICATION_JSON));

        var response = client.chat(endpoint, List.of(new ChatMessage("user", "hello")));

        assertEquals("Hello! How can I help you?", response.content());
        assertEquals("gpt-5.5", response.model());
        assertNotNull(response.usage());
        assertEquals(10, response.usage().promptTokens());
        assertEquals(20, response.usage().completionTokens());
        assertEquals(30, response.usage().totalTokens());
        server.verify();
    }

    @Test
    void requestBodyUsesResponsesMessageContentTypes() {
        server.expect(requestTo(RESPONSES_URL))
            .andExpect(method(POST))
            .andExpect(content().json("""
                {"model":"gpt-5.5","instructions":"You are a helpful assistant. Provide concise and accurate responses.","stream":false,"max_output_tokens":2048,"reasoning":{"effort":"xhigh"},"store":false,"input":[{"role":"user","content":[{"type":"input_text","text":"hello"}]},{"role":"assistant","content":[{"type":"output_text","text":"hi"}]}]}
                """, false))
            .andRespond(withSuccess(SUCCESS_JSON, APPLICATION_JSON));

        client.chat(endpoint, List.of(
            new ChatMessage("user", "hello"),
            new ChatMessage("assistant", "hi")));

        server.verify();
    }

    @Test
    void requestBodyUsesPerRequestReasoningEffort() {
        server.expect(requestTo(RESPONSES_URL))
            .andExpect(method(POST))
            .andExpect(content().json("""
                {"model":"gpt-5.5","stream":false,"reasoning":{"effort":"low"}}
                """, false))
            .andRespond(withSuccess(SUCCESS_JSON, APPLICATION_JSON));

        client.chat(endpoint, List.of(new ChatMessage("user", "hello")), "low");

        server.verify();
    }

    @Test
    void chatAllowsMissingUsage() {
        var withoutUsage = SUCCESS_JSON.replace(
            "," + "\"usage\":{" + "\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30}", "");
        server.expect(requestTo(RESPONSES_URL))
            .andRespond(withSuccess(withoutUsage, APPLICATION_JSON));

        var response = client.chat(endpoint, List.of(new ChatMessage("user", "hello")));

        assertNull(response.usage());
        server.verify();
    }

    @Test
    void listModelsParsesModelIds() {
        server.expect(requestTo(MODELS_URL))
            .andExpect(method(GET))
            .andRespond(withSuccess("""
                {"object":"list","data":[{"id":"gpt-5.5"},{"id":"gpt-5.4"}]}
                """, APPLICATION_JSON));

        assertEquals(List.of("gpt-5.5", "gpt-5.4"), client.listModels(endpoint));
        server.verify();
    }

    @Test
    void streamEmitsDeltasAndParsesCompletionUsage() {
        var deltas = new ArrayList<String>();
        var sse = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"Hello"}

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":" world"}

            event: response.completed
            data: {"type":"response.completed","response":{"model":"gpt-5.5","usage":{"input_tokens":4,"output_tokens":2,"total_tokens":6}}}

            """;
        server.expect(requestTo(RESPONSES_URL))
            .andExpect(method(POST))
            .andExpect(content().json("""
                {"model":"gpt-5.5","stream":true}
                """, false))
            .andRespond(withSuccess(sse, TEXT_EVENT_STREAM));

        var response = client.stream(endpoint, List.of(new ChatMessage("user", "hello")), deltas::add);

        assertEquals(List.of("Hello", " world"), deltas);
        assertEquals("Hello world", response.content());
        assertEquals(4, response.usage().promptTokens());
        assertEquals(2, response.usage().completionTokens());
        assertEquals(6, response.usage().totalTokens());
        server.verify();
    }
}
