package com.stayhotel.review;

import com.stayhotel.common.messaging.EventEnvelope;
import com.stayhotel.common.messaging.EventReader;
import com.stayhotel.common.messaging.ProcessedMessages;
import com.stayhotel.common.messaging.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Builds the review-eligibility projection from BookingCompleted (idempotent). */
@Component
public class BookingEventsListener {

    static final String CONSUMER = "review-service";

    private final EventReader reader;
    private final ProcessedMessages processed;
    private final ReviewService reviews;

    BookingEventsListener(EventReader reader, ProcessedMessages processed, ReviewService reviews) {
        this.reader = reader;
        this.processed = processed;
        this.reviews = reviews;
    }

    @KafkaListener(topics = Topics.BOOKING_EVENTS, groupId = CONSUMER)
    @Transactional
    public void onMessage(String message) {
        EventEnvelope event = reader.envelope(message);
        if (!"BookingCompleted".equals(event.eventType()) || !processed.markProcessed(event.eventId(), CONSUMER)) {
            return;
        }
        ReviewDtos.BookingCompleted e = reader.payload(event, ReviewDtos.BookingCompleted.class);
        reviews.recordCompletedStay(e.bookingId(), e.userId(), e.hotelId(), e.checkOutDate());
    }
}
