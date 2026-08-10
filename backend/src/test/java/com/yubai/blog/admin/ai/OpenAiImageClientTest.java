package com.yubai.blog.admin.ai;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiImageClientTest {
    private static final String BASE_URL = "https://relay.test/v1";
    private MockRestServiceServer server;
    private OpenAiImageClient client;

    @BeforeEach
    void setUp() {
        var builder =
                RestClient.builder()
                        .baseUrl(BASE_URL)
                        .defaultHeader("Authorization", "Bearer test-key")
                        .defaultHeader("x-openai-actor-authorization", "local-image-extension");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiImageClient(ignored -> builder.build());
    }

    @Test
    void parsesOpenAiBase64ResponseAndSendsRelayHeaders() throws Exception {
        var endpoint =
                new AiImageEndpoint(
                        "gpt",
                        BASE_URL,
                        "test-key",
                        "gpt-image-1",
                        "images",
                        "x-openai-actor-authorization",
                        "local-image-extension",
                        30);
        var base64 = Base64.getEncoder().encodeToString(onePixelPng());
        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(header("x-openai-actor-authorization", "local-image-extension"))
                .andExpect(
                        content().json("{\"model\":\"gpt-image-1\",\"prompt\":\"a test\",\"n\":1}"))
                .andRespond(
                        withSuccess(
                                "{\"created\":1,\"data\":[{\"b64_json\":\"" + base64 + "\"}]}",
                                APPLICATION_JSON));

        var result =
                client.generate(
                        endpoint,
                        new AiImageGenerationRequest("a test", 1, null, null, null, null),
                        1_000_000);

        assertEquals("gpt-image-1", result.model());
        assertEquals(1, result.images().size());
        assertEquals("image/png", result.images().get(0).mediaType());
        assertEquals(1, result.images().get(0).width());
        assertEquals(1, result.images().get(0).height());
        server.verify();
    }

    @Test
    void parsesResponsesApiImageGenerationResult() throws Exception {
        var endpoint =
                new AiImageEndpoint(
                        "gpt", BASE_URL, "test-key", "gpt-5.5", "responses", null, null, 30);
        var base64 = Base64.getEncoder().encodeToString(onePixelPng());
        server.expect(requestTo(BASE_URL + "/responses"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.tools[0].type").value("image_generation"))
                .andRespond(
                        withSuccess(
                                "{\"output\":[{\"type\":\"image_generation_call\",\"result\":\""
                                        + base64
                                        + "\"}]}",
                                APPLICATION_JSON));

        var result =
                client.generate(
                        endpoint,
                        new AiImageGenerationRequest("a test", 1, null, null, null, null),
                        1_000_000);

        assertEquals(1, result.images().size());
        assertEquals(1, result.images().get(0).width());
        server.verify();
    }

    @Test
    void sendsGrokReferenceImageToImageEditEndpoint() throws Exception {
        var endpoint =
                new AiImageEndpoint(
                        "grok",
                        BASE_URL,
                        "test-key",
                        "grok-imagine-image-quality",
                        "images",
                        null,
                        null,
                        30);
        var reference = onePixelPng();
        var base64 = Base64.getEncoder().encodeToString(reference);
        server.expect(requestTo(BASE_URL + "/images/edits"))
                .andExpect(method(POST))
                .andExpect(
                        content()
                                .json(
                                        "{\"model\":\"grok-imagine-image-quality\",\"prompt\":\"make it blue\","
                                                + "\"image\":{\"type\":\"image_url\",\"url\":\"data:image/png;base64,"
                                                + base64
                                                + "\"}}"))
                .andRespond(
                        withSuccess(
                                "{\"data\":[{\"b64_json\":\"" + base64 + "\"}]}",
                                APPLICATION_JSON));

        var result =
                client.generate(
                        endpoint,
                        new AiImageGenerationRequest(
                                "make it blue",
                                1,
                                null,
                                null,
                                null,
                                null,
                                new AiImageGenerationRequest.ReferenceImage(
                                        reference, "image/png")),
                        1_000_000);

        assertEquals(1, result.images().size());
        server.verify();
    }

    @Test
    void sendsGptReferenceImageAsMultipartEdit() throws Exception {
        var endpoint =
                new AiImageEndpoint(
                        "gpt", BASE_URL, "test-key", "gpt-image-2", "images", null, null, 30);
        var base64 = Base64.getEncoder().encodeToString(onePixelPng());
        server.expect(requestTo(BASE_URL + "/images/edits"))
                .andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MULTIPART_FORM_DATA))
                .andExpect(content().string(containsString("name=\"model\"")))
                .andExpect(content().string(containsString("name=\"prompt\"")))
                .andExpect(content().string(containsString("name=\"image\"")))
                .andRespond(
                        withSuccess(
                                "{\"data\":[{\"b64_json\":\"" + base64 + "\"}]}",
                                APPLICATION_JSON));

        var result =
                client.generate(
                        endpoint,
                        new AiImageGenerationRequest(
                                "make it blue",
                                1,
                                "1024x1024",
                                "high",
                                null,
                                null,
                                new AiImageGenerationRequest.ReferenceImage(
                                        Base64.getDecoder().decode(base64), "image/png")),
                        1_000_000);

        assertEquals(1, result.images().size());
        server.verify();
    }

    @Test
    void addsReferenceImageToResponsesInput() throws Exception {
        var endpoint =
                new AiImageEndpoint(
                        "gpt", BASE_URL, "test-key", "gpt-5.5", "responses", null, null, 30);
        var reference = onePixelPng();
        var base64 = Base64.getEncoder().encodeToString(reference);
        server.expect(requestTo(BASE_URL + "/responses"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.input[0].content[0].type").value("input_text"))
                .andExpect(jsonPath("$.input[0].content[1].type").value("input_image"))
                .andExpect(
                        jsonPath("$.input[0].content[1].image_url")
                                .value("data:image/png;base64," + base64))
                .andRespond(
                        withSuccess(
                                "{\"output\":[{\"type\":\"image_generation_call\",\"result\":\""
                                        + base64
                                        + "\"}]}",
                                APPLICATION_JSON));

        var result =
                client.generate(
                        endpoint,
                        new AiImageGenerationRequest(
                                "make it blue",
                                1,
                                null,
                                null,
                                null,
                                null,
                                new AiImageGenerationRequest.ReferenceImage(
                                        reference, "image/png")),
                        1_000_000);

        assertEquals(1, result.images().size());
        server.verify();
    }

    @Test
    void retriesTransientRelayFailureBeforeSuccess() throws Exception {
        var endpoint =
                new AiImageEndpoint(
                        "grok",
                        BASE_URL,
                        "test-key",
                        "grok-imagine-image",
                        "images",
                        null,
                        null,
                        30);
        var base64 = Base64.getEncoder().encodeToString(onePixelPng());
        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(method(POST))
                .andRespond(
                        withSuccess(
                                "{\"created\":1,\"data\":[{\"b64_json\":\"" + base64 + "\"}]}",
                                APPLICATION_JSON));

        var result =
                client.generate(
                        endpoint,
                        new AiImageGenerationRequest("a test", 1, null, null, null, null),
                        1_000_000);

        assertEquals(1, result.images().size());
        server.verify();
    }

    @Test
    void retriesSeveralTransientRelayFailuresBeforeReturningTheImage() throws Exception {
        var endpoint =
                new AiImageEndpoint(
                        "grok",
                        BASE_URL,
                        "test-key",
                        "grok-imagine-image",
                        "images",
                        null,
                        null,
                        30);
        var base64 = Base64.getEncoder().encodeToString(onePixelPng());
        for (int attempt = 0; attempt < 3; attempt++) {
            server.expect(requestTo(BASE_URL + "/images/generations"))
                    .andExpect(method(POST))
                    .andRespond(
                            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                                    .body(
                                            "{\"error\":{\"message\":\"Upstream service temporarily unavailable\"}}")
                                    .contentType(APPLICATION_JSON));
        }
        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(method(POST))
                .andRespond(
                        withSuccess(
                                "{\"created\":1,\"data\":[{\"b64_json\":\"" + base64 + "\"}]}",
                                APPLICATION_JSON));

        var result =
                client.generate(
                        endpoint,
                        new AiImageGenerationRequest("a test", 1, null, null, null, null),
                        1_000_000);

        assertEquals(1, result.images().size());
        server.verify();
    }

    @Test
    void omitsGptOnlyOptionsFromGrokImageRequest() {
        var endpoint =
                new AiImageEndpoint(
                        "grok",
                        BASE_URL,
                        "test-key",
                        "grok-imagine-image",
                        "images",
                        null,
                        null,
                        30);

        var body =
                OpenAiImageClient.buildBody(
                        endpoint,
                        new AiImageGenerationRequest(
                                "a test", 1, "1024x1024", "high", "16:9", "2k"),
                        "images");

        assertEquals("grok-imagine-image", body.get("model"));
        assertEquals("a test", body.get("prompt"));
        assertEquals(1, body.get("n"));
        assertEquals("b64_json", body.get("response_format"));
        assertEquals("16:9", body.get("aspect_ratio"));
        assertEquals("2k", body.get("resolution"));
        assertFalse(body.containsKey("size"));
        assertFalse(body.containsKey("quality"));
    }

    @Test
    void includesSafeUpstreamErrorMessageForDebugging() {
        var endpoint =
                new AiImageEndpoint(
                        "grok",
                        BASE_URL,
                        "test-key",
                        "grok-imagine-image",
                        "images",
                        null,
                        null,
                        30);
        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(method(POST))
                .andRespond(
                        withStatus(HttpStatus.BAD_REQUEST)
                                .body("{\"error\":{\"message\":\"unsupported model\"}}")
                                .contentType(APPLICATION_JSON));

        var exception =
                assertThrows(
                        AiServiceException.class,
                        () ->
                                client.generate(
                                        endpoint,
                                        new AiImageGenerationRequest(
                                                "a test", 1, null, null, null, null),
                                        1_000_000));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
        assertTrue(exception.getMessage().contains("unsupported model"));
        server.verify();
    }

    @Test
    void rejectsNonImageBase64() {
        var endpoint =
                new AiImageEndpoint(
                        "grok",
                        BASE_URL,
                        "test-key",
                        "grok-imagine-image",
                        "images",
                        null,
                        null,
                        30);
        var exception =
                assertThrows(
                        AiServiceException.class,
                        () ->
                                OpenAiImageClient.parseResponse(
                                        endpoint.model(),
                                        new com.fasterxml.jackson.databind.ObjectMapper()
                                                .createObjectNode()
                                                .putArray("data")
                                                .addObject()
                                                .put(
                                                        "b64_json",
                                                        Base64.getEncoder()
                                                                .encodeToString(
                                                                        "not an image".getBytes())),
                                        1_000_000));
        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
    }

    private static byte[] onePixelPng() throws Exception {
        var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
