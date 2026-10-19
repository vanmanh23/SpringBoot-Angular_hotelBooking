package com.stayhotel.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends notification emails outside the consumer transaction (D-029). Each attempt first takes a short lease on the
 * row (next_attempt_at pushed forward under SKIP LOCKED) so the immediate after-commit send and the retry job never
 * mail the same row twice. Failures back off exponentially; after {@link #MAX_ATTEMPTS} the row is FAILED.
 * Messages are plain text (no HTML rendering, so user-provided names cannot inject markup).
 */
@Component
class EmailDispatcher {

    static final int MAX_ATTEMPTS = 5;
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    private final JdbcTemplate jdbc;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final Clock clock;
    private final String from;

    EmailDispatcher(JdbcTemplate jdbc, ObjectProvider<JavaMailSender> mailSender, Clock clock,
                    @Value("${notification.mail-from:no-reply@stayhotel.local}") String from) {
        this.jdbc = jdbc;
        this.mailSender = mailSender;
        this.clock = clock;
        this.from = from;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onEmailDue(NotificationService.EmailDue due) {
        try {
            attempt(due.notificationId());
        } catch (RuntimeException e) {
            // never let a mail/DB problem surface into the Kafka listener; the retry job picks the row up
            log.warn("Email attempt for notification {} deferred: {}", due.notificationId(), e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${notification.email-retry-interval:PT30S}")
    void retryDue() {
        List<UUID> due = jdbc.queryForList("""
                SELECT id FROM notifications WHERE email_status = 'PENDING' AND next_attempt_at <= ?
                ORDER BY next_attempt_at LIMIT 50""", UUID.class, Timestamp.from(clock.instant()));
        due.forEach(this::attempt);
    }

    /** Leases, sends and records the outcome of one email. */
    void attempt(UUID id) {
        Instant now = clock.instant();
        List<Map<String, Object>> leased = jdbc.queryForList("""
                UPDATE notifications SET next_attempt_at = ?, email_attempts = email_attempts + 1
                WHERE id = (SELECT id FROM notifications WHERE id = ? AND email_status = 'PENDING' AND next_attempt_at <= ?
                            FOR UPDATE SKIP LOCKED)
                RETURNING email_to, title, body, email_attempts""", Timestamp.from(now.plus(LEASE)), id, Timestamp.from(now));
        if (leased.isEmpty()) {
            return; // already sent, being sent, or not due yet
        }
        Map<String, Object> row = leased.get(0);
        int attempts = ((Number) row.get("email_attempts")).intValue();
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || row.get("email_to") == null) {
            jdbc.update("UPDATE notifications SET email_status = 'SKIPPED', next_attempt_at = NULL WHERE id = ?", id);
            return;
        }
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(from);
            mail.setTo((String) row.get("email_to"));
            mail.setSubject("[StayHotel] " + row.get("title"));
            mail.setText((String) row.get("body"));
            sender.send(mail);
            jdbc.update("UPDATE notifications SET email_status = 'SENT', next_attempt_at = NULL, last_error = NULL WHERE id = ?",
                    id);
        } catch (MailException e) {
            String error = e.getClass().getSimpleName(); // never the message: it may echo the recipient address
            if (attempts >= MAX_ATTEMPTS) {
                jdbc.update("UPDATE notifications SET email_status = 'FAILED', next_attempt_at = NULL, last_error = ? WHERE id = ?",
                        error, id);
                log.error("Email for notification {} failed permanently after {} attempts ({})", id, attempts, error);
            } else {
                Duration backoff = Duration.ofSeconds(30L << (attempts - 1)); // 30s, 1m, 2m, 4m
                jdbc.update("UPDATE notifications SET next_attempt_at = ?, last_error = ? WHERE id = ?",
                        Timestamp.from(clock.instant().plus(backoff)), error, id);
                log.warn("Email for notification {} failed (attempt {}), retrying in {}", id, attempts, backoff);
            }
        }
    }
}
