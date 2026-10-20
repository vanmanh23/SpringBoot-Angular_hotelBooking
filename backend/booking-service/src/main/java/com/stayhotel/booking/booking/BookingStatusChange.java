package com.stayhotel.booking.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Append-only status history row (BR-18 audit trail). */
@Entity
@Table(name = "booking_status_history")
public class BookingStatusChange {

    public static final String SYSTEM = "SYSTEM";

    @Id
    private UUID id;

    /** Database-assigned insertion order; tie-breaker for entries with the same timestamp. */
    @Column(name = "seq", insertable = false, updatable = false)
    private Long seq;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", updatable = false)
    private Booking booking;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private BookingStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private BookingStatus toStatus;

    @Column(updatable = false)
    private String reason;

    @Column(nullable = false, updatable = false)
    private String actor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BookingStatusChange() {
    }

    BookingStatusChange(Booking booking, BookingStatus from, BookingStatus to, String reason, String actor, Instant at) {
        this.id = UUID.randomUUID();
        this.booking = booking;
        this.fromStatus = from;
        this.toStatus = to;
        this.reason = reason;
        this.actor = actor;
        this.createdAt = at;
    }

    public BookingStatus getFromStatus() {
        return fromStatus;
    }

    public BookingStatus getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }

    public String getActor() {
        return actor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
