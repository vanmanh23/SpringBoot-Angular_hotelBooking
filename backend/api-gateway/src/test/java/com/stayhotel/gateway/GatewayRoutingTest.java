package com.stayhotel.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies path-based routing and CORS using one in-process stub HTTP server per downstream service.
 * Each stub answers with its own name and the path it received.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "CORS_ALLOWED_ORIGINS=http://localhost:4200")
class GatewayRoutingTest {

    private static final List<String> SERVICES =
            List.of("auth", "hotel", "booking", "payment", "review", "notification");
    private static final Map<String, HttpServer> STUBS = new LinkedHashMap<>();

    static {
        SERVICES.forEach(name -> STUBS.put(name, startStub(name)));
    }

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        STUBS.forEach((name, server) -> registry.add(name.toUpperCase() + "_SERVICE_URL",
                () -> "http://localhost:" + server.getAddress().getPort()));
    }

    @AfterAll
    static void stopStubs() {
        STUBS.values().forEach(s -> s.stop(0));
    }

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void bindClient() {
        // Real HTTP client against the running server so CORS sees a complete request URI (scheme/host/port).
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @ParameterizedTest
    @CsvSource({
            "/api/v1/auth/login, auth",
            "/api/v1/users/me, auth",
            "/api/v1/admin/users, auth",
            "/api/v1/admin/users/123/status, auth",
            "/api/v1/hotels, hotel",
            "/api/v1/hotels/123, hotel",
            "/api/v1/hotels/123/room-types, hotel",
            "/api/v1/room-types/123, hotel",
            "/api/v1/amenities, hotel",
            "/api/v1/manage/hotels, hotel",
            "/api/v1/manage/hotels/123, hotel",
            "/api/v1/hotels/123/reviews, review",
            "/api/v1/bookings/me, booking",
            "/api/v1/availability, booking",
            "/api/v1/payments/abc, payment",
            "/sandbox-gateway/checkout/cs_1, payment",
            "/api/v1/reviews/abc, review",
            "/api/v1/notifications/me, notification"
    })
    void routesToOwningService(String path, String expectedService) {
        client.get().uri(path).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo(expectedService + " " + path);
    }

    @Test
    void correlationIdIsGeneratedAndForwarded() {
        String id = client.get().uri("/api/v1/hotels").exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("X-Correlation-Id", "[0-9a-f-]{36}")
                .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");
        // The stub echoes what it received; the downstream value must equal the one returned to the client.
        client.get().uri("/api/v1/hotels").header("X-Correlation-Id", id).exchange()
                .expectHeader().valueEquals("X-Seen-Correlation-Id", id)
                .expectHeader().valueEquals("X-Correlation-Id", id);
    }

    @Test
    void malformedCorrelationIdIsReplaced() {
        client.get().uri("/api/v1/hotels").header("X-Correlation-Id", "bad value<script>").exchange()
                .expectHeader().valueMatches("X-Seen-Correlation-Id", "[0-9a-f-]{36}");
    }

    @Test
    void unknownPathIsNotRouted() {
        client.get().uri("/api/v1/unknown").exchange().expectStatus().isNotFound();
    }

    @Test
    void corsPreflightAllowsConfiguredOrigin() {
        client.options().uri("/api/v1/hotels")
                .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200");
    }

    @Test
    void corsPreflightRejectsOtherOrigins() {
        client.options().uri("/api/v1/hotels")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static HttpServer startStub(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = (name + " " + exchange.getRequestURI().getPath()).getBytes(StandardCharsets.UTF_8);
                String seen = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
                if (seen != null) {
                    exchange.getResponseHeaders().add("X-Seen-Correlation-Id", seen);
                }
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
