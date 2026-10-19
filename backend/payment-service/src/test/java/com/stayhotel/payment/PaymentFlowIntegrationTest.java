package com.stayhotel.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.common.security.test.TestJwtDecoderConfiguration;
import com.stayhotel.common.security.test.TestJwtSupport;
import com.stayhotel.payment.booking.BookingClient;
import com.stayhotel.payment.payment.BookingEventsListener;
import com.stayhotel.payment.provider.WebhookSignature;
import com.stayhotel.payment.sandbox.SandboxGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Payment-service end to end inside one JVM: REST API → real sandbox checkout over HTTP → signed webhook over HTTP →
 * state + outbox; booking events → refunds; with a real PostgreSQL and Kafka. Only booking-service is mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestJwtDecoderConfiguration.class})
class PaymentFlowIntegrationTest {

    static final BigDecimal TOTAL = new BigDecimal("2268000");
    static final String SECRET = "test-webhook-secret-0123456789abcdef";

    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SandboxGateway sandbox;
    @Autowired
    BookingEventsListener listener;
    @MockitoBean
    BookingClient bookingClient;

    RestTemplate rest;
    UUID userId;
    String token;
    UUID bookingId;

    @BeforeEach
    void setUp() {
        // No redirect following: the sandbox answers 303 to the merchant's return URL.
        rest = new RestTemplate(new JdkClientHttpRequestFactory(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
        rest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        userId = UUID.randomUUID();
        token = TestJwtSupport.token(userId, "CUSTOMER");
        bookingId = UUID.randomUUID();
        bookingIs(bookingId, "PENDING_PAYMENT", Instant.now().plusSeconds(600));
    }

    void bookingIs(UUID id, String status, Instant expiresAt) {
        when(bookingClient.findForCaller(eq(id), anyString()))
                .thenReturn(Optional.of(new BookingClient.BookingView(id, status, TOTAL, "VND", expiresAt)));
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    ResponseEntity<String> call(HttpMethod method, String path, Object body, String bearer, String key) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            h.setBearerAuth(bearer);
        }
        if (key != null) {
            h.set("Idempotency-Key", key);
        }
        return rest.exchange(url(path), method, new HttpEntity<>(body, h), String.class);
    }

    JsonNode body(ResponseEntity<String> res) {
        try {
            return json.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(res.getBody(), e);
        }
    }

    JsonNode startPayment() {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/payments", Map.of("bookingId", bookingId), token, "key-" + UUID.randomUUID());
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.CREATED);
        return body(res);
    }

    String sessionOf(JsonNode payment) {
        String checkout = payment.get("checkoutUrl").asText();
        return checkout.substring(checkout.lastIndexOf('/') + 1);
    }

    ResponseEntity<String> pressButton(String sessionId, String action) {
        return rest.postForEntity(url("/sandbox-gateway/checkout/" + sessionId + "/" + action), null, String.class);
    }

    JsonNode payment(String id) {
        return body(call(HttpMethod.GET, "/api/v1/payments/" + id, null, token, null));
    }

    List<String> outbox(UUID booking) {
        return jdbc.queryForList("SELECT event_type FROM outbox_events WHERE message_key = ? ORDER BY seq", String.class, booking.toString());
    }

    void bookingEvent(String type, String payload) {
        String envelope = """
                {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"%s","aggregateId":"%s","payload":%s}"""
                .formatted(UUID.randomUUID(), type, Instant.now(), bookingId, payload);
        listener.onMessage(envelope); // same entry point Kafka uses; delivery itself is covered in booking-service tests
    }

    static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    @Nested
    class Checkout {

        @Test
        void amountComesFromBookingAndCheckoutPageNeedsNoScript() {
            JsonNode p = startPayment();
            assertThat(p.get("status").asText()).isEqualTo("PENDING");
            assertThat(p.get("amount").asLong()).isEqualTo(2_268_000);
            assertThat(p.get("checkoutUrl").asText()).matches("http://localhost:4200/sandbox-gateway/checkout/cs_[0-9a-f]{24}");

            ResponseEntity<String> page = rest.getForEntity(url("/sandbox-gateway/checkout/" + sessionOf(p)), String.class);
            assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(page.getBody()).contains("2.268.000 VND").contains("SANDBOX").doesNotContain("<script");
        }

