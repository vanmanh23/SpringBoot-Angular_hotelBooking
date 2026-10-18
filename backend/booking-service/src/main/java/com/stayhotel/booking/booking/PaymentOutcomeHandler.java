package com.stayhotel.booking.booking;

import com.stayhotel.booking.catalog.HotelCatalog;
import com.stayhotel.booking.catalog.HotelSnapshot;
import com.stayhotel.booking.inventory.InventoryRepository;
import com.stayhotel.common.messaging.OutboxWriter;
import com.stayhotel.common.messaging.ProcessedMessages;
import com.stayhotel.common.messaging.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies verified payment results to bookings (booking side of D-024):
 * <ul>
 *   <li>on-time payment of a hold → CONFIRMED;</li>
 *   <li>late payment (hold expired) → re-reserve every night; success → CONFIRMED, otherwise rejected;</li>
 *   <li>payment for a cancelled / already paid / started stay → rejected.</li>
 * </ul>
 * A rejection publishes {@code BookingPaymentRejected}; payment-service refunds and flags it. Each event is applied
 * at most once (dedup row in the same transaction).
 */
@Component
public class PaymentOutcomeHandler {

    public static final String CONSUMER = "booking-service";
    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeHandler.class);

    private final BookingRepository bookings;
    private final BookingService bookingService;
    private final InventoryRepository inventory;
    private final HotelCatalog catalog;
    private final OutboxWriter outbox;
    private final ProcessedMessages processed;
    private final TransactionTemplate tx;
    private final Clock clock;

    PaymentOutcomeHandler(BookingRepository bookings, BookingService bookingService, InventoryRepository inventory,
                          HotelCatalog catalog, OutboxWriter outbox, ProcessedMessages processed,
                          PlatformTransactionManager txManager, Clock clock) {
        this.bookings = bookings;
        this.bookingService = bookingService;
        this.inventory = inventory;
        this.catalog = catalog;
        this.outbox = outbox;
        this.processed = processed;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public void onPaymentSucceeded(UUID eventId, BookingEvents.PaymentSucceeded e) {
        // Late payments need live capacity from hotel-service: fetch it BEFORE opening the transaction.
        Optional<Booking> peek = tx.execute(s -> bookings.findById(e.bookingId()));
        if (peek == null || peek.isEmpty()) {
            tx.executeWithoutResult(s -> {
                if (processed.markProcessed(eventId, CONSUMER)) {
                    log.error("PaymentSucceeded for unknown booking {}", e.bookingId());
                }
            });
            return;
        }
        Booking b = peek.get();
        boolean mayNeedCapacity = b.getStatus() == BookingStatus.EXPIRED
                || b.getStatus() == BookingStatus.PENDING_PAYMENT && !clock.instant().isBefore(b.getExpiresAt());
        HotelSnapshot hotel = mayNeedCapacity ? catalog.findActiveHotel(b.getHotelId()).orElse(null) : null;

        tx.executeWithoutResult(s -> {
            if (!processed.markProcessed(eventId, CONSUMER)) {
                return; // duplicate delivery
            }
            Booking booking = bookings.findByIdForUpdate(e.bookingId()).orElseThrow();
            Instant now = clock.instant();
            if (booking.getTotalAmount().compareTo(e.amount()) != 0 || !booking.getCurrencyCode().equals(e.currency())) {
                reject(booking, e, "AMOUNT_MISMATCH", now);
                return;
            }
            switch (booking.getStatus()) {
                case PENDING_PAYMENT -> {
                    if (now.isBefore(booking.getExpiresAt())) {
                        confirm(booking, e.paymentId(), "Payment " + e.paymentId() + " received", false, now);
                    } else {
                        bookingService.expire(booking, now); // the job had not run yet
                        latePayment(booking, e, hotel, now);
                    }
                }
                case EXPIRED -> latePayment(booking, e, hotel, now);
                case CANCELLED -> reject(booking, e, "BOOKING_CANCELLED", now);
                case CONFIRMED, COMPLETED -> {
                    if (!e.paymentId().equals(booking.getPaymentId())) {
                        reject(booking, e, "ALREADY_PAID", now);
                    }
                }
            }
        });
    }

    public void onRefundUpdate(UUID eventId, UUID bookingId, Booking.RefundStatus status) {
        tx.executeWithoutResult(s -> {
            if (processed.markProcessed(eventId, CONSUMER)) {
                bookings.findByIdForUpdate(bookingId).ifPresent(b -> b.updateRefundStatus(status, clock.instant()));
            }
        });
    }

    private void latePayment(Booking booking, BookingEvents.PaymentSucceeded e, HotelSnapshot hotel, Instant now) {
        if (!now.isBefore(booking.checkInMoment())) {
            reject(booking, e, "STAY_STARTED", now);
            return;
        }
        if (hotel == null || !reReserve(booking, hotel)) {
            reject(booking, e, "NO_AVAILABILITY", now);
            return;
        }
        confirm(booking, e.paymentId(), "Late payment " + e.paymentId() + " accepted; rooms re-reserved", true, now);
    }

    /** All-or-nothing under row locks: either every night of every line is reserved again, or nothing changes. */
    private boolean reReserve(Booking booking, HotelSnapshot hotel) {
        List<InventoryRepository.Line> lines = booking.getRooms().stream()
                .map(r -> new InventoryRepository.Line(r.getRoomTypeId(), r.getQuantity(),
                        hotel.roomType(r.getRoomTypeId()).map(HotelSnapshot.RoomTypeSnapshot::totalUnits).orElse(0)))
                .toList();
        return inventory.reserveAllOrNothing(lines, booking.getCheckIn(), booking.getCheckOut());
    }

    private void confirm(Booking booking, UUID paymentId, String reason, boolean late, Instant now) {
        booking.transitionTo(BookingStatus.CONFIRMED, reason, BookingStatusChange.SYSTEM, now);
        booking.linkPayment(paymentId);
        outbox.append(Topics.BOOKING_EVENTS, "BookingConfirmed", booking.getId(),
                new BookingEvents.BookingConfirmed(booking.getId(), paymentId, late));
        log.info("Booking {} confirmed by payment {}{}", booking.getBookingCode(), paymentId, late ? " (late)" : "");
    }

    private void reject(Booking booking, BookingEvents.PaymentSucceeded e, String reason, Instant now) {
        booking.updateRefundStatus(Booking.RefundStatus.PENDING, now);
        outbox.append(Topics.BOOKING_EVENTS, "BookingPaymentRejected", booking.getId(),
                new BookingEvents.BookingPaymentRejected(booking.getId(), e.paymentId(), reason));
        log.warn("Payment {} rejected for booking {} ({}): refund requested", e.paymentId(), booking.getBookingCode(), reason);
    }
}
