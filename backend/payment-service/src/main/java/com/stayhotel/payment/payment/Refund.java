package com.stayhotel.payment.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Full refund of one payment (design 4.4.9). Its id doubles as the provider idempotency key, so re-submitting
 * the same refund can never move money twice.
 */
@Entity
@Table(name = "refunds")
public class Refund {

    public enum Status { PENDING, SUCCEEDED, FAILED }

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Refund() {
    }

    Refund(UUID paymentId, BigDecimal amount, String reason, Instant now) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.amount = amount;
        this.reason = reason;
        this.status = Status.PENDING;
        this.createdAt = now;
    }

    void recordAttempt(Instant now) {
        attempts++;
        lastAttemptAt = now;
    }

    boolean complete(boolean succeeded, String providerRef, Instant now) {
        if (status != Status.PENDING) {
            return false;
        }
        status = succeeded ? Status.SUCCEEDED : Status.FAILED;
        providerReference = providerRef;
        completedAt = now;
        return true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
