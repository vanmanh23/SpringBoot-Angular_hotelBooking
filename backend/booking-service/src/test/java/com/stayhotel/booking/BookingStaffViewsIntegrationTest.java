package com.stayhotel.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.stayhotel.booking.BookingTestSupport.Api;
import com.stayhotel.booking.BookingTestSupport.FakeHotelCatalog;
import com.stayhotel.booking.BookingTestSupport.MutableClock;
import com.stayhotel.booking.booking.BookingEvents;
import com.stayhotel.booking.booking.BookingLifecycleJob;
import com.stayhotel.booking.booking.PaymentOutcomeHandler;
import com.stayhotel.booking.catalog.HotelSnapshot;
import com.stayhotel.common.security.test.TestJwtDecoderConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.stayhotel.booking.BookingTestSupport.familyRoom;
import static com.stayhotel.booking.BookingTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manager/admin booking views (D-030) against a stub hotel-service that answers the manage-access check, plus the
 * M5 event payloads (BookingCreated contact data, BookingCompleted).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestJwtDecoderConfiguration.class, BookingTestSupport.Config.class})
class BookingStaffViewsIntegrationTest {

    static HttpServer stub;
    /** "hotelId|bearer token" pairs the stub hotel-service accepts; anything else is 403, unknown hotels 404. */
    static final Set<String> ALLOWED = ConcurrentHashMap.newKeySet();
    static final Set<String> KNOWN_HOTELS = ConcurrentHashMap.newKeySet();

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stub.createContext("/api/v1/manage/hotels/", exchange -> {
            String hotelId = exchange.getRequestURI().getPath().substring("/api/v1/manage/hotels/".length());
            String auth = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
            int status = !KNOWN_HOTELS.contains(hotelId) ? 404
                    : ALLOWED.contains(hotelId + "|" + auth.replace("Bearer ", "")) ? 200 : 403;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        stub.start();
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
    TestRestTemplate http;
    @Autowired
    ObjectMapper json;
    @Autowired
    FakeHotelCatalog catalog;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    BookingLifecycleJob job;
    @Autowired
    PaymentOutcomeHandler payments;

    Api guest;
    Api manager;
    Api otherManager;
    Api admin;
    HotelSnapshot hotel;

    @BeforeEach
    void setUp() {
        clock.set(Instant.now());
        guest = Api.customer(http, json);
        manager = Api.withRoles(http, json, "HOTEL_MANAGER");
        otherManager = Api.withRoles(http, json, "HOTEL_MANAGER");
        admin = Api.withRoles(http, json, "ADMIN");
        hotel = BookingTestSupport.hotel(3);
        catalog.put(hotel);
        KNOWN_HOTELS.add(hotel.id().toString());
        ALLOWED.add(hotel.id() + "|" + manager.token());
        ALLOWED.add(hotel.id() + "|" + admin.token());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    private JsonNode book(LocalDate in) {
        ResponseEntity<String> res = guest.book(request(hotel, in, 1, familyRoom(hotel), 1, 2));
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.CREATED);
        return guest.body(res);
    }

    @Test
    void assignedManagerSeesTheHotelsBookingsOthersDoNot() {
        JsonNode later = book(today().plusDays(20));
        JsonNode sooner = book(today().plusDays(10));
        String path = "/api/v1/manage/hotels/" + hotel.id() + "/bookings";

        ResponseEntity<String> mine = manager.get(path);
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = manager.body(mine);
        assertThat(page.get("content").findValuesAsText("id"))
                .as("ordered by check-in").containsExactly(sooner.get("id").asText(), later.get("id").asText());
        assertThat(page.get("content").get(0).get("contactName").asText()).isEqualTo("Nguyen Van A");
        assertThat(mine.getBody()).doesNotContain("guest@example.com").doesNotContain("paymentId");

        JsonNode filtered = manager.body(manager.get(path + "?from=" + today().plusDays(15)));
        assertThat(filtered.get("content").findValuesAsText("id")).containsExactly(later.get("id").asText());

        ResponseEntity<String> stranger = otherManager.get(path);
        assertThat(stranger.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(otherManager.body(stranger).get("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(guest.get(path).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(manager.get("/api/v1/manage/hotels/" + UUID.randomUUID() + "/bookings").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(admin.get(path).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void adminListsAllBookingsAndOthersAreForbidden() {
        JsonNode b = book(today().plusDays(12));
        String code = b.get("bookingCode").asText();

        JsonNode byCode = admin.body(admin.get("/api/v1/admin/bookings?code=" + code.toLowerCase()));
        assertThat(byCode.get("totalElements").asInt()).isEqualTo(1);
        assertThat(byCode.get("content").get(0).get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(admin.body(admin.get("/api/v1/admin/bookings?status=CONFIRMED&hotelId=" + hotel.id()))
                .get("totalElements").asInt()).isZero();

        assertThat(manager.get("/api/v1/admin/bookings").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(guest.get("/api/v1/admin/bookings").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void bookingCreatedCarriesContactAndCompletionIsPublished() throws Exception {
        LocalDate in = today().plusDays(2);
        JsonNode b = book(in);
        String id = b.get("id").asText();

        JsonNode created = json.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM outbox_events WHERE message_key = ? AND event_type = 'BookingCreated'""",
                String.class, id)).get("payload");
        assertThat(created.get("userId").asText()).isEqualTo(guest.userId().toString());
        assertThat(created.get("contactEmail").asText()).isEqualTo("guest@example.com");
        assertThat(created.get("contactName").asText()).isEqualTo("Nguyen Van A");
        assertThat(created.get("bookingCode").asText()).isEqualTo(b.get("bookingCode").asText());
        assertThat(created.get("hotelName").asText()).isEqualTo("Test Hotel");

        BigDecimal total = b.get("totalAmount").decimalValue();
        payments.onPaymentSucceeded(UUID.randomUUID(),
                new BookingEvents.PaymentSucceeded(UUID.randomUUID(), UUID.fromString(id), total, "VND"));
        clock.set(in.plusDays(2).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant());
        job.completeFinishedStays();

        Map<String, Object> completed = jdbc.queryForMap("""
                SELECT payload->'payload'->>'userId' AS user_id, payload->'payload'->>'hotelId' AS hotel_id,
                       payload->'payload'->>'contactEmail' AS email
                FROM outbox_events WHERE message_key = ? AND event_type = 'BookingCompleted'""", id);
        assertThat(completed).containsEntry("user_id", guest.userId().toString())
                .containsEntry("hotel_id", hotel.id().toString())
                .containsEntry("email", null); // personal data only in BookingCreated (D-029)
    }
}
