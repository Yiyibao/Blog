package com.yubai.blog.admin.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.yubai.blog.dish.InvalidRecipeException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecipeSourceHttpClientTest {
    private HttpServer server;
    private RecipeSourceHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        var validator = mock(RecipeUrlValidator.class);
        when(validator.validatePublicHttps(anyString()))
                .thenAnswer(invocation -> URI.create(invocation.getArgument(0)));
        client =
                new RecipeSourceHttpClient(
                        validator,
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void retriesTransientFailureThenReturnsBoundedBody() {
        var requests = new AtomicInteger();
        server.createContext(
                "/retry",
                exchange -> {
                    var attempt = requests.incrementAndGet();
                    var body = attempt == 1 ? "busy".getBytes() : "<html>recipe</html>".getBytes();
                    exchange.sendResponseHeaders(attempt == 1 ? 503 : 200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });

        assertThat(client.fetch(url("/retry"))).contains("recipe");
        assertThat(requests).hasValue(2);
    }

    @Test
    void rejectsOversizedResponseWithoutBufferingItAll() {
        server.createContext(
                "/large",
                exchange -> {
                    exchange.sendResponseHeaders(
                            200, RecipeSourceHttpClient.MAX_RESPONSE_BYTES + 1L);
                    var chunk = new byte[8192];
                    try {
                        for (int sent = 0;
                                sent <= RecipeSourceHttpClient.MAX_RESPONSE_BYTES;
                                sent += chunk.length) {
                            exchange.getResponseBody().write(chunk);
                        }
                    } catch (Exception ignored) {
                        // The client intentionally closes the stream as soon as the limit is
                        // crossed.
                    } finally {
                        exchange.close();
                    }
                });

        assertThatThrownBy(() -> client.fetch(url("/large")))
                .isInstanceOf(InvalidRecipeException.class)
                .hasMessage("页面内容过大");
    }

    @Test
    void opensCircuitAndDoesNotLeakTheRequestedUrlInErrors() {
        var requests = new AtomicInteger();
        server.createContext(
                "/fail",
                exchange -> {
                    requests.incrementAndGet();
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                });
        var url = url("/fail?token=secret-value");

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> client.fetch(url))
                    .isInstanceOf(InvalidRecipeException.class)
                    .hasMessageNotContaining("secret-value");
        }
        assertThat(requests).hasValue(6);

        assertThatThrownBy(() -> client.fetch(url))
                .isInstanceOf(InvalidRecipeException.class)
                .hasMessage("上游页面暂时不可用，请稍后重试");
        assertThat(requests).hasValue(6);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }
}
