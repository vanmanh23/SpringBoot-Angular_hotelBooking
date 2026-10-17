package com.stayhotel.booking;

import com.sun.net.httpserver.HttpServer;
import com.stayhotel.booking.catalog.HotelCatalog;
import com.stayhotel.booking.catalog.HotelSnapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The real HTTP adapter against a stub hotel-service: mapping, 404, 5xx and timeouts. */
@SpringBootTest(properties = {"booking.hotel-service.read-timeout=500ms"})
@Import(TestcontainersConfiguration.class)
class HttpHotelCatalogTest {

    static final UUID ACTIVE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SLOW = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID BROKEN = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static HttpServer stub;

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stub.createContext("/api/v1/hotels/", exchange -> {
            String id = exchange.getRequestURI().getPath().substring("/api/v1/hotels/".length());
            int status;
            String body;
            if (id.equals(ACTIVE.toString())) {
                status = 200;
                body = """
                        {"id":"%s","name":"Sơn Trà Resort","timeZone":"Asia/Ho_Chi_Minh","checkInTime":"14:00",
                         "checkOutTime":"12:00","taxRatePercent":8.00,"serviceFeePercent":5.00,"images":[],"amenities":[],
                         "roomTypes":[{"id":"44444444-4444-4444-4444-444444444444","name":"Deluxe","capacity":2,
                           "basePrice":2400000,"currency":"VND","totalUnits":10,"status":"ACTIVE","amenities":[],"version":0}]}
                        """.formatted(ACTIVE);
            } else if (id.equals(SLOW.toString())) {
                sleep();
                status = 200;
                body = "{}";
            } else if (id.equals(BROKEN.toString())) {
                status = 500;
                body = "{\"code\":\"INTERNAL_ERROR\"}";
            } else {
                status = 404;
                body = "{\"code\":\"HOTEL_NOT_FOUND\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        stub.setExecutor(Executors.newCachedThreadPool()); // a slow request must not block the others
        stub.start();
    }

    private static void sleep() {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @DynamicPropertySource
    static void url(DynamicPropertyRegistry registry) {
        registry.add("booking.hotel-service.url", () -> "http://localhost:" + stub.getAddress().getPort());
    }

    @Autowired
    HotelCatalog catalog;

    @Test
    void mapsActiveHotelDetail() {
        Optional<HotelSnapshot> hotel = catalog.findActiveHotel(ACTIVE);
        assertThat(hotel).hasValueSatisfying(h -> {
            assertThat(h.name()).isEqualTo("Sơn Trà Resort");
            assertThat(h.checkInTime()).hasToString("14:00");
            assertThat(h.taxRatePercent()).isEqualByComparingTo("8");
            assertThat(h.roomTypes()).singleElement().satisfies(rt -> {
                assertThat(rt.totalUnits()).isEqualTo(10);
                assertThat(rt.basePrice()).isEqualByComparingTo("2400000");
            });
        });
    }

    @Test
    void notFoundMeansNotBookable() {
        assertThat(catalog.findActiveHotel(UUID.randomUUID())).isEmpty();
    }

    @Test
    void serverErrorsAndTimeoutsAreReportedAsUnavailable() {
        assertThatThrownBy(() -> catalog.findActiveHotel(BROKEN)).isInstanceOf(HotelCatalog.CatalogUnavailableException.class);
        assertThatThrownBy(() -> catalog.findActiveHotel(SLOW)).isInstanceOf(HotelCatalog.CatalogUnavailableException.class);
    }
}
