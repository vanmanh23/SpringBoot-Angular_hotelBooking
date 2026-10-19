package com.stayhotel.notification;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Optional;

/** Turns a domain event + booking contact into a short, plain-text message (no HTML, nothing to escape). */
final class NotificationComposer {

    private NotificationComposer() {
    }

    record Message(String type, String title, String body) {
    }

    static Optional<Message> compose(String eventType, JsonNode payload, Contact c, String appUrl) {
        String link = appUrl + "/bookings/" + c.bookingId();
        String stay = c.hotelName() + ", " + c.checkIn() + " → " + c.checkOut();
        return Optional.ofNullable(switch (eventType) {
            case "BookingCreated" -> new Message("BOOKING_CREATED", "Booking " + c.bookingCode() + " received",
                    "Hi " + c.name() + ",\n\nWe are holding your rooms at " + stay + " for 15 minutes. "
                            + "Total: " + money(c) + ". Complete the payment to confirm:\n" + link);
            case "BookingConfirmed" -> new Message("BOOKING_CONFIRMED", "Booking " + c.bookingCode() + " confirmed",
                    "Hi " + c.name() + ",\n\nYour stay at " + stay + " is confirmed. Booking code: " + c.bookingCode()
                            + ".\n" + link);
            case "BookingExpired" -> new Message("BOOKING_EXPIRED", "Booking " + c.bookingCode() + " expired",
                    "Hi " + c.name() + ",\n\nWe did not receive the payment in time, so the rooms at " + stay
                            + " were released. You can search again at any time.");
            case "BookingCancelled" -> new Message("BOOKING_CANCELLED", "Booking " + c.bookingCode() + " cancelled",
                    "Hi " + c.name() + ",\n\nYour booking at " + stay + " was cancelled."
                            + (payload.path("refundEligible").asBoolean(false)
                            ? " A refund of " + money(c) + " is on its way; we will tell you when it is completed." : ""));
            case "BookingPaymentRejected" -> new Message("PAYMENT_REJECTED", "Payment for " + c.bookingCode() + " refunded",
                    "Hi " + c.name() + ",\n\nYour payment arrived after the booking could no longer be honoured, so it is "
                            + "being refunded in full. Our team has been notified.\n" + link);
            case "PaymentFailed" -> new Message("PAYMENT_FAILED", "Payment for " + c.bookingCode() + " failed",
                    "Hi " + c.name() + ",\n\nYour payment was declined. Your rooms are still held for a short time; "
                            + "you can try again here:\n" + link);
            case "RefundSucceeded" -> new Message("REFUND_COMPLETED", "Refund for " + c.bookingCode() + " completed",
                    "Hi " + c.name() + ",\n\n" + money(c, payload.path("amount")) + " has been refunded to your payment method.");
            case "BookingCompleted" -> new Message("STAY_COMPLETED", "How was your stay?",
                    "Hi " + c.name() + ",\n\nThank you for staying at " + c.hotelName() + ". You can now leave a review:\n" + link);
            default -> null; // RefundRequested, PaymentSucceeded, ... : nothing to tell the guest
        });
    }

    private static String money(Contact c) {
        return NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN")).format(c.totalAmount()) + " " + c.currency();
    }

    private static String money(Contact c, JsonNode amount) {
        if (!amount.isValueNode() || amount.asText().isBlank()) {
            return money(c);
        }
        return NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN")).format(new BigDecimal(amount.asText()))
                + " " + c.currency();
    }
}
