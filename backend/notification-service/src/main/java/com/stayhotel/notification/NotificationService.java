package com.stayhotel.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.stayhotel.common.error.BusinessException;
import com.stayhotel.common.messaging.EventEnvelope;
import com.stayhotel.common.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns booking/payment events into in-app notifications plus an email (D-029). The notification row is written in
 * the consumer's transaction; the email goes out after commit and is retried independently, so a mail outage never
 * blocks or alters business processing (R-06).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String appUrl;

    NotificationService(JdbcTemplate jdbc, ApplicationEventPublisher events, Clock clock,
                        @Value("${notification.app-url:http://localhost:4200}") String appUrl) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
        this.appUrl = appUrl;
    }

    /** Must run inside the consumer transaction (after dedup). */
    @Transactional
    public void handle(EventEnvelope event) {
        JsonNode payload = event.payload();
        UUID bookingId = UUID.fromString(payload.path("bookingId").asText());
        if ("BookingCreated".equals(event.eventType()) && payload.hasNonNull("contactEmail")) {
            storeContact(payload);
        }
        Optional<Contact> contact = contact(bookingId);
        if (contact.isEmpty()) {
            // Booking created before contact data was published (pre-M5) - nobody to address. booking-events is keyed
            // by bookingId, and payment events only follow a booking that already exists, so this is not a race.
            log.warn("No contact for booking {}; {} not notified", bookingId, event.eventType());
            return;
        }
        NotificationComposer.compose(event.eventType(), payload, contact.get(), appUrl).ifPresent(message -> {
            UUID id = UUID.randomUUID();
            int inserted = jdbc.update("""
                    INSERT INTO notifications (id, event_id, user_id, booking_id, type, title, body, email_to, email_status,
                                               next_attempt_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?) ON CONFLICT (event_id) DO NOTHING""",
                    id, event.eventId(), contact.get().userId(), bookingId, message.type(), message.title(), message.body(),
                    contact.get().email(), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
            if (inserted == 1) {
                events.publishEvent(new EmailDue(id));
            }
        });
    }

    private void storeContact(JsonNode p) {
        jdbc.update("""
                INSERT INTO booking_contacts (booking_id, user_id, contact_name, contact_email, booking_code, hotel_name,
                                              check_in, check_out, total_amount, currency_code, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (booking_id) DO NOTHING""",
                UUID.fromString(p.path("bookingId").asText()), UUID.fromString(p.path("userId").asText()),
                p.path("contactName").asText(), p.path("contactEmail").asText(), p.path("bookingCode").asText(),
                p.path("hotelName").asText(), LocalDate.parse(p.path("checkInDate").asText()),
                LocalDate.parse(p.path("checkOutDate").asText()), new BigDecimal(p.path("totalAmount").asText()),
                p.path("currency").asText(), Timestamp.from(clock.instant()));
    }

    private Optional<Contact> contact(UUID bookingId) {
        return jdbc.query("SELECT * FROM booking_contacts WHERE booking_id = ?", (rs, i) -> new Contact(
                rs.getObject("booking_id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("contact_name"),
                rs.getString("contact_email"), rs.getString("booking_code"), rs.getString("hotel_name"),
                rs.getObject("check_in", LocalDate.class), rs.getObject("check_out", LocalDate.class),
                rs.getBigDecimal("total_amount"), rs.getString("currency_code")), bookingId).stream().findFirst();
    }

    // ------------------------------------------------------------------ in-app API

    private static final RowMapper<NotificationView> VIEW = (rs, i) -> new NotificationView(rs.getObject("id", UUID.class),
            rs.getString("type"), rs.getString("title"), rs.getString("body"), rs.getObject("booking_id", UUID.class),
            rs.getTimestamp("read_at") != null, rs.getTimestamp("created_at").toInstant());

    @Transactional(readOnly = true)
    public PageResponse<NotificationView> mine(UUID userId, int page, int size) {
        long total = count(userId, false);
        List<NotificationView> content = jdbc.query("""
                SELECT id, type, title, body, booking_id, read_at, created_at FROM notifications
                WHERE user_id = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?""", VIEW, userId, size, (long) page * size);
        return new PageResponse<>(content, page, size, total, (int) ((total + size - 1) / size));
    }

    @Transactional(readOnly = true)
    public long unread(UUID userId) {
        return count(userId, true);
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        int updated = jdbc.update("UPDATE notifications SET read_at = COALESCE(read_at, ?) WHERE id = ? AND user_id = ?",
                Timestamp.from(clock.instant()), notificationId, userId);
        if (updated == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Notification not found");
        }
    }

    @Transactional
    public void markAllRead(UUID userId) {
        jdbc.update("UPDATE notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL",
                Timestamp.from(clock.instant()), userId);
    }

    private long count(UUID userId, boolean unreadOnly) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?"
                + (unreadOnly ? " AND read_at IS NULL" : ""), Long.class, userId);
        return n == null ? 0 : n;
    }

    public record NotificationView(UUID id, String type, String title, String body, UUID bookingId, boolean read,
                                   Instant createdAt) {
    }

    /** Spring event: a notification row committed and its email can be attempted. */
    public record EmailDue(UUID notificationId) {
    }
}
