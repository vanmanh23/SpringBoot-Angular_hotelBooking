package com.stayhotel.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.booking.BookingTestSupport.Api;
import com.stayhotel.booking.BookingTestSupport.FakeHotelCatalog;
import com.stayhotel.booking.BookingTestSupport.MutableClock;
import com.stayhotel.booking.booking.BookingLifecycleJob;
import com.stayhotel.booking.booking.BookingService;
import com.stayhotel.booking.catalog.HotelSnapshot;
import com.stayhotel.common.security.test.TestJwtDecoderConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.stayhotel.booking.BookingTestSupport.doubleRoom;
import static com.stayhotel.booking.BookingTestSupport.familyRoom;
import static com.stayhotel.booking.BookingTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestJwtDecoderConfiguration.class, BookingTestSupport.Config.class})
class BookingFlowIntegrationTest {

    static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper json;
    @Autowired
    FakeHotelCatalog catalog;
    @Autowired
    MutableClock clock;
    @Autowired
    BookingLifecycleJob job;
    @Autowired
    BookingService bookingService;

    Api alice;
    HotelSnapshot hotel;

    @BeforeEach
    void setUp() {
        clock.set(Instant.now());
        catalog.down.set(false);
        alice = Api.customer(http, json);
        hotel = BookingTestSupport.hotel(1);
        catalog.put(hotel);
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), HCM);
    }

    int availableDouble(LocalDate checkIn, int nights) {
        ResponseEntity<String> res = http.getForEntity("/api/v1/availability?hotelId=" + hotel.id() + "&checkIn=" + checkIn
                + "&checkOut=" + checkIn.plusDays(nights), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode rt : alice.body(res).get("roomTypes")) {
            if (rt.get("roomTypeId").asText().equals(doubleRoom(hotel).toString())) {
                return rt.get("availableUnits").asInt();
            }
        }
        throw new AssertionError("double room missing");
    }

    @Nested
    class Creation {

        @Test
        void createsPendingBookingWithBackendPriceAndReservesUnits() {
            LocalDate in = today().plusDays(10);
            Map<String, Object> body = request(hotel, in, 2, doubleRoom(hotel), 1, 2);
            body.put("totalAmount", 1);          // must be ignored (BR-12)
            body.put("subtotal", 1);

            ResponseEntity<String> res = alice.book(body);

            assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.CREATED);
            JsonNode b = alice.body(res);
            assertThat(b.get("status").asText()).isEqualTo("PENDING_PAYMENT");
            assertThat(b.get("bookingCode").asText()).matches("SH-\\d{6}-[0-9A-Z]{8}");
            assertThat(b.get("subtotal").asLong()).isEqualTo(2_000_000);
            assertThat(b.get("feeAmount").asLong()).isEqualTo(100_000);
            assertThat(b.get("taxAmount").asLong()).isEqualTo(168_000);
            assertThat(b.get("totalAmount").asLong()).isEqualTo(2_268_000);
            assertThat(Instant.parse(b.get("expiresAt").asText())).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
            assertThat(b.get("history").get(0).get("toStatus").asText()).isEqualTo("PENDING_PAYMENT");
            assertThat(availableDouble(in, 2)).isZero();
        }

        @Test
        void lastRoomCannotBeBookedTwice() {
            LocalDate in = today().plusDays(11);
            assertThat(alice.book(request(hotel, in, 2, doubleRoom(hotel), 1, 2)).getStatusCode()).isEqualTo(HttpStatus.CREATED);

            ResponseEntity<String> second = Api.customer(http, json).book(request(hotel, in.plusDays(1), 1, doubleRoom(hotel), 1, 1));
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(alice.body(second).get("code").asText()).isEqualTo("ROOM_SOLD_OUT");

            ResponseEntity<String> nonOverlapping = Api.customer(http, json).book(request(hotel, in.plusDays(2), 1, doubleRoom(hotel), 1, 1));
            assertThat(nonOverlapping.getStatusCode()).as("check-out day is free again").isEqualTo(HttpStatus.CREATED);
        }

        @Test
        void idempotencyKeyReplaysSameRequestAndRejectsDifferentOne() {
            Map<String, Object> body = request(hotel, today().plusDays(12), 1, familyRoom(hotel), 1, 2);
            ResponseEntity<String> first = alice.call(POST, "/api/v1/bookings", body, "client-key-0001");
            ResponseEntity<String> replay = alice.call(POST, "/api/v1/bookings", body, "client-key-0001");

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
            assertThat(alice.body(replay).get("id")).isEqualTo(alice.body(first).get("id"));

            body.put("adults", 3);
            ResponseEntity<String> changed = alice.call(POST, "/api/v1/bookings", body, "client-key-0001");
            assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(alice.body(changed).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

            ResponseEntity<String> otherUser = Api.customer(http, json).call(POST, "/api/v1/bookings",
                    request(hotel, today().plusDays(12), 1, familyRoom(hotel), 1, 2), "client-key-0001");
            assertThat(otherUser.getStatusCode()).as("keys are scoped per user").isEqualTo(HttpStatus.CREATED);
        }

        @Test
        void idempotencyKeyIsRequired() {
            ResponseEntity<String> res = alice.call(POST, "/api/v1/bookings", request(hotel, today().plusDays(5), 1,
                    familyRoom(hotel), 1, 2), null);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(alice.body(res).get("fieldErrors").findValuesAsText("field")).containsExactly("idempotencyKey");
        }

        @Test
        void onlyCustomersCanBook() {
            Map<String, Object> body = request(hotel, today().plusDays(5), 1, familyRoom(hotel), 1, 2);
            assertThat(Api.withRoles(http, json, "HOTEL_MANAGER").book(body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            HttpEntity<Map<String, Object>> anonymous = new HttpEntity<>(body);
            assertThat(http.exchange("/api/v1/bookings", HttpMethod.POST, anonymous, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Nested
    class Validation {

        private void expect400(Map<String, Object> body, String field) {
            ResponseEntity<String> res = alice.book(body);
            assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(alice.body(res).get("fieldErrors").findValuesAsText("field")).contains(field);
        }

        @Test
        void stayRules() {
            LocalDate in = today().plusDays(3);
            expect400(request(hotel, in, 0, familyRoom(hotel), 1, 2), "checkOutDate");                 // BR-01
            expect400(request(hotel, in, 31, familyRoom(hotel), 1, 2), "checkOutDate");                // BR-04
            expect400(request(hotel, today().minusDays(1), 2, familyRoom(hotel), 1, 2), "checkInDate"); // BR-05
            expect400(request(hotel, today().plusDays(366), 2, familyRoom(hotel), 1, 2), "checkInDate");
            expect400(request(hotel, in, 2, familyRoom(hotel), 2, 1), "adults");                       // room without adult

            Map<String, Object> duplicate = request(hotel, in, 2, familyRoom(hotel), 1, 2);
            duplicate.put("rooms", List.of(Map.of("roomTypeId", familyRoom(hotel), "quantity", 1),
                    Map.of("roomTypeId", familyRoom(hotel), "quantity", 1)));
            expect400(duplicate, "rooms");

            Map<String, Object> bad = request(hotel, in, 2, familyRoom(hotel), 1, 2);
            bad.put("contactEmail", "not-an-email");
            bad.put("adults", 0);
            expect400(bad, "contactEmail");
        }

        @Test
        void businessRulesAgainstCatalogue() {
            LocalDate in = today().plusDays(4);
            ResponseEntity<String> capacity = alice.book(request(hotel, in, 1, doubleRoom(hotel), 1, 3)); // BR-02/07
            assertThat(alice.body(capacity).get("code").asText()).isEqualTo("CAPACITY_EXCEEDED");

            ResponseEntity<String> foreignType = alice.book(request(hotel, in, 1, UUID.randomUUID(), 1, 1));
            assertThat(alice.body(foreignType).get("code").asText()).isEqualTo("ROOM_TYPE_NOT_AVAILABLE");

            catalog.remove(hotel.id()); // hotel deactivated: hotel-service answers 404
            ResponseEntity<String> inactive = alice.book(request(hotel, in, 1, familyRoom(hotel), 1, 1));
            assertThat(inactive.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(alice.body(inactive).get("code").asText()).isEqualTo("HOTEL_NOT_AVAILABLE");
        }

        @Test
        void catalogueOutageIs503AndReservesNothing() {
            catalog.down.set(true);
            ResponseEntity<String> res = alice.book(request(hotel, today().plusDays(6), 1, doubleRoom(hotel), 1, 1));
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(alice.body(res).get("code").asText()).isEqualTo("HOTEL_SERVICE_UNAVAILABLE");
            catalog.down.set(false);
            assertThat(availableDouble(today().plusDays(6), 1)).isEqualTo(1);
        }
    }

    @Nested
    class QuoteAndSnapshot {

        @Test
        void quotePricesWithoutReserving() {
            LocalDate in = today().plusDays(20);
            ResponseEntity<String> res = alice.call(POST, "/api/v1/bookings/quote",
                    request(hotel, in, 3, familyRoom(hotel), 2, 4), null);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode q = alice.body(res);
            assertThat(q.get("subtotal").asLong()).isEqualTo(9_000_000);  // 1.5M x 2 x 3
            assertThat(q.get("totalAmount").asLong()).isEqualTo(10_206_000);
            assertThat(q.get("available").asBoolean()).isTrue();
            assertThat(alice.book(request(hotel, in, 3, doubleRoom(hotel), 1, 2)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(alice.body(alice.call(POST, "/api/v1/bookings/quote", request(hotel, in, 3, doubleRoom(hotel), 1, 2), null))
                    .get("available").asBoolean()).isFalse();
        }

        @Test
        void laterPriceChangesDoNotAlterExistingBookings() {
            JsonNode booked = alice.body(alice.book(request(hotel, today().plusDays(30), 1, familyRoom(hotel), 1, 2)));
            var expensive = new HotelSnapshot(hotel.id(), hotel.name(), hotel.timeZone(), hotel.checkInTime(),
                    new BigDecimal("10.00"), new BigDecimal("10.00"), List.of(hotel.roomTypes().get(0),
                    new HotelSnapshot.RoomTypeSnapshot(familyRoom(hotel), "Family Deluxe", 4, new BigDecimal("9000000"), "VND", 5)));
            catalog.put(expensive);

            JsonNode reread = alice.body(alice.get("/api/v1/bookings/" + booked.get("id").asText()));
            assertThat(reread.get("totalAmount")).isEqualTo(booked.get("totalAmount"));
            assertThat(reread.get("taxRatePercent").decimalValue()).isEqualByComparingTo("8");
            assertThat(reread.get("lines").get(0).get("roomTypeName").asText()).isEqualTo("Family");
            assertThat(reread.get("lines").get(0).get("unitPrice").asLong()).isEqualTo(1_500_000);
        }
    }

    @Nested
    class Ownership {

        @Test
        void othersGet404AdminsCanSee() {
            String id = alice.body(alice.book(request(hotel, today().plusDays(7), 1, familyRoom(hotel), 1, 2))).get("id").asText();
            Api mallory = Api.customer(http, json);

            assertThat(mallory.get("/api/v1/bookings/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(mallory.get("/api/v1/bookings/" + id + "/status").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(mallory.call(POST, "/api/v1/bookings/" + id + "/cancel", Map.of(), null).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(mallory.body(mallory.get("/api/v1/bookings/me")).get("totalElements").asInt()).isZero();
            assertThat(Api.withRoles(http, json, "ADMIN").get("/api/v1/bookings/" + id).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void myBookingsAreNewestFirstAndFilterable() {
            Api bob = Api.customer(http, json);
            bob.book(request(hotel, today().plusDays(40), 1, familyRoom(hotel), 1, 1));
            clock.advance(Duration.ofSeconds(1));
            String second = bob.body(bob.book(request(hotel, today().plusDays(41), 1, familyRoom(hotel), 1, 1))).get("id").asText();
            bob.call(POST, "/api/v1/bookings/" + second + "/cancel", Map.of(), null);

            JsonNode all = bob.body(bob.get("/api/v1/bookings/me"));
            assertThat(all.get("totalElements").asInt()).isEqualTo(2);
            assertThat(all.get("content").get(0).get("id").asText()).isEqualTo(second);
            assertThat(bob.body(bob.get("/api/v1/bookings/me?status=CANCELLED")).get("totalElements").asInt()).isEqualTo(1);
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void unpaidHoldExpiresAndReleasesUnits() {
            LocalDate in = today().plusDays(14);
            String id = alice.body(alice.book(request(hotel, in, 2, doubleRoom(hotel), 1, 2))).get("id").asText();
            assertThat(job.expireOverdueHolds()).as("not overdue yet").isZero();

            clock.advance(Duration.ofMinutes(16));
            assertThat(job.expireOverdueHolds()).as("holds of other tests may expire too").isGreaterThanOrEqualTo(1);

            JsonNode b = alice.body(alice.get("/api/v1/bookings/" + id));
            assertThat(b.get("status").asText()).isEqualTo("EXPIRED");
            assertThat(b.get("history").findValuesAsText("toStatus")).containsExactly("PENDING_PAYMENT", "EXPIRED");
            assertThat(availableDouble(in, 2)).isEqualTo(1);

            ResponseEntity<String> cancel = alice.call(POST, "/api/v1/bookings/" + id + "/cancel", Map.of(), null);
            assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(alice.body(cancel).get("code").asText()).isEqualTo("INVALID_STATUS_TRANSITION");
        }

        @Test
        void cancellingAPendingBookingReleasesUnitsWithoutRefund() {
            LocalDate in = today().plusDays(15);
            String id = alice.body(alice.book(request(hotel, in, 1, doubleRoom(hotel), 1, 1))).get("id").asText();

            ResponseEntity<String> res = alice.call(POST, "/api/v1/bookings/" + id + "/cancel", Map.of("reason", "Plans changed"), null);

            JsonNode b = alice.body(res);
            assertThat(b.get("status").asText()).isEqualTo("CANCELLED");
            assertThat(b.get("cancelReason").asText()).isEqualTo("Plans changed");
            assertThat(b.get("refundEligible").isNull()).isTrue();
            assertThat(availableDouble(in, 1)).isEqualTo(1);
        }

        @Test
        void confirmedCancellationRefundEligibilityFollows48HourRule() {
            LocalDate in = today().plusDays(9);
            Instant checkInMoment = in.atTime(LocalTime.of(14, 0)).atZone(HCM).toInstant();
            String early = confirmedBooking(in);
            String late = confirmedBooking(in);
            String tooLate = confirmedBooking(in);

            clock.set(checkInMoment.minus(Duration.ofHours(49)));
            assertThat(alice.body(cancel(early)).get("refundEligible").asBoolean()).isTrue();

            clock.set(checkInMoment.minus(Duration.ofHours(47)));
            assertThat(alice.body(cancel(late)).get("refundEligible").asBoolean()).isFalse();

            clock.set(checkInMoment.plus(Duration.ofMinutes(1)));
            ResponseEntity<String> refused = cancel(tooLate);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(alice.body(refused).get("code").asText()).isEqualTo("CANCELLATION_NOT_ALLOWED");
        }

        @Test
        void latePaymentForOverdueHoldIsNotConfirmed() {
            String id = alice.body(alice.book(request(hotel, today().plusDays(16), 1, familyRoom(hotel), 1, 1))).get("id").asText();
            clock.advance(Duration.ofMinutes(15).plusSeconds(1)); // DB timestamps are microsecond-rounded

            ResponseEntity<String> attempt = confirmVia(id);
            assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(alice.body(alice.get("/api/v1/bookings/" + id)).get("status").asText()).isEqualTo("EXPIRED");
        }

        @Test
        void finishedStaysAreCompletedOnce() {
            LocalDate in = today().plusDays(2);
            String id = confirmedBooking(in);
            clock.set(in.plusDays(2).atStartOfDay(HCM).toInstant()); // day after check-out (1 night stay)

            assertThat(job.completeFinishedStays()).isGreaterThanOrEqualTo(1);
            assertThat(alice.body(alice.get("/api/v1/bookings/" + id)).get("status").asText()).isEqualTo("COMPLETED");
            assertThat(alice.body(cancel(id)).get("code").asText()).isEqualTo("INVALID_STATUS_TRANSITION");
        }

        private String confirmedBooking(LocalDate in) {
            String id = alice.body(alice.book(request(hotel, in, 1, familyRoom(hotel), 1, 1))).get("id").asText();
            assertThat(confirmVia(id).getStatusCode()).isEqualTo(HttpStatus.OK);
            return id;
        }

        /** Confirmation has no public endpoint (it will come from verified payments in M4); call the service. */
        private ResponseEntity<String> confirmVia(String id) {
            try {
                bookingService.confirmPaid(UUID.fromString(id), "TEST-PAYMENT");
                return ResponseEntity.ok().build();
            } catch (com.stayhotel.common.error.BusinessException e) {
                return ResponseEntity.status(e.getStatus()).build();
            }
        }

        private ResponseEntity<String> cancel(String id) {
            return alice.call(POST, "/api/v1/bookings/" + id + "/cancel", Map.of(), null);
        }
    }

    @Test
    void availabilityIsPublicAndValidated() {
        assertThat(http.getForEntity("/api/v1/availability?hotelId=" + hotel.id() + "&checkIn=" + today().plusDays(1)
                + "&checkOut=" + today().plusDays(1), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/availability?hotelId=" + UUID.randomUUID() + "&checkIn=" + today().plusDays(1)
                + "&checkOut=" + today().plusDays(2), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(alice.call(GET, "/api/v1/bookings/me", null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
