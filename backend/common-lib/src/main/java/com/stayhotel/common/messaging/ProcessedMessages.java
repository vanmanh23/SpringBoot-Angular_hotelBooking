package com.stayhotel.common.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/**
 * Consumer-side deduplication: an event is handled only by the transaction that manages to insert its id.
 * Because the insert shares the handler's transaction, a failed handler leaves no mark and the retry runs again.
 */
public class ProcessedMessages {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProcessedMessages(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return true the first time this consumer sees the event, false for a duplicate delivery */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId, String consumer) {
        return jdbc.update("""
                INSERT INTO processed_messages (event_id, consumer, processed_at) VALUES (?, ?, ?)
                ON CONFLICT (event_id, consumer) DO NOTHING""", eventId, consumer, Timestamp.from(clock.instant())) == 1;
    }
}
