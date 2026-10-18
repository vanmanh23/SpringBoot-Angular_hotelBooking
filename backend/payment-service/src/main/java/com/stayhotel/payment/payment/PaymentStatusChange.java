package com.stayhotel.payment.payment;

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

@Entity
@Table(name = "payment_status_history")
public class PaymentStatusChange {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", updatable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private PaymentStatus toStatus;

    @Column(updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentStatusChange() {
    }

    PaymentStatusChange(Payment payment, PaymentStatus from, PaymentStatus to, String reason, Instant at) {
        this.id = UUID.randomUUID();
        this.payment = payment;
        this.fromStatus = from;
        this.toStatus = to;
        this.reason = reason;
        this.createdAt = at;
    }

    public PaymentStatus getToStatus() {
        return toStatus;
    }
}