        @Test
        void payingSendsSignedWebhookThenRedirectsAndConfirmsExactlyOnce() {
            JsonNode p = startPayment();
            String session = sessionOf(p);

            ResponseEntity<String> redirect = pressButton(session, "pay");
            assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
            assertThat(redirect.getHeaders().getLocation()).hasToString("http://localhost:4200/bookings/" + bookingId + "?payment=return");

            JsonNode after = payment(p.get("id").asText());
            assertThat(after.get("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(after.get("checkoutUrl").isNull()).isTrue();
            assertThat(outbox(bookingId)).containsExactly("PaymentSucceeded");

            sandbox.redeliver(session); // provider retries the same event
            sandbox.redeliver(session);
            assertThat(outbox(bookingId)).as("duplicate webhooks change nothing").containsExactly("PaymentSucceeded");
            assertThat(pressButton(session, "pay").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void declineFailsThePaymentAndAllowsANewAttempt() {
            JsonNode first = startPayment();
            pressButton(sessionOf(first), "decline");

            JsonNode failed = payment(first.get("id").asText());
            assertThat(failed.get("status").asText()).isEqualTo("FAILED");
            assertThat(failed.get("failureReason").asText()).contains("declined");
            assertThat(outbox(bookingId)).containsExactly("PaymentFailed");

            JsonNode second = startPayment();
            assertThat(second.get("id")).isNotEqualTo(first.get("id"));
        }

        @Test
        void idempotencyAndSinglePendingPayment() {
            ResponseEntity<String> first = call(HttpMethod.POST, "/api/v1/payments", Map.of("bookingId", bookingId), token, "pay-key-00001");
            ResponseEntity<String> replay = call(HttpMethod.POST, "/api/v1/payments", Map.of("bookingId", bookingId), token, "pay-key-00001");
            ResponseEntity<String> otherKey = call(HttpMethod.POST, "/api/v1/payments", Map.of("bookingId", bookingId), token, "pay-key-00002");

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(body(replay).get("id")).isEqualTo(body(first).get("id"));
            assertThat(body(otherKey).get("id")).as("open checkout is resumed, not duplicated").isEqualTo(body(first).get("id"));

            UUID otherBooking = UUID.randomUUID();
            bookingIs(otherBooking, "PENDING_PAYMENT", Instant.now().plusSeconds(600));
            ResponseEntity<String> reused = call(HttpMethod.POST, "/api/v1/payments", Map.of("bookingId", otherBooking), token, "pay-key-00001");
            assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            pressButton(sessionOf(body(first)), "pay");
            ResponseEntity<String> again = call(HttpMethod.POST, "/api/v1/payments", Map.of("bookingId", bookingId), token, "pay-key-00003");
            assertThat(again.getStatusCode()).as("no double charge").isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void unpayableOrForeignBookingsAreRefused() {
            UUID expired = UUID.randomUUID();
            bookingIs(expired, "PENDING_PAYMENT", Instant.now().plusSeconds(10));
            assertThat(body(call(HttpMethod.POST, "/api/v1/payments", Map.of("bookingId", expired), token, "k-expired-1")).get("code").asText())
                    .isEqualTo("BOOKING_NOT_PAYABLE");
            UUID confirmed = UUID.randomUUID();
            bookingIs(confirmed, "CONFIRMED", null);
            assertThat(call(HttpMethod.POST, "/api/v1/payments", Map.of("bookingId", confirmed), token, "k-confirmed-1").getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
            when(bookingClient.findForCaller(any(), anyString())).thenReturn(Optional.empty());
            assertThat(call(HttpMethod.POST, "/api/v1/payments", Map.of("bookingId", UUID.randomUUID()), token, "k-foreign-1").getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(call(HttpMethod.POST, "/api/v1/payments", Map.of("bookingId", bookingId), null, "k-anon-0001").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void otherUsersCannotSeePayments() {
            JsonNode p = startPayment();
            String stranger = TestJwtSupport.token(UUID.randomUUID(), "CUSTOMER");
            assertThat(call(HttpMethod.GET, "/api/v1/payments/" + p.get("id").asText(), null, stranger, null).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(body(call(HttpMethod.GET, "/api/v1/payments/booking/" + bookingId, null, stranger, null))).isEmpty();
            assertThat(body(call(HttpMethod.GET, "/api/v1/payments/booking/" + bookingId, null, token, null))).hasSize(1);
        }
    }

    @Nested
    class Webhooks {

        ResponseEntity<String> postWebhook(String body, long timestamp, String secret) {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            h.set(WebhookSignature.TIMESTAMP_HEADER, Long.toString(timestamp));
            h.set(WebhookSignature.SIGNATURE_HEADER, WebhookSignature.sign(secret, timestamp, body));
            return rest.exchange(url("/api/v1/payments/webhooks/sandbox"), HttpMethod.POST, new HttpEntity<>(body, h), String.class);
        }

        String succeededEvent(JsonNode p, String amount) {
            return """
                    {"eventId":"evt_%s","type":"payment.succeeded","sessionId":"%s","merchantReference":"%s",
                     "amount":%s,"currency":"VND","occurredAt":"%s"}""".formatted(UUID.randomUUID(), sessionOf(p),
                    p.get("id").asText(), amount, Instant.now());
        }

        @Test
        void forgedOrReplayedWebhooksAreRejected() {
            JsonNode p = startPayment();
            String event = succeededEvent(p, "2268000");
            long now = Instant.now().getEpochSecond();

            assertThat(postWebhook(event, now, "attacker-secret-0123456789abcdefgh").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(postWebhook(event, now - 3600, SECRET).getStatusCode()).as("old timestamp").isEqualTo(HttpStatus.UNAUTHORIZED);
            HttpHeaders none = new HttpHeaders();
            none.setContentType(MediaType.APPLICATION_JSON);
            assertThat(rest.exchange(url("/api/v1/payments/webhooks/sandbox"), HttpMethod.POST, new HttpEntity<>(event, none), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(payment(p.get("id").asText()).get("status").asText()).isEqualTo("PENDING");

            ResponseEntity<String> ok = postWebhook(event, now, SECRET);
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(body(postWebhook(event, now, SECRET)).get("duplicate").asBoolean()).isTrue();
            assertThat(payment(p.get("id").asText()).get("status").asText()).isEqualTo("SUCCEEDED");
        }

        @Test
        void amountMismatchIsFlaggedNotConfirmed() {
            JsonNode p = startPayment();
            postWebhook(succeededEvent(p, "1"), Instant.now().getEpochSecond(), SECRET);

            JsonNode after = payment(p.get("id").asText());
            assertThat(after.get("status").asText()).isEqualTo("PENDING");
            assertThat(after.get("needsReview").asBoolean()).isTrue();
            assertThat(outbox(bookingId)).isEmpty();

            // FR-ADMIN-04: the flagged payment shows up in the admin monitoring list, which only admins can read
            String admin = TestJwtSupport.token(UUID.randomUUID(), "ADMIN");
            JsonNode flagged = body(call(HttpMethod.GET, "/api/v1/admin/payments?needsReview=true&size=100", null, admin, null));
            assertThat(flagged.get("content").findValuesAsText("id")).contains(p.get("id").asText());
            assertThat(flagged.get("content").findValuesAsText("needsReview")).containsOnly("true");
            assertThat(call(HttpMethod.GET, "/api/v1/admin/payments", null, token, null).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    class Refunds {

        int refundRows(String paymentId) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM refunds WHERE payment_id = ?::uuid", Integer.class, paymentId);
        }

        int providerRefunds(String paymentId) {
            return jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sandbox_refunds r JOIN sandbox_sessions s ON s.id = r.session_id
                    WHERE s.merchant_reference = ?::uuid""", Integer.class, paymentId);
        }

        String paidPayment() {
            JsonNode p = startPayment();
            pressButton(sessionOf(p), "pay");
            return p.get("id").asText();
        }

        @Test
        void eligibleCancellationRefundsOnceEvenIfEventsAreDuplicated() throws Exception {
            String id = paidPayment();

            bookingEvent("BookingCancelled", "{\"bookingId\":\"%s\",\"refundEligible\":true}".formatted(bookingId));
            await(() -> "REFUNDED".equals(payment(id).get("status").asText()), "refund completed through the provider");
            bookingEvent("BookingCancelled", "{\"bookingId\":\"%s\",\"refundEligible\":true}".formatted(bookingId));
            // A retry reaching the provider again with the same idempotency key must not move money twice.
            String refundId = jdbc.queryForObject("SELECT id::text FROM refunds WHERE payment_id = ?::uuid", String.class, id);
            String session = jdbc.queryForObject("SELECT provider_reference FROM payments WHERE id = ?::uuid", String.class, id);
            sandbox.requestRefund(refundId, session, TOTAL);

            JsonNode p = payment(id);
            assertThat(p.get("refund").get("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(refundRows(id)).isEqualTo(1);
            assertThat(providerRefunds(id)).as("money moved once at the provider").isEqualTo(1);
            assertThat(outbox(bookingId)).containsExactly("PaymentSucceeded", "RefundRequested", "RefundSucceeded");
        }

        @Test
        void nonRefundableCancellationRefundsNothing() {
            String id = paidPayment();
            bookingEvent("BookingCancelled", "{\"bookingId\":\"%s\",\"refundEligible\":false}".formatted(bookingId));
            assertThat(payment(id).get("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(refundRows(id)).isZero();
        }

        @Test
        void rejectedLatePaymentIsRefundedAndFlagged() throws Exception {
            String id = paidPayment();
            bookingEvent("BookingPaymentRejected", "{\"bookingId\":\"%s\",\"paymentId\":\"%s\",\"reason\":\"NO_AVAILABILITY\"}"
                    .formatted(bookingId, id));

            await(() -> "REFUNDED".equals(payment(id).get("status").asText()), "automatic refund");
            assertThat(payment(id).get("needsReview").asBoolean()).isTrue();
        }

        @Test
        void onlyAdminsRefundManuallyAndOnlyCapturedPayments() throws Exception {
            String id = paidPayment();
            Map<String, String> body = Map.of("reason", "Goodwill");
            assertThat(call(HttpMethod.POST, "/api/v1/payments/" + id + "/refund", body, token, null).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            String admin = TestJwtSupport.token(UUID.randomUUID(), "ADMIN");
            ResponseEntity<String> accepted = call(HttpMethod.POST, "/api/v1/payments/" + id + "/refund", body, admin, null);
            assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            await(() -> "REFUNDED".equals(payment(id).get("status").asText()), "admin refund");
            assertThat(call(HttpMethod.POST, "/api/v1/payments/" + id + "/refund", body, admin, null).getStatusCode())
                    .as("second manual refund is a no-op").isEqualTo(HttpStatus.ACCEPTED);
            assertThat(refundRows(id)).isEqualTo(1);

            JsonNode pending = startPaymentFor(UUID.randomUUID());
            assertThat(call(HttpMethod.POST, "/api/v1/payments/" + pending.get("id").asText() + "/refund", body, admin, null)
                    .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        JsonNode startPaymentFor(UUID booking) {
            bookingIs(booking, "PENDING_PAYMENT", Instant.now().plusSeconds(600));
            return body(call(HttpMethod.POST, "/api/v1/payments", Map.of("bookingId", booking), token, "k-" + UUID.randomUUID()));
        }
    }
}
