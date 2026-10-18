package com.stayhotel.payment.payment;

import com.stayhotel.common.messaging.OutboxWriter;
import com.stayhotel.common.messaging.Topics;
import com.stayhotel.common.security.CurrentUser;
import com.stayhotel.payment.PaymentErrors;
import com.stayhotel.payment.booking.BookingClient;
import com.stayhotel.payment.config.PaymentProperties;
import com.stayhotel.payment.provider.PaymentProvider;
import com.stayhotel.payment.provider.ProviderEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Payment use cases. Results come only from verified provider events (BR-23); every state change and its outbox
 * event commit together; refunds are idempotent end-to-end (DB uniqueness + provider idempotency key).
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final BookingClient bookings;
    private final PaymentProvider provider;
    private final OutboxWriter outbox;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final PaymentProperties properties;
    private final Clock clock;

    PaymentService(PaymentRepository payments, RefundRepository refunds, BookingClient bookings, PaymentProvider provider,
                   OutboxWriter outbox, JdbcTemplate jdbc, TransactionTemplate tx, ApplicationEventPublisher events,
                   PaymentProperties properties, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.bookings = bookings;
        this.provider = provider;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ checkout

    /**
     * Starts (or resumes) a checkout. Idempotent per (user, key); an existing PENDING payment for the booking is
     * returned instead of opening a second one; a captured payment blocks new attempts (no double charge).
     */
    public CreateResult create(CurrentUser user, String bearerToken, String idempotencyKey, UUID bookingId) {
        Optional<Payment> replay = tx.execute(s -> payments.findByUserIdAndIdempotencyKey(user.id(), idempotencyKey));
        if (replay != null && replay.isPresent()) {
            if (!replay.get().getBookingId().equals(bookingId)) {
                throw PaymentErrors.idempotencyKeyReused();
            }
            return new CreateResult(view(replay.get().getId()), true);
        }

        BookingClient.BookingView booking = bookings.findForCaller(bookingId, bearerToken)
                .orElseThrow(PaymentErrors::bookingNotFound);
        Optional<Payment> active = tx.execute(s -> payments.findActiveByBooking(bookingId));
        if (active != null && active.isPresent()) {
            if (active.get().getStatus() == PaymentStatus.PENDING && active.get().getUserId().equals(user.id())) {
                return new CreateResult(view(active.get().getId()), true);
            }
            throw PaymentErrors.alreadyPaid();
        }
        Instant now = clock.instant();
        if (!"PENDING_PAYMENT".equals(booking.status()) || booking.expiresAt() == null
                || !booking.expiresAt().isAfter(now.plusSeconds(30))) {
            throw PaymentErrors.bookingNotPayable();
        }

        UUID paymentId;
        try {
            paymentId = tx.execute(s -> payments.saveAndFlush(Payment.pending(bookingId, user.id(), idempotencyKey,
                    booking.totalAmount(), booking.currency(), now)).getId());
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a parallel request (same key or same booking): answer with the winner.
            return tx.execute(s -> payments.findByUserIdAndIdempotencyKey(user.id(), idempotencyKey)
                    .or(() -> payments.findActiveByBooking(bookingId))
                    .filter(p -> p.getUserId().equals(user.id()) && p.getStatus() == PaymentStatus.PENDING)
                    .map(p -> new CreateResult(view(p.getId()), true)))
                    .orElseThrow(PaymentErrors::alreadyPaid);
        }

        try {
            // Outside any DB transaction: never hold a connection while talking to an external provider.
            PaymentProvider.Checkout checkout = provider.createCheckout(paymentId, booking.totalAmount(), booking.currency(),
                    properties.publicBaseUrl() + "/bookings/" + bookingId + "?payment=return");
            tx.executeWithoutResult(s -> payments.findById(paymentId).orElseThrow()
                    .attachCheckout(checkout.sessionId(), checkout.url(), clock.instant()));
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> payments.findById(paymentId).orElseThrow()
                    .transitionTo(PaymentStatus.FAILED, "Provider unavailable", clock.instant()));
            log.warn("Checkout creation failed for payment {}: {}", paymentId, e.getClass().getSimpleName());
            throw PaymentErrors.providerUnavailable();
        }
        return new CreateResult(view(paymentId), false);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public PaymentDtos.PaymentResponse get(CurrentUser user, UUID paymentId) {
        Payment p = payments.findById(paymentId).filter(x -> x.getUserId().equals(user.id()) || user.isAdmin())
                .orElseThrow(PaymentErrors::paymentNotFound);
        return toResponse(p);
    }

    @Transactional(readOnly = true)
    public List<PaymentDtos.PaymentResponse> listForBooking(CurrentUser user, UUID bookingId) {
        return payments.findByBookingIdOrderByCreatedAtDesc(bookingId).stream()
                .filter(p -> p.getUserId().equals(user.id()) || user.isAdmin())
                .map(this::toResponse).toList();
    }

    // ------------------------------------------------------------------ provider events

    /**
     * Applies one VERIFIED provider event exactly once (BR-24). Unknown references or impossible transitions are
     * recorded for review instead of being forced through.
     * @return false if the event had been processed before
     */
    @Transactional
    public boolean applyProviderEvent(ProviderEvent e) {
        int fresh = jdbc.update("""
                INSERT INTO webhook_events (provider, event_id, event_type, received_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (provider, event_id) DO NOTHING""", Payment.PROVIDER_SANDBOX, e.eventId(), e.type(),
                Timestamp.from(clock.instant()));
        if (fresh == 0) {
            log.info("Duplicate provider event {} ignored", e.eventId());
            return false;
        }
        Instant now = clock.instant();
        switch (e.type()) {
            case ProviderEvent.PAYMENT_SUCCEEDED -> onPaymentSucceeded(e, now);
            case ProviderEvent.PAYMENT_FAILED -> onPaymentFailed(e, now);
            case ProviderEvent.REFUND_SUCCEEDED, ProviderEvent.REFUND_FAILED -> onRefundResult(e, now);
            default -> log.warn("Ignoring unknown provider event type {}", e.type());
        }
        return true;
    }

    private void onPaymentSucceeded(ProviderEvent e, Instant now) {
        Optional<Payment> found = payments.findByProviderReferenceForUpdate(Payment.PROVIDER_SANDBOX, e.sessionId());
        if (found.isEmpty()) {
            log.error("payment.succeeded for unknown session {}", e.sessionId());
            return;
        }
        Payment p = found.get();
        if (p.getAmount().compareTo(e.amount()) != 0 || !p.getCurrencyCode().equals(e.currency())) {
            p.flagForReview("Amount mismatch reported by provider", now);
            log.error("Amount mismatch on payment {}: expected {} {}, provider reported {} {}", p.getId(),
                    p.getAmount(), p.getCurrencyCode(), e.amount(), e.currency());
            return;
        }
        if (!p.transitionTo(PaymentStatus.SUCCEEDED, "Provider confirmed payment", now)) {
            p.flagForReview("Payment captured while in status " + p.getStatus(), now);
            return;
        }
        outbox.append(Topics.PAYMENT_EVENTS, "PaymentSucceeded", p.getBookingId(),
                new PaymentEvents.PaymentSucceeded(p.getId(), p.getBookingId(), p.getAmount(), p.getCurrencyCode()));
    }

    private void onPaymentFailed(ProviderEvent e, Instant now) {
        payments.findByProviderReferenceForUpdate(Payment.PROVIDER_SANDBOX, e.sessionId()).ifPresent(p -> {
            String reason = e.reason() == null ? "Payment failed" : e.reason();
            if (p.transitionTo(PaymentStatus.FAILED, reason, now)) {
                outbox.append(Topics.PAYMENT_EVENTS, "PaymentFailed", p.getBookingId(),
                        new PaymentEvents.PaymentFailed(p.getId(), p.getBookingId(), reason));
            }
        });
    }

    private void onRefundResult(ProviderEvent e, Instant now) {
        if (e.refundReference() == null) {
            log.error("Refund event {} without refund reference", e.eventId());
            return;
        }
        refunds.findByIdForUpdate(e.refundReference()).ifPresent(r -> {
            boolean succeeded = ProviderEvent.REFUND_SUCCEEDED.equals(e.type());
            if (!r.complete(succeeded, e.eventId(), now)) {
                return;
            }
            Payment p = payments.findByIdForUpdate(r.getPaymentId()).orElseThrow();
            if (succeeded) {
                p.transitionTo(PaymentStatus.REFUNDED, "Refund confirmed by provider", now);
                outbox.append(Topics.PAYMENT_EVENTS, "RefundSucceeded", p.getBookingId(),
                        new PaymentEvents.RefundSucceeded(p.getId(), p.getBookingId(), r.getId(), r.getAmount()));
            } else {
                p.transitionTo(PaymentStatus.SUCCEEDED, "Refund failed at provider", now);
                p.flagForReview("Refund failed at provider", now);
                outbox.append(Topics.PAYMENT_EVENTS, "RefundFailed", p.getBookingId(),
                        new PaymentEvents.RefundFailed(p.getId(), p.getBookingId(), r.getId()));
            }
        });
    }

    // ------------------------------------------------------------------ refunds

    /**
     * Opens a full refund for a captured payment. Idempotent: if a refund is already pending or done, nothing new is
     * created (BR-27 "retry must not refund twice"). The provider call happens after commit ({@link RefundSubmitter}).
     * @return true if a new refund was opened
     */
    @Transactional
    public boolean startRefund(UUID paymentId, String reason, boolean flagForReview) {
        Payment p = payments.findByIdForUpdate(paymentId).orElseThrow(PaymentErrors::paymentNotFound);
        Instant now = clock.instant();
        if (flagForReview) {
            p.flagForReview(reason, now);
        }
        if (p.getStatus() == PaymentStatus.REFUND_PENDING || p.getStatus() == PaymentStatus.REFUNDED) {
            return false;
        }
        if (!p.transitionTo(PaymentStatus.REFUND_PENDING, reason, now)) {
            throw PaymentErrors.notRefundable();
        }
        Refund refund = refunds.save(new Refund(p.getId(), p.getAmount(), reason, now));
        outbox.append(Topics.PAYMENT_EVENTS, "RefundRequested", p.getBookingId(),
                new PaymentEvents.RefundRequested(p.getId(), p.getBookingId(), refund.getId()));
        events.publishEvent(new PaymentEvents.RefundCreated(refund.getId()));
        return true;
    }

    /** Refunds the captured payment of a booking, if there is one (used for eligible cancellations). */
    @Transactional
    public void refundBookingIfPaid(UUID bookingId, String reason) {
        payments.findActiveByBooking(bookingId)
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED)
                .ifPresent(p -> startRefund(p.getId(), reason, false));
    }

    @Transactional
    public PaymentDtos.PaymentResponse adminRefund(UUID paymentId, String reason) {
        startRefund(paymentId, "Admin: " + reason, false);
        return toResponse(payments.findById(paymentId).orElseThrow());
    }

    private PaymentDtos.PaymentResponse view(UUID paymentId) {
        return tx.execute(s -> toResponse(payments.findById(paymentId).orElseThrow()));
    }

    private PaymentDtos.PaymentResponse toResponse(Payment p) {
        return PaymentDtos.PaymentResponse.from(p, refunds.findFirstByPaymentIdOrderByCreatedAtDesc(p.getId()).orElse(null));
    }

    public record CreateResult(PaymentDtos.PaymentResponse payment, boolean replayed) {
    }
}
