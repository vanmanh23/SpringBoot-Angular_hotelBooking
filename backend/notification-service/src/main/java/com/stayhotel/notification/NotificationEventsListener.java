package com.stayhotel.notification;

import com.stayhotel.common.messaging.EventEnvelope;
import com.stayhotel.common.messaging.EventReader;
import com.stayhotel.common.messaging.ProcessedMessages;
import com.stayhotel.common.messaging.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Consumes booking and payment events; idempotent per eventId. Payloads are never logged (they carry contacts). */
@Component
public class NotificationEventsListener {

    static final String CONSUMER = "notification-service";

    private final EventReader reader;
    private final ProcessedMessages processed;
    private final NotificationService notifications;

    NotificationEventsListener(EventReader reader, ProcessedMessages processed, NotificationService notifications) {
        this.reader = reader;
        this.processed = processed;
        this.notifications = notifications;
    }

    @KafkaListener(topics = {Topics.BOOKING_EVENTS, Topics.PAYMENT_EVENTS}, groupId = CONSUMER)
    @Transactional
    public void onMessage(String message) {
        EventEnvelope event = reader.envelope(message);
        if (event.payload() == null || !event.payload().hasNonNull("bookingId")
                || !processed.markProcessed(event.eventId(), CONSUMER)) {
            return;
        }
        notifications.handle(event);
    }
}
