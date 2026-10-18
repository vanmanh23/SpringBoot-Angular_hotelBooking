package com.stayhotel.booking.booking;

import com.stayhotel.common.messaging.EventEnvelope;
import com.stayhotel.common.messaging.EventReader;
import com.stayhotel.common.messaging.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes payment-events (at-least-once). Exceptions are retried with backoff, then sent to the DLT. */
@Component
class PaymentEventsListener {

    private final EventReader reader;
    private final PaymentOutcomeHandler handler;

    PaymentEventsListener(EventReader reader, PaymentOutcomeHandler handler) {
        this.reader = reader;
        this.handler = handler;
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = PaymentOutcomeHandler.CONSUMER)
    public void onMessage(String message) {
        EventEnvelope event = reader.envelope(message);
        switch (event.eventType()) {
            case "PaymentSucceeded" -> handler.onPaymentSucceeded(event.eventId(),
                    reader.payload(event, BookingEvents.PaymentSucceeded.class));
            case "RefundRequested" -> refund(event, Booking.RefundStatus.PENDING);
            case "RefundSucceeded" -> refund(event, Booking.RefundStatus.REFUNDED);
            case "RefundFailed" -> refund(event, Booking.RefundStatus.FAILED);
            default -> {
                // PaymentFailed: the hold simply stays until it is paid again or expires.
            }
        }
    }

    private void refund(EventEnvelope event, Booking.RefundStatus status) {
        handler.onRefundUpdate(event.eventId(), reader.payload(event, BookingEvents.RefundUpdate.class).bookingId(), status);
    }
}
