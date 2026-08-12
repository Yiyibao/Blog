package com.yubai.blog.admin.recipe;

import com.yubai.blog.dish.InvalidRecipeException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RecipeSourceHttpClient {
    static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_ATTEMPTS = 2;
    private static final int CIRCUIT_FAILURE_THRESHOLD = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CIRCUIT_OPEN_DURATION = Duration.ofSeconds(30);

    private final RecipeUrlValidator urlValidator;
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, CircuitState> circuits = new ConcurrentHashMap<>();

    @Autowired
    public RecipeSourceHttpClient(RecipeUrlValidator urlValidator) {
        this(
                urlValidator,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(CONNECT_TIMEOUT)
                        .build());
    }

    RecipeSourceHttpClient(RecipeUrlValidator urlValidator, HttpClient httpClient) {
        this.urlValidator = urlValidator;
        this.httpClient = httpClient;
    }

    public String fetch(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > MAX_URL_LENGTH) {
            throw new InvalidRecipeException("URL 不合法");
        }
        var uri = urlValidator.validatePublicHttps(rawUrl);
        var circuit =
                circuits.computeIfAbsent(
                        uri.getHost().toLowerCase(), ignored -> new CircuitState());
        if (!circuit.allow(Instant.now())) {
            throw new InvalidRecipeException("上游页面暂时不可用，请稍后重试");
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                var body = fetchOnce(uri);
                circuit.success();
                return body;
            } catch (RetryableFetchException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    circuit.failure(Instant.now());
                    throw new InvalidRecipeException("上游页面暂时不可用，请稍后重试");
                }
                pauseBeforeRetry(attempt);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new InvalidRecipeException("页面请求已取消");
            }
        }
        throw new InvalidRecipeException("上游页面暂时不可用，请稍后重试");
    }

    private String fetchOnce(URI uri) throws RetryableFetchException, InterruptedException {
        try {
            var request =
                    HttpRequest.newBuilder(uri)
                            .timeout(REQUEST_TIMEOUT)
                            .header("User-Agent", "Mozilla/5.0 (compatible; BlogBot/1.0)")
                            .header("Accept", "text/html,application/xhtml+xml,application/ld+json")
                            .GET()
                            .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() >= 500 || response.statusCode() == 429) {
                    throw new RetryableFetchException();
                }
                if (response.statusCode() >= 300) {
                    throw new InvalidRecipeException("页面返回 " + response.statusCode() + "，无法获取内容");
                }
                var contentLength = response.headers().firstValueAsLong("Content-Length");
                if (contentLength.isPresent() && contentLength.getAsLong() > MAX_RESPONSE_BYTES) {
                    throw new InvalidRecipeException("页面内容过大");
                }
                return new String(readBounded(body), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (InvalidRecipeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new RetryableFetchException();
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException, InterruptedException {
        var output = new ByteArrayOutputStream();
        var buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            total += read;
            if (total > MAX_RESPONSE_BYTES) {
                throw new InvalidRecipeException("页面内容过大");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void pauseBeforeRetry(int attempt) {
        try {
            Thread.sleep(200L * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InvalidRecipeException("页面请求已取消");
        }
    }

    private static final class RetryableFetchException extends Exception {}

    private static final class CircuitState {
        private int failures;
        private Instant openUntil;

        synchronized boolean allow(Instant now) {
            if (openUntil == null) return true;
            if (now.isBefore(openUntil)) return false;
            failures = 0;
            openUntil = null;
            return true;
        }

        synchronized void success() {
            failures = 0;
            openUntil = null;
        }

        synchronized void failure(Instant now) {
            failures++;
            if (failures >= CIRCUIT_FAILURE_THRESHOLD) {
                openUntil = now.plus(CIRCUIT_OPEN_DURATION);
            }
        }
    }
}
