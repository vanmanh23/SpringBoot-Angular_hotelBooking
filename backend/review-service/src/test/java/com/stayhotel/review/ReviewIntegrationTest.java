package com.stayhotel.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.common.messaging.EventEnvelope;
import com.stayhotel.common.messaging.Topics;
import com.stayhotel.common.security.test.TestJwtDecoderConfiguration;
import com.stayhotel.common.security.test.TestJwtSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Review rules (D-027) and the versioned hotel rating (D-028). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestJwtDecoderConfiguration.class})
class ReviewIntegrationTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    BookingEventsListener listener;
    @Autowired
    KafkaTemplate<String, String> kafka;

    UUID guest;
    UUID hotel;
    UUID booking;

    @BeforeEach
    void setUp() throws Exception {
        guest = UUID.randomUUID();
        hotel = UUID.randomUUID();
        booking = completedStay(guest, hotel);
    }

    private UUID completedStay(UUID user, UUID hotelId) throws Exception {
        UUID b = UUID.randomUUID();
        listener.onMessage(completedEvent(UUID.randomUUID(), b, user, hotelId));
        return b;
    }

    private String completedEvent(UUID eventId, UUID b, UUID user, UUID hotelId) throws Exception {
        return json.writeValueAsString(new EventEnvelope(eventId, "BookingCompleted", 1, Instant.now(), "test",
                b.toString(), json.valueToTree(new ReviewDtos.BookingCompleted(b, user, hotelId,
                java.time.LocalDate.parse("2026-10-01")))));
    }

    private ResponseEntity<String> call(HttpMethod method, String path, UUID who, String role, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (who != null) {
            headers.setBearerAuth(TestJwtSupport.token(who, role));
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> post(UUID who, UUID bookingId, int rating) {
        return call(HttpMethod.POST, "/api/v1/reviews", who, "CUSTOMER",
                Map.of("bookingId", bookingId, "rating", rating, "comment", "Great <script>x</script>"));
    }

    private JsonNode body(ResponseEntity<String> res) throws Exception {
        return json.readTree(res.getBody());
    }

    private String code(ResponseEntity<String> res) throws Exception {
        return body(res).get("code").asText();
    }

    private List<Map<String, Object>> ratingEvents() {
        return jdbc.queryForList("""
                SELECT payload->'payload'->>'avgRating' AS avg, (payload->'payload'->>'reviewCount')::int AS cnt,
                       (payload->'payload'->>'version')::bigint AS ver
                FROM outbox_events WHERE message_key = ? AND event_type = 'HotelRatingChanged' ORDER BY seq""",
                hotel.toString());
    }

    @Test
    void guestWithCompletedStayReviewsOnceAndHotelScoreIsPublished() throws Exception {
        JsonNode eligibility = body(call(HttpMethod.GET, "/api/v1/reviews/eligibility/" + booking, guest, "CUSTOMER", null));
        assertThat(eligibility.get("eligible").asBoolean()).isTrue();

        ResponseEntity<String> created = post(guest, booking, 4);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode review = body(created);
        assertThat(review.get("author").asText()).isEqualTo("Verified guest");
        assertThat(created.getBody()).doesNotContain(guest.toString());

        ResponseEntity<String> again = post(guest, booking, 5);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(again)).isEqualTo("REVIEW_EXISTS");

        eligibility = body(call(HttpMethod.GET, "/api/v1/reviews/eligibility/" + booking, guest, "CUSTOMER", null));
        assertThat(eligibility.get("eligible").asBoolean()).isFalse();
        assertThat(eligibility.get("reviewId").asText()).isEqualTo(review.get("id").asText());

        UUID second = completedStay(UUID.randomUUID(), hotel);
        UUID secondGuest = jdbc.queryForObject("SELECT user_id FROM eligible_stays WHERE booking_id = ?", UUID.class, second);
        assertThat(post(secondGuest, second, 5).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode page = body(call(HttpMethod.GET, "/api/v1/hotels/" + hotel + "/reviews", null, null, null));
        assertThat(page.get("totalElements").asInt()).isEqualTo(2);
        assertThat(page.get("content").findValuesAsText("mine")).containsOnly("false");

        List<Map<String, Object>> events = ratingEvents();
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).containsEntry("avg", "4.5").containsEntry("cnt", 2).containsEntry("ver", 2L);
    }

    @Test
    void onlyTheGuestOfACompletedStayMayReview() throws Exception {
        UUID stranger = UUID.randomUUID();
        ResponseEntity<String> idor = post(stranger, booking, 1);
        assertThat(idor.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(idor)).isEqualTo("REVIEW_NOT_ALLOWED");

        ResponseEntity<String> notCompleted = post(guest, UUID.randomUUID(), 5);
        assertThat(notCompleted.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> manager = call(HttpMethod.POST, "/api/v1/reviews", guest, "HOTEL_MANAGER",
                Map.of("bookingId", booking, "rating", 5));
        assertThat(manager.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> invalid = post(guest, booking, 6);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST, "/api/v1/reviews", null, null, Map.of("bookingId", booking, "rating", 5))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void onlyTheAuthorEditsOrDeletesAndAdminCanModerate() throws Exception {
        String id = body(post(guest, booking, 2)).get("id").asText();
        UUID stranger = UUID.randomUUID();

        assertThat(call(HttpMethod.PUT, "/api/v1/reviews/" + id, stranger, "CUSTOMER", Map.of("rating", 5))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.DELETE, "/api/v1/reviews/" + id, stranger, "CUSTOMER", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> edited = call(HttpMethod.PUT, "/api/v1/reviews/" + id, guest, "CUSTOMER",
                Map.of("rating", 3, "comment", "ok"));
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(edited).get("mine").asBoolean()).isTrue();

        assertThat(call(HttpMethod.PATCH, "/api/v1/admin/reviews/" + id + "/visibility", guest, "CUSTOMER",
                Map.of("status", "HIDDEN")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        UUID admin = UUID.randomUUID();
        ResponseEntity<String> hidden = call(HttpMethod.PATCH, "/api/v1/admin/reviews/" + id + "/visibility", admin, "ADMIN",
                Map.of("status", "HIDDEN", "reason", "spam"));
        assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(call(HttpMethod.GET, "/api/v1/hotels/" + hotel + "/reviews", null, null, null))
                .get("totalElements").asInt()).isZero();
        assertThat(body(call(HttpMethod.GET, "/api/v1/reviews/me", guest, "CUSTOMER", null)).get(0).get("status").asText())
                .isEqualTo("HIDDEN");
        JsonNode queue = body(call(HttpMethod.GET, "/api/v1/admin/reviews?status=HIDDEN&hotelId=" + hotel, admin, "ADMIN", null));
        assertThat(queue.get("content").findValuesAsText("hiddenReason")).containsExactly("spam");
        assertThat(call(HttpMethod.GET, "/api/v1/admin/reviews", guest, "CUSTOMER", null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        List<Map<String, Object>> events = ratingEvents();
        assertThat(events).extracting(e -> e.get("ver")).containsExactly(1L, 2L, 3L);
        assertThat(events.get(2)).containsEntry("cnt", 0).containsEntry("avg", null);

        assertThat(call(HttpMethod.DELETE, "/api/v1/reviews/" + id, admin, "ADMIN", null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(ratingEvents()).hasSize(4);
    }

    @Test
    void concurrentDoubleSubmitCreatesOneReview() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Callable<HttpStatus> submit = () -> HttpStatus.valueOf(post(guest, booking, 5).getStatusCode().value());
            List<Future<HttpStatus>> results = pool.invokeAll(List.of(submit, submit, submit, submit));
            long created = 0;
            for (Future<HttpStatus> f : results) {
                HttpStatus s = f.get();
                assertThat(s).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);
                created += s == HttpStatus.CREATED ? 1 : 0;
            }
            assertThat(created).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews WHERE booking_id = ?", Integer.class, booking)).isOne();
    }

    @Test
    void completedStayEventsAreIdempotentAndConsumedFromTheBroker() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        listener.onMessage(completedEvent(eventId, b, guest, hotel));
        listener.onMessage(completedEvent(eventId, b, guest, hotel));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eligible_stays WHERE booking_id = ?", Integer.class, b)).isOne();

        UUID viaKafka = UUID.randomUUID();
        kafka.send(Topics.BOOKING_EVENTS, viaKafka.toString(), completedEvent(UUID.randomUUID(), viaKafka, guest, hotel)).get();
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM eligible_stays WHERE booking_id = ?", Integer.class,
                viaKafka) == 1, "eligible stay from Kafka");
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
