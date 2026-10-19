package com.stayhotel.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reviews")
public class Review {

    public enum Status { VISIBLE, HIDDEN }

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "hotel_id", nullable = false, updatable = false)
    private UUID hotelId;

    @Column(nullable = false)
    private short rating;

    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "hidden_reason")
    private String hiddenReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Review() {
    }

    static Review create(UUID bookingId, UUID userId, UUID hotelId, int rating, String comment, Instant now) {
        Review r = new Review();
        r.id = UUID.randomUUID();
        r.bookingId = bookingId;
        r.userId = userId;
        r.hotelId = hotelId;
        r.status = Status.VISIBLE;
        r.createdAt = now;
        r.edit(rating, comment, now);
        return r;
    }

    void edit(int rating, String comment, Instant now) {
        this.rating = (short) rating;
        this.comment = comment == null || comment.isBlank() ? null : comment.trim();
        this.updatedAt = now;
    }

    void moderate(Status status, String reason, Instant now) {
        this.status = status;
        this.hiddenReason = status == Status.HIDDEN ? reason : null;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getHotelId() {
        return hotelId;
    }

    public int getRating() {
        return rating;
    }

    public String getComment() {
        return comment;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getHiddenReason() {
        return hiddenReason;
    }
}
