package com.stayhotel.payment.payment;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    public static final String PROVIDER_SANDBOX = "SANDBOX";

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, updatable = false, columnDefinition = "bpchar(3)")
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "checkout_url")
    private String checkoutUrl;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "needs_review", nullable = false)
    private boolean needsReview;

    @Column(name = "succeeded_at")
    private Instant succeededAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL)
    @OrderBy("createdAt ASC")
    private List<PaymentStatusChange> history = new ArrayList<>();

    protected Payment() {
    }

    /** Amount and currency come from booking-service (BR-22), never from the client. */
    static Payment pending(UUID bookingId, UUID userId, String idempotencyKey, BigDecimal amount, String currency, Instant now) {
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.bookingId = bookingId;
        p.userId = userId;
        p.provider = PROVIDER_SANDBOX;
        p.idempotencyKey = idempotencyKey;
        p.amount = amount;
        p.currencyCode = currency;
        p.status = PaymentStatus.PENDING;
        p.createdAt = now;
        p.updatedAt = now;
        p.history.add(new PaymentStatusChange(p, null, PaymentStatus.PENDING, "Checkout started", now));
        return p;
    }

    void attachCheckout(String sessionId, String url, Instant now) {
        providerReference = sessionId;
        checkoutUrl = url;
        updatedAt = now;
    }

    /** @return false (and no change) when the transition is not allowed, e.g. a late or duplicate provider event. */
    boolean transitionTo(PaymentStatus target, String reason, Instant now) {
        if (!status.canTransitionTo(target)) {
            return false;
        }
        history.add(new PaymentStatusChange(this, status, target, reason, now));
        status = target;
        updatedAt = now;
        if (target == PaymentStatus.SUCCEEDED && succeededAt == null) {
            succeededAt = now;
        }
        if (target == PaymentStatus.FAILED) {
            failureReason = reason;
        }
        return true;
    }

    void flagForReview(String reason, Instant now) {
        needsReview = true;
        if (failureReason == null) {
            failureReason = reason;
        }
        updatedAt = now;
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

    public String getProvider() {
        return provider;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getCheckoutUrl() {
        return checkoutUrl;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public boolean isNeedsReview() {
        return needsReview;
    }

    public Instant getSucceededAt() {
        return succeededAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<PaymentStatusChange> getHistory() {
        return List.copyOf(history);
    }
}
