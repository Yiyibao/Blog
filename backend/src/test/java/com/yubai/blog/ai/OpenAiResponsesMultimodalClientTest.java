package com.yubai.blog.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yubai.blog.admin.ai.AiEndpoint;
import com.yubai.blog.admin.ai.AiProviderType;
import com.yubai.blog.config.AiProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiResponsesMultimodalClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void imageAndDocumentPartsCrossTheRealHttpJsonBoundary() throws Exception {
        var captured = new AtomicReference<JsonNode>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/responses",
                exchange -> {
                    captured.set(objectMapper.readTree(exchange.getRequestBody()));
                    var response =
                            "{\"model\":\"fake-vision\",\"output_text\":\"understood\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        var endpoint =
                new AiEndpoint(
                        1L,
                        AiProviderType.OPENAI_RESPONSES,
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "test-key",
                        "fake-vision",
                        5,
                        128,
                        100,
                        1_000,
                        null,
                        null,
                        null,
                        null);
        var request =
                new AiModelPreparedRequest(
                        endpoint,
                        1L,
                        List.of(
                                AiModelInputPart.text("describe both"),
                                new AiModelInputPart(
                                        AiPartKind.IMAGE_REF,
                                        null,
                                        "pixel.png",
                                        "image/png",
                                        new byte[] {(byte) 0x89, 'P', 'N', 'G'}),
                                new AiModelInputPart(
                                        AiPartKind.FILE_REF,
                                        "hello",
                                        "note.txt",
                                        "text/plain",
                                        "hello".getBytes(StandardCharsets.UTF_8))),
                        Set.of(
                                AiProviderCapability.TEXT,
                                AiProviderCapability.VISION,
                                AiProviderCapability.FILE_INPUT));

        var result = new OpenAiResponsesMultimodalClient(new AiProperties()).execute(request);

        assertThat(result.text()).isEqualTo("understood");
        var content = captured.get().at("/input/0/content");
        assertThat(content.findValuesAsText("type"))
                .containsExactly("input_text", "input_image", "input_file");
        assertThat(content.get(1).get("image_url").asText()).startsWith("data:image/png;base64,");
        assertThat(content.get(2).get("file_data").asText()).startsWith("data:text/plain;base64,");
        assertThat(captured.get().get("store").asBoolean()).isFalse();
    }

    @Test
    void preservesAssistantRoleWhenReplayingApplicationOwnedHistory() {
        var endpoint =
                new AiEndpoint(
                        1L,
                        AiProviderType.OPENAI_RESPONSES,
                        "http://127.0.0.1:1",
                        "test-key",
                        "fake-model",
                        5,
                        128,
                        100,
                        1_000,
                        null,
                        null,
                        null,
                        null);
        var client = new OpenAiResponsesMultimodalClient(new AiProperties());

        var body =
                client.buildBody(
                        endpoint,
                        List.of(
                                AiModelInputPart.text(AiPartRole.USER, "first question"),
                                AiModelInputPart.text(AiPartRole.ASSISTANT, "first answer"),
                                AiModelInputPart.text(AiPartRole.USER, "follow up")));

        var input = objectMapper.valueToTree(body).get("input");
        assertThat(input.findValuesAsText("role")).containsExactly("user", "assistant", "user");
        assertThat(input.at("/1/content/0/text").asText()).isEqualTo("first answer");
    }
}
