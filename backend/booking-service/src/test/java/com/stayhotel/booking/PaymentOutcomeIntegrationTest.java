package com.stayhotel.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.booking.BookingTestSupport.Api;
import com.stayhotel.booking.BookingTestSupport.FakeHotelCatalog;
import com.stayhotel.booking.BookingTestSupport.MutableClock;
import com.stayhotel.booking.booking.BookingEvents;
import com.stayhotel.booking.booking.BookingLifecycleJob;
import com.stayhotel.booking.booking.PaymentOutcomeHandler;
import com.stayhotel.booking.catalog.HotelSnapshot;
import com.stayhotel.common.messaging.Topics;
import com.stayhotel.common.security.test.TestJwtDecoderConfiguration;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static com.stayhotel.booking.BookingTestSupport.doubleRoom;
import static com.stayhotel.booking.BookingTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

/** Booking side of payments and the late-payment policy (D-024), plus real Kafka delivery, retries and DLT. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestJwtDecoderConfiguration.class, BookingTestSupport.Config.class})
class PaymentOutcomeIntegrationTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper json;
    @Autowired
    FakeHotelCatalog catalog;
    @Autowired
    MutableClock clock;
    @Autowired
    PaymentOutcomeHandler handler;
    @Autowired
    BookingLifecycleJob job;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    KafkaTemplate<String, String> kafka;
    /** The broker the application really uses (the Testcontainers one), not the yml default. */
    @Autowired
    KafkaConnectionDetails kafkaConnection;

    Api alice;
    HotelSnapshot hotel;
    LocalDate checkIn;

    @BeforeEach
    void setUp() {
        clock.set(Instant.now());
        alice = Api.customer(http, json);
        hotel = BookingTestSupport.hotel(1);
        catalog.put(hotel);
        checkIn = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(25);
    }

    private JsonNode book(LocalDate in, int nights) {
        return alice.body(alice.book(request(hotel, in, nights, doubleRoom(hotel), 1, 1)));
    }

    private JsonNode reload(JsonNode booking) {
        return alice.body(alice.get("/api/v1/bookings/" + booking.get("id").asText()));
    }

    private BookingEvents.PaymentSucceeded paid(JsonNode booking, UUID paymentId) {
        return new BookingEvents.PaymentSucceeded(paymentId, UUID.fromString(booking.get("id").asText()),
                booking.get("totalAmount").decimalValue(), "VND");
    }

    private List<String> events(JsonNode booking) {
        return jdbc.queryForList("SELECT event_type FROM outbox_events WHERE message_key = ? ORDER BY seq",
                String.class, booking.get("id").asText());
    }

    private Map<String, Object> lastEventPayload(JsonNode booking, String type) {
        return jdbc.queryForMap("""
                SELECT payload->'payload'->>'reason' AS reason, payload->'payload'->>'latePayment' AS late
                FROM outbox_events WHERE message_key = ? AND event_type = ? ORDER BY created_at DESC LIMIT 1""",
                booking.get("id").asText(), type);
    }

    private int reserved(LocalDate night) {
        List<Integer> v = jdbc.queryForList("SELECT reserved_units FROM room_type_inventory WHERE room_type_id = ? AND stay_date = ?",
                Integer.class, doubleRoom(hotel), night);
        return v.isEmpty() ? 0 : v.get(0);
    }

    @Test
    void onTimePaymentConfirmsOnceEvenIfDeliveredTwice() {
        JsonNode b = book(checkIn, 2);
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        handler.onPaymentSucceeded(eventId, paid(b, paymentId));
        handler.onPaymentSucceeded(eventId, paid(b, paymentId)); // duplicate delivery

        JsonNode after = reload(b);
        assertThat(after.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(after.get("history").findValuesAsText("toStatus")).containsExactly("PENDING_PAYMENT", "CONFIRMED");
        assertThat(events(b)).containsExactly("BookingCreated", "BookingConfirmed");
    }

    @Test
    void latePaymentIsAcceptedWhenEveryNightCanBeReReserved() {
        JsonNode b = book(checkIn.plusDays(3), 2);
        clock.advance(Duration.ofMinutes(16));
        assertThat(reserved(checkIn.plusDays(3))).isEqualTo(1); // job has not run: still held

        handler.onPaymentSucceeded(UUID.randomUUID(), paid(b, UUID.randomUUID()));

        JsonNode after = reload(b);
        assertThat(after.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(after.get("history").findValuesAsText("toStatus")).containsExactly("PENDING_PAYMENT", "EXPIRED", "CONFIRMED");
        assertThat(lastEventPayload(b, "BookingConfirmed").get("late")).isEqualTo("true");
        assertThat(reserved(checkIn.plusDays(3))).as("released by expiry, re-reserved by late payment").isEqualTo(1);
    }

    @Test
    void latePaymentIsRejectedAndNothingStaysPartiallyReservedWhenRoomsAreGone() {
        LocalDate in = checkIn.plusDays(6);
        JsonNode b = book(in, 2);                       // the single unit, nights in and in+1
        clock.advance(Duration.ofMinutes(16));
        job.expireOverdueHolds();                       // hold expires, both nights released
        assertThat(Api.customer(http, json).book(request(hotel, in.plusDays(1), 1, doubleRoom(hotel), 1, 1))
                .getStatusCode().value()).isEqualTo(201); // a competitor takes the second night

        handler.onPaymentSucceeded(UUID.randomUUID(), paid(b, UUID.randomUUID()));

        JsonNode after = reload(b);
        assertThat(after.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(after.get("refundStatus").asText()).isEqualTo("PENDING");
        assertThat(lastEventPayload(b, "BookingPaymentRejected").get("reason")).isEqualTo("NO_AVAILABILITY");
        assertThat(reserved(in)).as("all-or-nothing: the free first night was not taken either").isZero();
        assertThat(reserved(in.plusDays(1))).as("competitor keeps the second night").isEqualTo(1);
    }

    @Test
    void paymentsForCancelledOrAlreadyPaidBookingsAreRejected() {
        JsonNode cancelled = book(checkIn.plusDays(14), 1);
        alice.call(org.springframework.http.HttpMethod.POST, "/api/v1/bookings/" + cancelled.get("id").asText() + "/cancel", Map.of(), null);
        handler.onPaymentSucceeded(UUID.randomUUID(), paid(cancelled, UUID.randomUUID()));
        assertThat(lastEventPayload(cancelled, "BookingPaymentRejected").get("reason")).isEqualTo("BOOKING_CANCELLED");

        JsonNode b = book(checkIn.plusDays(16), 1);
        UUID first = UUID.randomUUID();
        handler.onPaymentSucceeded(UUID.randomUUID(), paid(b, first));
        handler.onPaymentSucceeded(UUID.randomUUID(), paid(b, first)); // same payment, new event: no-op
        handler.onPaymentSucceeded(UUID.randomUUID(), paid(b, UUID.randomUUID()));
        assertThat(lastEventPayload(b, "BookingPaymentRejected").get("reason")).isEqualTo("ALREADY_PAID");
        assertThat(events(b)).containsExactly("BookingCreated", "BookingConfirmed", "BookingPaymentRejected");
    }

    @Test
    void amountMismatchNeverConfirms() {
        JsonNode b = book(checkIn.plusDays(18), 1);
        handler.onPaymentSucceeded(UUID.randomUUID(), new BookingEvents.PaymentSucceeded(UUID.randomUUID(),
                UUID.fromString(b.get("id").asText()), BigDecimal.ONE, "VND"));

        assertThat(reload(b).get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(lastEventPayload(b, "BookingPaymentRejected").get("reason")).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    void realKafkaRoundTripConfirmsAndTracksRefund() throws Exception {
        JsonNode b = book(checkIn.plusDays(20), 1);
        String bookingId = b.get("id").asText();
        UUID paymentId = UUID.randomUUID();
        kafka.send(Topics.PAYMENT_EVENTS, bookingId, envelope("PaymentSucceeded", bookingId, """
                {"paymentId":"%s","bookingId":"%s","amount":%s,"currency":"VND"}""".formatted(paymentId, bookingId,
                b.get("totalAmount").asText()))).get();
        await(() -> "CONFIRMED".equals(reload(b).get("status").asText()), "booking confirmed via Kafka");

        kafka.send(Topics.PAYMENT_EVENTS, bookingId, envelope("RefundSucceeded", bookingId, """
                {"paymentId":"%s","bookingId":"%s","refundId":"%s","amount":1}""".formatted(paymentId, bookingId, UUID.randomUUID()))).get();
        await(() -> "REFUNDED".equals(reload(b).get("refundStatus").asText()), "refund status updated via Kafka");

        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE message_key = ? AND published_at IS NULL",
                Integer.class, bookingId) == 0, "outbox relayed to Kafka");
    }

    /** Production brokers have auto-creation disabled, so the service itself must provision every topic. */
    @Test
    void topicsAndDeadLetterTopicsAreProvisionedAtStartup() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers())))) {
            assertThat(admin.listTopics().names().get()).contains(Topics.PAYMENT_EVENTS, Topics.BOOKING_EVENTS,
                    Topics.PAYMENT_EVENTS + Topics.DLT_SUFFIX, Topics.BOOKING_EVENTS + Topics.DLT_SUFFIX);
        }
    }

    @Test
    void poisonMessageEndsInDeadLetterTopic() throws Exception {
        String marker = "poison-" + UUID.randomUUID();
        kafka.send(Topics.PAYMENT_EVENTS, marker, "{not json " + marker).get();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-check-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.PAYMENT_EVENTS + Topics.DLT_SUFFIX));
            long deadline = System.currentTimeMillis() + 30_000;
            boolean found = false;
            while (!found && System.currentTimeMillis() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    found |= marker.equals(record.key());
                }
            }
            assertThat(found).as("unreadable message moved to %s", Topics.PAYMENT_EVENTS + Topics.DLT_SUFFIX).isTrue();
        }
    }

    private static String envelope(String type, String bookingId, String payload) {
        return """
                {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"%s","correlationId":null,
                 "aggregateId":"%s","payload":%s}""".formatted(UUID.randomUUID(), type, Instant.now(), bookingId, payload);
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
