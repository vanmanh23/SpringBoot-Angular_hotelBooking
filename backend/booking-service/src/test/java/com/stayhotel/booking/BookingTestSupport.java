package com.stayhotel.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.booking.catalog.HotelCatalog;
import com.stayhotel.booking.catalog.HotelSnapshot;
import com.stayhotel.common.security.test.TestJwtSupport;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Shared fixtures for booking-service integration tests. */
public final class BookingTestSupport {

    private BookingTestSupport() {
    }

    /** In-memory stand-in for hotel-service (the real HTTP adapter has its own test). */
    public static class FakeHotelCatalog implements HotelCatalog {
        private final Map<UUID, HotelSnapshot> hotels = new ConcurrentHashMap<>();
        public final AtomicBoolean down = new AtomicBoolean(false);

        public void put(HotelSnapshot hotel) {
            hotels.put(hotel.id(), hotel);
        }

        public void remove(UUID hotelId) {
            hotels.remove(hotelId);
        }

        @Override
        public Optional<HotelSnapshot> findActiveHotel(UUID hotelId) {
            if (down.get()) {
                throw new CatalogUnavailableException("simulated outage", null);
            }
            return Optional.ofNullable(hotels.get(hotelId));
        }
    }

    public static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        public void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        public void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        FakeHotelCatalog fakeHotelCatalog() {
            return new FakeHotelCatalog();
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    /** A hotel with a single-unit room type ("last room") and a roomy family type. */
    public static HotelSnapshot hotel(int lastRoomUnits) {
        return new HotelSnapshot(UUID.randomUUID(), "Test Hotel", "Asia/Ho_Chi_Minh", LocalTime.of(14, 0),
                new BigDecimal("8.00"), new BigDecimal("5.00"), List.of(
                new HotelSnapshot.RoomTypeSnapshot(UUID.randomUUID(), "Double", 2, new BigDecimal("1000000.00"), "VND", lastRoomUnits),
                new HotelSnapshot.RoomTypeSnapshot(UUID.randomUUID(), "Family", 4, new BigDecimal("1500000.00"), "VND", 5)));
    }

    public static UUID doubleRoom(HotelSnapshot h) {
        return h.roomTypes().get(0).id();
    }

    public static UUID familyRoom(HotelSnapshot h) {
        return h.roomTypes().get(1).id();
    }

    public static Map<String, Object> request(HotelSnapshot hotel, LocalDate checkIn, int nights, UUID roomTypeId,
                                              int quantity, int adults) {
        Map<String, Object> body = new HashMap<>();
        body.put("hotelId", hotel.id());
        body.put("checkInDate", checkIn.toString());
        body.put("checkOutDate", checkIn.plusDays(nights).toString());
        body.put("adults", adults);
        body.put("children", 0);
        body.put("rooms", List.of(Map.of("roomTypeId", roomTypeId, "quantity", quantity)));
        body.put("contactName", "Nguyen Van A");
        body.put("contactEmail", "guest@example.com");
        return body;
    }

    /** Thin HTTP client bound to one user token. */
    public record Api(TestRestTemplate http, ObjectMapper json, UUID userId, String token) {

        public static Api customer(TestRestTemplate http, ObjectMapper json) {
            UUID id = UUID.randomUUID();
            return new Api(http, json, id, TestJwtSupport.token(id, "CUSTOMER"));
        }

        public static Api withRoles(TestRestTemplate http, ObjectMapper json, String... roles) {
            UUID id = UUID.randomUUID();
            return new Api(http, json, id, TestJwtSupport.token(id, roles));
        }

        public ResponseEntity<String> call(HttpMethod method, String path, Object body, String idempotencyKey) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(token);
            if (idempotencyKey != null) {
                headers.set("Idempotency-Key", idempotencyKey);
            }
            return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        }

        public ResponseEntity<String> book(Map<String, Object> body) {
            return call(HttpMethod.POST, "/api/v1/bookings", body, "key-" + UUID.randomUUID());
        }

        public ResponseEntity<String> get(String path) {
            return call(HttpMethod.GET, path, null, null);
        }

        public JsonNode body(ResponseEntity<String> res) {
            try {
                return json.readTree(res.getBody());
            } catch (Exception e) {
                throw new IllegalStateException(res.getBody(), e);
            }
        }
    }
}
