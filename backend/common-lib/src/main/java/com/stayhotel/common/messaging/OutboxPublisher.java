package com.stayhotel.common.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Relays unpublished outbox rows to Kafka (at-least-once, in creation order). Rows are claimed with
 * FOR UPDATE SKIP LOCKED so several instances can run it. A failed send stops the batch to keep ordering; the
 * row stays unpublished and is retried on the next tick. Consumers must therefore tolerate duplicates.
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH = 50;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${stayhotel.outbox.interval:1000}")
    public void publishPending() {
        Integer sent;
        do {
            sent = tx.execute(s -> publishBatch());
        } while (sent != null && sent == BATCH);
    }

    private int publishBatch() {
        List<Row> rows = jdbc.query("""
                SELECT id, topic, message_key, payload::text AS payload FROM outbox_events
                WHERE published_at IS NULL ORDER BY seq LIMIT ? FOR UPDATE SKIP LOCKED""",
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("topic"), rs.getString("message_key"),
                        rs.getString("payload")), BATCH);
        int sent = 0;
        for (Row row : rows) {
            try {
                kafka.send(row.topic(), row.key(), row.payload()).get(10, TimeUnit.SECONDS);
                jdbc.update("UPDATE outbox_events SET published_at = ?, attempts = attempts + 1 WHERE id = ?",
                        Timestamp.from(clock.instant()), row.id());
                sent++;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                String error = e.getClass().getSimpleName();
                jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE id = ?", error, row.id());
                log.warn("Outbox publish failed for event {} ({}); will retry", row.id(), error);
                break;
            }
        }
        return sent;
    }

    private record Row(UUID id, String topic, String key, String payload) {
    }
}
