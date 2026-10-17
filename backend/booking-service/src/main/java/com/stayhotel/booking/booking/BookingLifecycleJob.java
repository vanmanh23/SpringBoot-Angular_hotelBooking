package com.stayhotel.booking.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Time-driven transitions: overdue holds → EXPIRED (releases units, BR-19/20) and finished stays → COMPLETED.
 * Rows are taken with FOR UPDATE SKIP LOCKED, so several instances can run it and a concurrent cancel wins cleanly.
 */
@Component
public class BookingLifecycleJob {

    private static final Logger log = LoggerFactory.getLogger(BookingLifecycleJob.class);
    static final int BATCH = 100;

    private final BookingRepository bookings;
    private final BookingService service;
    private final Clock clock;

    BookingLifecycleJob(BookingRepository bookings, BookingService service, Clock clock) {
        this.bookings = bookings;
        this.service = service;
        this.clock = clock;
    }

    @Transactional
    public int expireOverdueHolds() {
        Instant now = clock.instant();
        List<Booking> overdue = bookings.lockOverdueHolds(now, BATCH);
        overdue.forEach(b -> service.expire(b, now));
        if (!overdue.isEmpty()) {
            log.info("Expired {} unpaid booking hold(s)", overdue.size());
        }
        return overdue.size();
    }

    /** A stay is complete once its check-out date is in the past in the hotel's own time zone. */
    @Transactional
    public int completeFinishedStays() {
        Instant now = clock.instant();
        // Latest possible "yesterday" anywhere on Earth is UTC+14's date - 1; filter precisely below.
        LocalDate latest = now.atZone(ZoneOffset.ofHours(14)).toLocalDate().minusDays(1);
        int completed = 0;
        for (Booking b : bookings.lockCompletionCandidates(latest, BATCH)) {
            LocalDate hotelToday = now.atZone(ZoneId.of(b.getHotelTimeZone())).toLocalDate();
            if (b.getCheckOut().isBefore(hotelToday)) {
                b.transitionTo(BookingStatus.COMPLETED, "Stay completed", BookingStatusChange.SYSTEM, now);
                completed++;
            }
        }
        return completed;
    }

    /** Scheduling is switched off in tests (booking.jobs.enabled=false), which call the methods directly. */
    @Component
    @ConditionalOnProperty(name = "booking.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduler {
        private final BookingLifecycleJob job;

        Scheduler(BookingLifecycleJob job) {
            this.job = job;
        }

        @Scheduled(fixedDelayString = "${booking.jobs.expiry-interval:30s}", initialDelayString = "10s")
        void expire() {
            job.expireOverdueHolds();
        }

        @Scheduled(fixedDelayString = "${booking.jobs.completion-interval:15m}", initialDelayString = "60s")
        void complete() {
            job.completeFinishedStays();
        }
    }
}
