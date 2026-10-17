package com.stayhotel.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.booking.BookingTestSupport.Api;
import com.stayhotel.booking.BookingTestSupport.FakeHotelCatalog;
import com.stayhotel.booking.BookingTestSupport.MutableClock;
import com.stayhotel.booking.booking.BookingLifecycleJob;
import com.stayhotel.booking.catalog.HotelSnapshot;
import com.stayhotel.common.security.test.TestJwtDecoderConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.stayhotel.booking.BookingTestSupport.doubleRoom;
import static com.stayhotel.booking.BookingTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-04 / BR-11: no overbooking under concurrency. Requests are fired at the same instant from many threads
 * against a real PostgreSQL; the assertions check both the HTTP outcomes and the persisted counters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestJwtDecoderConfiguration.class, BookingTestSupport.Config.class})
class BookingConcurrencyTest {

    static final int THREADS = 12;

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

    HotelSnapshot hotel;
    LocalDate checkIn;

    @BeforeEach
    void setUp() {
        clock.set(Instant.now());
        hotel = BookingTestSupport.hotel(3); // three "Double" units
        catalog.put(hotel);
        checkIn = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(20);
    }

    private <T> List<T> runConcurrently(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private int reserved(LocalDate night) {
        List<Integer> value = jdbc.queryForList(
                "SELECT reserved_units FROM room_type_inventory WHERE room_type_id = ? AND stay_date = ?",
                Integer.class, doubleRoom(hotel), night);
        return value.isEmpty() ? 0 : value.get(0); // no row yet means nothing was ever reserved that night
    }

    @RepeatedTest(3)
    void manyCustomersRacingForThreeUnitsNeverOverbook() throws Exception {
        List<ResponseEntity<String>> results = runConcurrently(THREADS, () ->
                Api.customer(http, json).book(request(hotel, checkIn, 3, doubleRoom(hotel), 1, 2)));

        assertThat(results).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED).hasSize(3);
        assertThat(results).filteredOn(r -> r.getStatusCode() != HttpStatus.CREATED)
                .allSatisfy(r -> assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.CONFLICT));
        for (int i = 0; i < 3; i++) {
            assertThat(reserved(checkIn.plusDays(i))).isEqualTo(3);
        }
        Integer bookings = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bookings b JOIN booking_rooms r ON r.booking_id = b.id
                WHERE r.room_type_id = ? AND b.status = 'PENDING_PAYMENT'""", Integer.class, doubleRoom(hotel));
        assertThat(bookings).isEqualTo(3);
    }

    @Test
    void overlappingStaysCompeteOnlyForSharedNights() throws Exception {
        // 3 units. Half the requests book nights 0-1, half nights 1-2: night 1 is shared by everyone.
        AtomicInteger counter = new AtomicInteger();
        List<ResponseEntity<String>> results = new ArrayList<>(runConcurrently(THREADS, () -> {
            boolean early = counter.getAndIncrement() % 2 == 0;
            return Api.customer(http, json).book(request(hotel, early ? checkIn : checkIn.plusDays(1), 2, doubleRoom(hotel), 1, 1));
        }));

        assertThat(results).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED).hasSize(3);
        assertThat(reserved(checkIn.plusDays(1))).isEqualTo(3);
        assertThat(reserved(checkIn) + reserved(checkIn.plusDays(2))).isEqualTo(3);
    }

    @Test
    void sameIdempotencyKeyFromParallelRetriesCreatesOneBooking() throws Exception {
        Api alice = Api.customer(http, json);
        Map<String, Object> body = request(hotel, checkIn.plusDays(5), 1, doubleRoom(hotel), 1, 1);

        List<ResponseEntity<String>> results = runConcurrently(8, () ->
                alice.call(HttpMethod.POST, "/api/v1/bookings", body, "double-click-key-1"));

        assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode().is2xxSuccessful()).as(r.getBody()).isTrue());
        assertThat(results.stream().map(r -> alice.body(r).get("id").asText()).distinct()).hasSize(1);
        assertThat(reserved(checkIn.plusDays(5))).isEqualTo(1);
    }

    @Test
    void cancelRacingWithExpiryProducesExactlyOneTransition() throws Exception {
        Api alice = Api.customer(http, json);
        String id = alice.body(alice.book(request(hotel, checkIn.plusDays(8), 1, doubleRoom(hotel), 1, 1))).get("id").asText();
        clock.advance(Duration.ofMinutes(16));

        AtomicInteger counter = new AtomicInteger();
        List<Object> outcomes = runConcurrently(2, () -> counter.getAndIncrement() == 0
                ? job.expireOverdueHolds()
                : alice.call(HttpMethod.POST, "/api/v1/bookings/" + id + "/cancel", Map.of(), null).getStatusCode());

        Integer transitions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM booking_status_history WHERE booking_id = ?::uuid AND from_status = 'PENDING_PAYMENT'",
                Integer.class, id);
        assertThat(transitions).as(outcomes.toString()).isEqualTo(1);
        assertThat(reserved(checkIn.plusDays(8))).as("released exactly once").isZero();
    }
}
