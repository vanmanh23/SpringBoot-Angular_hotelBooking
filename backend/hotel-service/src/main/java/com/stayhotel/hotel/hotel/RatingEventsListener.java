package com.stayhotel.hotel.hotel;

import com.stayhotel.common.messaging.EventEnvelope;
import com.stayhotel.common.messaging.EventReader;
import com.stayhotel.common.messaging.ProcessedMessages;
import com.stayhotel.common.messaging.Topics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Applies review-service's absolute, versioned rating values (D-028). The version guard makes duplicates and
 * out-of-order deliveries harmless. A plain JDBC update is used on purpose: it must not bump the hotel's optimistic
 * lock version, otherwise a manager editing the hotel would get a spurious 409.
 */
@Component
public class RatingEventsListener {

    static final String CONSUMER = "hotel-service";

    private final EventReader reader;
    private final ProcessedMessages processed;
    private final JdbcTemplate jdbc;

    RatingEventsListener(EventReader reader, ProcessedMessages processed, JdbcTemplate jdbc) {
        this.reader = reader;
        this.processed = processed;
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = Topics.REVIEW_EVENTS, groupId = CONSUMER)
    @Transactional
    public void onMessage(String message) {
        EventEnvelope event = reader.envelope(message);
        if (!"HotelRatingChanged".equals(event.eventType()) || !processed.markProcessed(event.eventId(), CONSUMER)) {
            return;
        }
        RatingChanged e = reader.payload(event, RatingChanged.class);
        jdbc.update("""
                UPDATE hotels SET avg_rating = ?, review_count = ?, rating_version = ?
                WHERE id = ? AND rating_version < ?""", e.avgRating(), e.reviewCount(), e.version(), e.hotelId(), e.version());
    }

    record RatingChanged(UUID hotelId, BigDecimal avgRating, int reviewCount, long version) {
    }
}
