package com.stayhotel.hotel.user;

import com.stayhotel.common.error.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

/** The real auth-service adapter against a stub: token forwarding and every response mapping (R-M5-1). */
class HttpUserDirectoryTest {

    static final UUID MANAGER = UUID.randomUUID();
    static final UUID LOCKED_MANAGER = UUID.randomUUID();
    static final UUID CUSTOMER = UUID.randomUUID();
    static final UUID BROKEN = UUID.randomUUID();
    static final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    static HttpServer stub;
    static HttpUserDirectory directory;

    @BeforeAll
    static void start() throws IOException {
        Map<UUID, String> users = Map.of(
                MANAGER, "{\"id\":\"%s\",\"status\":\"ACTIVE\",\"roles\":[\"CUSTOMER\",\"HOTEL_MANAGER\"]}".formatted(MANAGER),
                LOCKED_MANAGER, "{\"id\":\"%s\",\"status\":\"LOCKED\",\"roles\":[\"HOTEL_MANAGER\"]}".formatted(LOCKED_MANAGER),
                CUSTOMER, "{\"id\":\"%s\",\"status\":\"ACTIVE\",\"roles\":[\"CUSTOMER\"]}".formatted(CUSTOMER));
        stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stub.createContext("/api/v1/admin/users/", exchange -> {
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            UUID id = UUID.fromString(exchange.getRequestURI().getPath().substring("/api/v1/admin/users/".length()));
            int status = id.equals(BROKEN) ? 500 : "Bearer not-admin".equals(lastAuthorization.get()) ? 403 : users.containsKey(id) ? 200 : 404;
            byte[] body = (status == 200 ? users.get(id) : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        stub.start();
        directory = new HttpUserDirectory(RestClient.builder(), "http://localhost:" + stub.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        stub.stop(0);
    }

    private static String code(Runnable call) {
        try {
            call.run();
            return "OK";
        } catch (BusinessException e) {
            return e.getStatus().value() + " " + e.getCode();
        }
    }

    @Test
    void forwardsTheCallersTokenAndAcceptsActiveManagers() {
        assertThatNoException().isThrownBy(() -> directory.requireActiveHotelManager(MANAGER, "admin-token"));
        assertThat(lastAuthorization.get()).isEqualTo("Bearer admin-token");
    }

    @Test
    void mapsEveryOtherOutcome() {
        assertThat(code(() -> directory.requireActiveHotelManager(CUSTOMER, "t"))).isEqualTo("422 USER_NOT_HOTEL_MANAGER");
        assertThat(code(() -> directory.requireActiveHotelManager(LOCKED_MANAGER, "t"))).isEqualTo("422 USER_NOT_HOTEL_MANAGER");
        assertThat(code(() -> directory.requireActiveHotelManager(UUID.randomUUID(), "t"))).isEqualTo("404 USER_NOT_FOUND");
        assertThat(code(() -> directory.requireActiveHotelManager(MANAGER, "not-admin"))).isEqualTo("403 FORBIDDEN");
        assertThat(code(() -> directory.requireActiveHotelManager(BROKEN, "t"))).isEqualTo("503 AUTH_SERVICE_UNAVAILABLE");
    }

    @Test
    void unreachableAuthServiceIs503() {
        HttpUserDirectory down = new HttpUserDirectory(RestClient.builder(), "http://localhost:1");
        assertThatThrownBy(() -> down.requireActiveHotelManager(MANAGER, "t")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("temporarily unavailable");
    }
}
