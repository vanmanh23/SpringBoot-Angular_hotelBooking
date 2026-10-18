package com.stayhotel.payment.payment;

import com.stayhotel.payment.config.PaymentProperties;
import com.stayhotel.payment.provider.PaymentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * Sends refunds to the provider AFTER the refund row is committed, and resubmits refunds that got no answer.
 * Resubmission is safe because the refund id is the provider idempotency key.
 */
@Component
class RefundSubmitter {

    private static final Logger log = LoggerFactory.getLogger(RefundSubmitter.class);

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentProvider provider;
    private final TransactionTemplate tx;
    private final PaymentProperties properties;
    private final Clock clock;

    RefundSubmitter(RefundRepository refunds, PaymentRepository payments, PaymentProvider provider, TransactionTemplate tx,
                    PaymentProperties properties, Clock clock) {
        this.refunds = refunds;
        this.payments = payments;
        this.provider = provider;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRefundCreated(PaymentEvents.RefundCreated event) {
        submit(event.refundId());
    }

    @Scheduled(fixedDelayString = "${payment.refund-retry-interval:60000}", initialDelay = 30000)
    void resubmitStale() {
        refunds.findStalePending(clock.instant().minus(properties.refundRetryAfter())).forEach(this::submit);
    }

    void submit(UUID refundId) {
        Submission s = tx.execute(status -> refunds.findById(refundId)
                .filter(r -> r.getStatus() == Refund.Status.PENDING)
                .map(r -> {
                    r.recordAttempt(clock.instant());
                    Payment p = payments.findById(r.getPaymentId()).orElseThrow();
                    return new Submission(r.getId().toString(), p.getProviderReference(), r.getAmount());
                }).orElse(null));
        if (s == null) {
            return;
        }
        try {
            provider.requestRefund(s.idempotencyKey(), s.sessionId(), s.amount());
        } catch (RuntimeException e) {
            log.warn("Refund {} submission failed ({}); it will be retried", refundId, e.getClass().getSimpleName());
        }
    }

    private record Submission(String idempotencyKey, String sessionId, java.math.BigDecimal amount) {
    }
}
