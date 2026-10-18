package com.stayhotel.common.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.common.logging.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Transactional outbox (design 5.5.3): events are inserted in the SAME database transaction as the state change
 * that causes them, so "state committed but event lost" cannot happen. {@link OutboxPublisher} ships them later.
 */
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String topic, String eventType, UUID aggregateId, Object payload) {
        Instant now = clock.instant();
        UUID eventId = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(eventId, eventType, 1, now, MDC.get(CorrelationIdFilter.MDC_KEY),
                aggregateId.toString(), objectMapper.valueToTree(payload));
        try {
            jdbc.update("""
                    INSERT INTO outbox_events (id, topic, message_key, event_type, payload, created_at)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)""",
                    eventId, topic, aggregateId.toString(), eventType, objectMapper.writeValueAsString(envelope),
                    Timestamp.from(now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + eventType, e);
        }
        return eventId;
    }
}
