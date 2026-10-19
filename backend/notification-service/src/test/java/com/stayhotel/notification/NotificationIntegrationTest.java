package com.stayhotel.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.common.messaging.EventEnvelope;
import com.stayhotel.common.messaging.Topics;
import com.stayhotel.common.security.test.TestJwtDecoderConfiguration;
import com.stayhotel.common.security.test.TestJwtSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Event → in-app notification + email (D-029): idempotency, ownership, after-commit sending and bounded retries. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "notification.email-retry-interval=PT1H") // retries driven by the test, not the scheduler
@Import({TestcontainersConfiguration.class, TestJwtDecoderConfiguration.class})
class NotificationIntegrationTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    NotificationEventsListener listener;
    @Autowired
    EmailDispatcher dispatcher;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @MockitoBean
    JavaMailSender mail;

    UUID user;
    UUID booking;

    @BeforeEach
    void setUp() {
        reset(mail);
        user = UUID.randomUUID();
        booking = UUID.randomUUID();
    }

    private String envelope(UUID eventId, String type, Map<String, Object> payload) throws Exception {
        return json.writeValueAsString(new EventEnvelope(eventId, type, 1, Instant.now(), "test", booking.toString(),
                json.valueToTree(payload)));
    }

    private Map<String, Object> created() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("bookingId", booking);
        p.put("userId", user);
        p.put("hotelId", UUID.randomUUID());
        p.put("hotelName", "Sài Gòn Riverside");
        p.put("bookingCode", "SH-TEST01");
        p.put("checkInDate", "2026-12-01");
        p.put("checkOutDate", "2026-12-03");
        p.put("totalAmount", "2268000.00");
        p.put("currency", "VND");
        p.put("contactName", "<b>Lan</b>");
        p.put("contactEmail", "lan@example.test");
        return p;
    }

    private void deliver(UUID eventId, String type, Map<String, Object> payload) throws Exception {
        listener.onMessage(envelope(eventId, type, payload));
    }

    private int rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE booking_id = ?", Integer.class, booking);
    }

    private Map<String, Object> row(String type) {
        return jdbc.queryForMap("SELECT * FROM notifications WHERE booking_id = ? AND type = ?", booking, type);
    }

    private ResponseEntity<String> call(HttpMethod method, String path, UUID who) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.token(who, "CUSTOMER"));
        return http.exchange(path, method, new HttpEntity<>(headers), String.class);
    }

    @Test
    void bookingCreatedStoresContactNotifiesOnceAndEmailsAfterCommit() throws Exception {
        UUID eventId = UUID.randomUUID();
        deliver(eventId, "BookingCreated", created());
        deliver(eventId, "BookingCreated", created()); // duplicate delivery

        assertThat(rows()).isEqualTo(1);
        Map<String, Object> n = row("BOOKING_CREATED");
        assertThat(n.get("user_id")).isEqualTo(user);
        assertThat(n.get("email_status")).isEqualTo("SENT");
        assertThat(n.get("title")).isEqualTo("Booking SH-TEST01 received");
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, times(1)).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("lan@example.test");
        assertThat(sent.getValue().getText()).contains("2.268.000 VND").contains("/bookings/" + booking);
    }

    @Test
    void laterEventsUseTheStoredContactAndIrrelevantOnesAreIgnored() throws Exception {
        deliver(UUID.randomUUID(), "BookingCreated", created());
        deliver(UUID.randomUUID(), "BookingConfirmed", Map.of("bookingId", booking, "paymentId", UUID.randomUUID(),
                "latePayment", false));
        deliver(UUID.randomUUID(), "PaymentSucceeded", Map.of("bookingId", booking, "paymentId", UUID.randomUUID(),
                "amount", "2268000.00", "currency", "VND"));
        deliver(UUID.randomUUID(), "RefundSucceeded", Map.of("bookingId", booking, "paymentId", UUID.randomUUID(),
                "refundId", UUID.randomUUID(), "amount", "1134000.00"));

        assertThat(rows()).isEqualTo(3);
        assertThat(row("BOOKING_CONFIRMED").get("body").toString()).contains("confirmed").contains("SH-TEST01");
        assertThat(row("REFUND_COMPLETED").get("body").toString()).contains("1.134.000 VND");
    }

    @Test
    void eventForUnknownBookingIsSkippedWithoutFailingTheConsumer() throws Exception {
        deliver(UUID.randomUUID(), "BookingConfirmed", Map.of("bookingId", booking, "paymentId", UUID.randomUUID(),
                "latePayment", false));
        assertThat(rows()).isZero();
        verify(mail, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void inAppApiIsScopedToTheOwner() throws Exception {
        deliver(UUID.randomUUID(), "BookingCreated", created());
        deliver(UUID.randomUUID(), "BookingExpired", Map.of("bookingId", booking));

        ResponseEntity<String> mine = call(HttpMethod.GET, "/api/v1/notifications/me", user);
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getHeaders().getFirst("X-Unread-Count")).isEqualTo("2");
        JsonNode page = json.readTree(mine.getBody());
        assertThat(page.get("content").findValuesAsText("type")).containsExactly("BOOKING_EXPIRED", "BOOKING_CREATED");
        assertThat(page.get("content").get(0).get("body").asText()).contains("<b>Lan</b>"); // stored as text; UI escapes
        assertThat(mine.getBody()).doesNotContain("lan@example.test");

        UUID stranger = UUID.randomUUID();
        String someId = page.get("content").get(0).get("id").asText();
        assertThat(json.readTree(call(HttpMethod.GET, "/api/v1/notifications/me", stranger).getBody())
                .get("totalElements").asInt()).isZero();
        assertThat(call(HttpMethod.POST, "/api/v1/notifications/" + someId + "/read", stranger).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(call(HttpMethod.POST, "/api/v1/notifications/" + someId + "/read", user).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.GET, "/api/v1/notifications/me/unread-count", user).getBody()).isEqualTo("{\"count\":1}");
        assertThat(call(HttpMethod.POST, "/api/v1/notifications/me/read-all", user).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.GET, "/api/v1/notifications/me/unread-count", user).getBody()).isEqualTo("{\"count\":0}");
        assertThat(http.getForEntity("/api/v1/notifications/me", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void mailOutageNeverBlocksTheEventAndRetriesAreBounded() throws Exception {
        doThrow(new MailSendException("smtp down")).when(mail).send(any(SimpleMailMessage.class));
        deliver(UUID.randomUUID(), "BookingCreated", created());

        Map<String, Object> n = row("BOOKING_CREATED");
        assertThat(n.get("email_status")).isEqualTo("PENDING");
        assertThat(n.get("email_attempts")).isEqualTo(1);
        assertThat(n.get("last_error")).isEqualTo("MailSendException");

        UUID id = (UUID) n.get("id");
        dispatcher.attempt(id);
        assertThat(row("BOOKING_CREATED").get("email_attempts")).as("not due yet: backoff respected").isEqualTo(1);

        for (int i = 2; i <= EmailDispatcher.MAX_ATTEMPTS + 1; i++) {
            jdbc.update("UPDATE notifications SET next_attempt_at = now() - interval '1 second' WHERE id = ?"
                    + " AND email_status = 'PENDING'", id);
            dispatcher.attempt(id);
        }
        n = row("BOOKING_CREATED");
        assertThat(n.get("email_status")).isEqualTo("FAILED");
        assertThat(n.get("email_attempts")).isEqualTo(EmailDispatcher.MAX_ATTEMPTS);
        verify(mail, times(EmailDispatcher.MAX_ATTEMPTS)).send(any(SimpleMailMessage.class));
    }

    @Test
    void consumesFromTheRealBroker() throws Exception {
        kafka.send(Topics.BOOKING_EVENTS, booking.toString(), envelope(UUID.randomUUID(), "BookingCreated", created())).get();
        kafka.send(Topics.PAYMENT_EVENTS, booking.toString(), envelope(UUID.randomUUID(), "PaymentFailed",
                Map.of("bookingId", booking, "paymentId", UUID.randomUUID(), "reason", "DECLINED"))).get();

        await(() -> rows() == 2, "two notifications from two topics");
        assertThat(row("PAYMENT_FAILED").get("user_id")).isEqualTo(user);
        verify(mail, atLeastOnce()).send(any(SimpleMailMessage.class));
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
