package com.stayhotel.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only record of sensitive staff actions (BR-32), written in the SAME transaction as the change, so a change
 * can never commit without its audit row. Same table shape as auth-service's {@code audit_logs}. Details must not
 * contain personal or payment data beyond identifiers.
 */
public class AuditTrail {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public AuditTrail(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorId, String action, String targetType, Object targetId, Map<String, ?> details) {
        try {
            jdbc.update("""
                    INSERT INTO audit_logs (id, actor_id, action, target_type, target_id, details, created_at)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)""",
                    UUID.randomUUID(), actorId, action, targetType, String.valueOf(targetId),
                    details == null ? null : json.writeValueAsString(details), Timestamp.from(clock.instant()));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unserializable audit details", e);
        }
    }
}
