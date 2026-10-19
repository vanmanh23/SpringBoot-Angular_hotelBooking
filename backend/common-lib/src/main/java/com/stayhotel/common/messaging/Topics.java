package com.stayhotel.common.messaging;

/** Kafka topics. Each has a {@code <topic>.DLT} dead-letter companion. Messages are keyed by booking id. */
public final class Topics {

    public static final String PAYMENT_EVENTS = "payment-events";
    public static final String BOOKING_EVENTS = "booking-events";
    public static final String REVIEW_EVENTS = "review-events";
    public static final String DLT_SUFFIX = ".DLT";

    private Topics() {
    }
}
