package com.stayhotel.payment.payment;

import com.stayhotel.common.messaging.EventEnvelope;
import com.stayhotel.common.messaging.EventReader;
import com.stayhotel.common.messaging.ProcessedMessages;
import com.stayhotel.common.messaging.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reacts to booking events (at-least-once delivery): eligible cancellations and rejected payments become refunds.
 * Dedup by event id in the same transaction as the effect, so duplicates and retries are harmless.
 */
@Component
public class BookingEventsListener {

    static final String CONSUMER = "payment-service";
    private static final Logger log = LoggerFactory.getLogger(BookingEventsListener.class);

    private final EventReader reader;
    private final ProcessedMessages processed;
    private final PaymentService payments;

    BookingEventsListener(EventReader reader, ProcessedMessages processed, PaymentService payments) {
        this.reader = reader;
        this.processed = processed;
        this.payments = payments;
    }

    @KafkaListener(topics = Topics.BOOKING_EVENTS, groupId = CONSUMER)
    @Transactional
    public void onMessage(String message) {
        EventEnvelope event = reader.envelope(message);
        if (!processed.markProcessed(event.eventId(), CONSUMER)) {
            return;
        }
        switch (event.eventType()) {
            case "BookingCancelled" -> {
                PaymentEvents.BookingCancelled e = reader.payload(event, PaymentEvents.BookingCancelled.class);
                if (Boolean.TRUE.equals(e.refundEligible())) {
                    payments.refundBookingIfPaid(e.bookingId(), "Booking cancelled within the free-cancellation period");
                }
            }
            case "BookingPaymentRejected" -> {
                PaymentEvents.BookingPaymentRejected e = reader.payload(event, PaymentEvents.BookingPaymentRejected.class);
                log.warn("Booking {} rejected payment {} ({}): refunding and flagging for review", e.bookingId(),
                        e.paymentId(), e.reason());
                payments.startRefund(e.paymentId(), "Payment not applicable to booking: " + e.reason(), true);
            }
            default -> {
                // BookingCreated / Confirmed / Expired are not relevant to payments.
            }
        }
    }
}
