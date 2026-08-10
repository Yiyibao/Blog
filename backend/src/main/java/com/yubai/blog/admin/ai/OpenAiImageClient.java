package com.yubai.blog.admin.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * OpenAI-compatible image client used by both the Grok and GPT relay profiles. It accepts normal
 * /images/generations responses and the small Responses API image-generation shape used by some GPT
 * relays.
 */
@Component
public class OpenAiImageClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ENVELOPE_BYTES = 32_000_000;
    private static final int MAX_DIMENSION = 16_384;
    private static final long MAX_PIXELS = 80_000_000L;
    private static final int MAX_DOWNLOAD_REDIRECTS = 3;

    /**
     * Image relays can briefly return 502/503/504 while the provider is warming up a generation
     * worker. A few bounded retries make that transient state invisible to the admin UI without
     * retrying client errors.
     */
    private static final int TRANSIENT_RETRY_COUNT = 3;

    private static final long TRANSIENT_RETRY_DELAY_MILLIS = 750L;

    private final Function<AiImageEndpoint, RestClient> apiClientFactory;

    @Autowired
    public OpenAiImageClient() {
        this.apiClientFactory = OpenAiImageClient::buildApiClient;
    }

    OpenAiImageClient(Function<AiImageEndpoint, RestClient> apiClientFactory) {
        this.apiClientFactory = apiClientFactory;
    }

    public AiImageResult generate(
            AiImageEndpoint endpoint, AiImageGenerationRequest request, long maxImageBytes) {
        if (maxImageBytes <= 0 || maxImageBytes > Integer.MAX_VALUE) {
            throw new AiServiceException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Image size limit is invalid");
        }
        try {
            var wireApi = normalizeWireApi(endpoint.wireApi());
            var client = apiClientFactory.apply(endpoint);
            var path = joinUrl(endpoint.baseUrl(), pathFor(endpoint, request, wireApi));
            for (var attempt = 0; attempt <= TRANSIENT_RETRY_COUNT; attempt++) {
                try {
                    var response = post(client, path, endpoint, request, wireApi);
                    return parseResponse(endpoint.model(), response, maxImageBytes);
                } catch (HttpServerErrorException exception) {
                    if (!isTransient(exception) || attempt == TRANSIENT_RETRY_COUNT)
                        throw exception;
                    pauseBeforeRetry(attempt);
                }
            }
            throw new AiServiceException(
                    HttpStatus.BAD_GATEWAY, "AI image service returned an error");
        } catch (AiServiceException exception) {
            throw exception;
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new AiServiceException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        upstreamFailureMessage("AI image service rate limit exceeded", exception));
            }
            throw new AiServiceException(
                    HttpStatus.BAD_GATEWAY,
                    upstreamFailureMessage("AI image service request failed", exception));
        } catch (HttpServerErrorException exception) {
            throw new AiServiceException(
                    HttpStatus.BAD_GATEWAY,
                    upstreamFailureMessage("AI image service returned an error", exception));
        } catch (ResourceAccessException exception) {
            if (exception.getCause() instanceof java.net.SocketTimeoutException) {
                throw new AiServiceException(
                        HttpStatus.GATEWAY_TIMEOUT, "AI image service request timed out");
            }
            throw new AiServiceException(
                    HttpStatus.BAD_GATEWAY, "Unable to reach AI image service");
        } catch (RestClientException exception) {
            throw new AiServiceException(
                    HttpStatus.BAD_GATEWAY, "Invalid response from AI image service");
        }
    }

    private static boolean isTransient(HttpServerErrorException exception) {
        var status = exception.getStatusCode().value();
        return status == HttpStatus.BAD_GATEWAY.value()
                || status == HttpStatus.SERVICE_UNAVAILABLE.value()
                || status == HttpStatus.GATEWAY_TIMEOUT.value();
    }

    private static void pauseBeforeRetry(int attempt) {
        try {
            var multiplier = 1L << Math.min(attempt, 3);
            Thread.sleep(TRANSIENT_RETRY_DELAY_MILLIS * multiplier);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiServiceException(
                    HttpStatus.BAD_GATEWAY, "AI image service request interrupted");
        }
    }

    private static String upstreamFailureMessage(
            String fallback, RestClientResponseException exception) {
        var body = exception.getResponseBodyAsString();
        if (!notBlank(body)) return fallback;
        try {
            var message = findErrorMessage(MAPPER.readTree(body));
            if (notBlank(message)) return fallback + ": " + compactSafeMessage(message);
        } catch (IOException ignored) {
            // Keep the stable fallback when a relay returns non-JSON text.
        }
        return fallback;
    }

    private static String findErrorMessage(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (!node.isObject()) return null;
        for (var field : List.of("message", "detail", "error")) {
            var message = findErrorMessage(node.get(field));
            if (notBlank(message)) return message;
        }
        return null;
    }

    private static String compactSafeMessage(String raw) {
        var message = raw.replaceAll("\\s+", " ").trim();
        message =
                message.replaceAll(
                        "(?i)(api[_-]?key|authorization|token)\\s*[:=]\\s*[^\\s,;}]+",
                        "$1=[redacted]");
        return message.length() <= 240 ? message : message.substring(0, 240) + "…";
    }

    static Map<String, Object> buildBody(
            AiImageEndpoint endpoint, AiImageGenerationRequest request, String wireApi) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", endpoint.model());
        if (wireApi.equals("responses")) {
            body.put(
                    "input",
                    request.referenceImage() == null ? request.prompt() : responsesInput(request));
            var imageTool = new LinkedHashMap<String, Object>();
            imageTool.put("type", "image_generation");
            if (request.referenceImage() != null && !isGrok(endpoint)) {
                imageTool.put("action", "edit");
            }
            if (notBlank(request.size())) imageTool.put("size", request.size());
            if (notBlank(request.quality())) imageTool.put("quality", request.quality());
            if (notBlank(request.aspectRatio()))
                imageTool.put("aspect_ratio", request.aspectRatio());
            if (notBlank(request.resolution())) imageTool.put("resolution", request.resolution());
            body.put("tools", List.of(imageTool));
            return body;
        }
        body.put("prompt", request.prompt());
        body.put("n", request.count());
        // Grok Imagine uses aspect_ratio/resolution and selects the quality tier
        // through the model name.  size/quality are GPT-image fields and some
        // Grok-compatible relays reject them as unknown request properties.
        if (isGrok(endpoint)) body.put("response_format", "b64_json");
        if (request.referenceImage() != null && isGrok(endpoint)) {
            var image = new LinkedHashMap<String, Object>();
            image.put("url", dataUri(request.referenceImage()));
            image.put("type", "image_url");
            body.put("image", image);
        }
        if (!isGrok(endpoint) && notBlank(request.size())) body.put("size", request.size());
        if (!isGrok(endpoint) && notBlank(request.quality()))
            body.put("quality", request.quality());
        if (notBlank(request.aspectRatio())) body.put("aspect_ratio", request.aspectRatio());
        if (notBlank(request.resolution())) body.put("resolution", request.resolution());
        return body;
    }

    private static JsonNode post(
            RestClient client,
            String path,
            AiImageEndpoint endpoint,
            AiImageGenerationRequest request,
            String wireApi) {
        if (usesMultipartEdit(endpoint, request, wireApi)) {
            return client.post()
                    .uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(buildMultipartBody(endpoint, request))
                    .retrieve()
                    .toEntity(JsonNode.class)
                    .getBody();
        }
        return client.post()
                .uri(path)
                .body(buildBody(endpoint, request, wireApi))
                .retrieve()
                .toEntity(JsonNode.class)
                .getBody();
    }

    private static String pathFor(
            AiImageEndpoint endpoint, AiImageGenerationRequest request, String wireApi) {
        if (wireApi.equals("responses")) return "/responses";
        return request.referenceImage() == null ? "/images/generations" : "/images/edits";
    }

    private static boolean usesMultipartEdit(
            AiImageEndpoint endpoint, AiImageGenerationRequest request, String wireApi) {
        return request.referenceImage() != null && wireApi.equals("images") && !isGrok(endpoint);
    }

    private static MultiValueMap<String, Object> buildMultipartBody(
            AiImageEndpoint endpoint, AiImageGenerationRequest request) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("model", endpoint.model());
        body.add("prompt", request.prompt());
        body.add("n", String.valueOf(request.count()));
        if (notBlank(request.size())) body.add("size", request.size());
        if (notBlank(request.quality())) body.add("quality", request.quality());
        var reference = request.referenceImage();
        var resource =
                new ByteArrayResource(reference.bytes()) {
                    @Override
                    public String getFilename() {
                        return "reference" + extension(reference.mediaType());
                    }
                };
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(reference.mediaType()));
        headers.setContentDispositionFormData("image", resource.getFilename());
        body.add("image", new HttpEntity<>(resource, headers));
        return body;
    }

    private static List<Map<String, Object>> responsesInput(AiImageGenerationRequest request) {
        var text = new LinkedHashMap<String, Object>();
        text.put("type", "input_text");
        text.put("text", request.prompt());
        var image = new LinkedHashMap<String, Object>();
        image.put("type", "input_image");
        image.put("image_url", dataUri(request.referenceImage()));
        var user = new LinkedHashMap<String, Object>();
        user.put("role", "user");
        user.put("content", List.of(text, image));
        return List.of(user);
    }

    private static String dataUri(AiImageGenerationRequest.ReferenceImage image) {
        return "data:"
                + image.mediaType()
                + ";base64,"
                + Base64.getEncoder().encodeToString(image.bytes());
    }

    private static String extension(String mediaType) {
        return switch (mediaType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".png";
        };
    }

    static AiImageResult parseResponse(String model, JsonNode response, long maxImageBytes) {
        if (response == null || response.isNull()) {
            throw invalidResponse();
        }
        var candidates = new ArrayList<Candidate>();
        collectCandidates(response, candidates);
        if (candidates.isEmpty()) throw invalidResponse();
        var images = new ArrayList<AiImageResult.Image>();
        for (var candidate : candidates) {
            if (candidate.kind().equals("url")) {
                var downloaded = download(candidate.value(), maxImageBytes);
                images.add(inspect(downloaded.bytes(), downloaded.mediaType(), maxImageBytes));
            } else {
                var bytes = decodeBase64(candidate.value(), maxImageBytes);
                images.add(inspect(bytes, null, maxImageBytes));
            }
        }
        return new AiImageResult(model, List.copyOf(images));
    }

    private static void collectCandidates(JsonNode node, List<Candidate> candidates) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            var url = node.get("url");
            if (url != null && url.isTextual() && !url.asText().isBlank()) {
                candidates.add(new Candidate("url", url.asText()));
                return;
            }
            for (var field : List.of("b64_json", "base64", "result")) {
                var value = node.get(field);
                if (value != null && value.isTextual() && !value.asText().isBlank()) {
                    candidates.add(new Candidate("base64", value.asText()));
                    return;
                }
            }
            node.fields()
                    .forEachRemaining(entry -> collectCandidates(entry.getValue(), candidates));
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectCandidates(child, candidates));
        }
    }

    private static Downloaded download(String rawUrl, long maxBytes) {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException exception) {
            throw invalidResponse();
        }
        var current = uri;
        for (var redirect = 0; redirect <= MAX_DOWNLOAD_REDIRECTS; redirect++) {
            // Validate every hop.  Image relays commonly return a short-lived CDN
            // URL which redirects once or twice; following it blindly would turn
            // this server-side fetch into an SSRF primitive.
            validateRemoteUrl(current);
            var attempt = requestDownload(current, maxBytes);
            if (attempt.downloaded() != null) return attempt.downloaded();
            if (attempt.redirect() == null || redirect == MAX_DOWNLOAD_REDIRECTS) {
                throw new AiServiceException(HttpStatus.BAD_GATEWAY, "AI image download failed");
            }
            current = attempt.redirect();
        }
        throw new AiServiceException(HttpStatus.BAD_GATEWAY, "AI image download failed");
    }

    private static DownloadAttempt requestDownload(URI uri, long maxBytes) {
        var factory = new NoRedirectRequestFactory();
        var timeout = (int) Duration.ofSeconds(30).toMillis();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        try {
            return RestClient.builder()
                    .requestFactory(factory)
                    .build()
                    .get()
                    .uri(uri)
                    .exchange(
                            (request, response) -> {
                                if (response.getStatusCode().is3xxRedirection()) {
                                    var location =
                                            response.getHeaders().getFirst(HttpHeaders.LOCATION);
                                    if (location == null || location.isBlank()) {
                                        throw new AiServiceException(
                                                HttpStatus.BAD_GATEWAY, "AI image download failed");
                                    }
                                    try {
                                        return new DownloadAttempt(null, uri.resolve(location));
                                    } catch (IllegalArgumentException exception) {
                                        throw new AiServiceException(
                                                HttpStatus.BAD_GATEWAY, "AI image download failed");
                                    }
                                }
                                if (!response.getStatusCode().is2xxSuccessful()) {
                                    throw new AiServiceException(
                                            HttpStatus.BAD_GATEWAY, "AI image download failed");
                                }
                                var bytes = readBounded(response.getBody(), maxBytes);
                                var contentType = response.getHeaders().getContentType();
                                return new DownloadAttempt(
                                        new Downloaded(
                                                bytes,
                                                contentType == null
                                                        ? null
                                                        : contentType.toString()),
                                        null);
                            });
        } catch (AiServiceException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "AI image download failed");
        }
    }

    private static AiImageResult.Image inspect(
            byte[] bytes, String declaredMediaType, long maxBytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > maxBytes) throw invalidResponse();
        try {
            var image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (image == null
                    || image.getWidth() <= 0
                    || image.getHeight() <= 0
                    || image.getWidth() > MAX_DIMENSION
                    || image.getHeight() > MAX_DIMENSION
                    || (long) image.getWidth() * image.getHeight() > MAX_PIXELS) {
                throw invalidResponse();
            }
            var mediaType = normalizeMediaType(declaredMediaType, bytes);
            return new AiImageResult.Image(bytes, mediaType, image.getWidth(), image.getHeight());
        } catch (IOException exception) {
            throw new AiServiceException(
                    HttpStatus.BAD_GATEWAY, "AI image response is not a valid image");
        }
    }

    private static byte[] decodeBase64(String raw, long maxBytes) {
        var value = raw.trim();
        var comma = value.indexOf(',');
        if (value.startsWith("data:") && comma >= 0) value = value.substring(comma + 1);
        var maxEncoded = (maxBytes + 2) / 3 * 4 + 16;
        if (value.length() > maxEncoded) throw invalidResponse();
        try {
            var bytes = Base64.getMimeDecoder().decode(value.getBytes(StandardCharsets.US_ASCII));
            if (bytes.length > maxBytes) throw invalidResponse();
            return bytes;
        } catch (IllegalArgumentException exception) {
            throw invalidResponse();
        }
    }

    private static String normalizeMediaType(String declared, byte[] bytes) {
        var lower = declared == null ? "" : declared.toLowerCase(Locale.ROOT);
        if (lower.startsWith("image/") && !lower.equals("image/svg+xml")) {
            return lower.split(";", 2)[0];
        }
        if (bytes.length >= 8
                && bytes[0] == (byte) 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4e
                && bytes[3] == 0x47) return "image/png";
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) return "image/jpeg";
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F')
            return "image/gif";
        if (bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') return "image/webp";
        throw invalidResponse();
    }

    private static void validateRemoteUrl(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null) throw invalidResponse();
        try {
            for (var address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()
                        || isUniqueLocalIpv6(address)
                        || isCarrierGradeNat(address)) throw invalidResponse();
            }
        } catch (UnknownHostException exception) {
            throw invalidResponse();
        }
    }

    private static byte[] readBounded(InputStream input, long maxBytes) throws IOException {
        var output = new ByteArrayOutputStream((int) Math.min(maxBytes, 1_048_576));
        var buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maxBytes) throw invalidResponse();
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static RestClient buildApiClient(AiImageEndpoint endpoint) {
        var factory = new NoRedirectRequestFactory();
        var timeout =
                (int) Duration.ofSeconds(Math.max(1, endpoint.requestTimeoutSeconds())).toMillis();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        var builder =
                RestClient.builder()
                        .requestFactory(factory)
                        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .requestInterceptor(
                                (request, body, execution) ->
                                        capBody(execution.execute(request, body)));
        if (endpoint.apiKey() != null && !endpoint.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + endpoint.apiKey());
        }
        if (endpoint.headerName() != null
                && !endpoint.headerName().isBlank()
                && endpoint.headerValue() != null
                && !endpoint.headerValue().isBlank()) {
            builder.defaultHeader(endpoint.headerName(), endpoint.headerValue());
        }
        return builder.build();
    }

    private static ClientHttpResponse capBody(ClientHttpResponse response) throws IOException {
        var length = response.getHeaders().getContentLength();
        if (length > MAX_ENVELOPE_BYTES) {
            response.close();
            throw new IOException("AI image response is too large");
        }
        return new ClientHttpResponse() {
            @Override
            public InputStream getBody() throws IOException {
                return new BoundedInputStream(response.getBody(), MAX_ENVELOPE_BYTES);
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

    private static String joinUrl(String baseUrl, String path) {
        var base = baseUrl.trim().replaceAll("/+$", "");
        var suffix = path.startsWith("/") ? path : "/" + path;
        if (base.endsWith("/v1") && suffix.startsWith("/v1/")) suffix = suffix.substring(3);
        return base + suffix;
    }

    private static String normalizeWireApi(String wireApi) {
        return "responses".equalsIgnoreCase(wireApi == null ? "" : wireApi.trim())
                ? "responses"
                : "images";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isGrok(AiImageEndpoint endpoint) {
        return "grok".equalsIgnoreCase(endpoint.provider());
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        var bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static boolean isCarrierGradeNat(InetAddress address) {
        var bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
    }

    private static AiServiceException invalidResponse() {
        return new AiServiceException(
                HttpStatus.BAD_GATEWAY, "Invalid image response from AI service");
    }

    private record Candidate(String kind, String value) {}

    private record Downloaded(byte[] bytes, String mediaType) {}

    private record DownloadAttempt(Downloaded downloaded, URI redirect) {}

    private static final class NoRedirectRequestFactory extends SimpleClientHttpRequestFactory {
        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                throws IOException {
            super.prepareConnection(connection, httpMethod);
            connection.setInstanceFollowRedirects(false);
        }
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
            if (value >= 0) count(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            var count = super.read(buffer, offset, length);
            if (count > 0) count(count);
            return count;
        }

        private void count(long count) throws IOException {
            consumed += count;
            if (consumed > limit) throw new IOException("AI image response is too large");
        }
    }
}
