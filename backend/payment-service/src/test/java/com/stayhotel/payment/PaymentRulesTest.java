package com.stayhotel.payment;

import com.stayhotel.payment.payment.PaymentStatus;
import com.stayhotel.payment.provider.WebhookSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRulesTest {

    static final String SECRET = "unit-test-secret-0123456789abcdef";

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource({
            "PENDING, SUCCEEDED, true", "PENDING, FAILED, true", "PENDING, CANCELLED, true",
            "SUCCEEDED, REFUND_PENDING, true", "REFUND_PENDING, REFUNDED, true", "REFUND_PENDING, SUCCEEDED, true",
            "SUCCEEDED, PENDING, false", "SUCCEEDED, FAILED, false", "FAILED, SUCCEEDED, false",
            "REFUNDED, REFUND_PENDING, false", "PENDING, REFUNDED, false", "CANCELLED, SUCCEEDED, false"
    })
    void paymentStateMachine(PaymentStatus from, PaymentStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void validSignatureVerifies() {
        Instant now = Instant.now();
        String body = "{\"eventId\":\"evt_1\"}";
        String sig = WebhookSignature.sign(SECRET, now.getEpochSecond(), body);
        assertThat(WebhookSignature.verify(SECRET, Long.toString(now.getEpochSecond()), sig, body, now, Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void tamperedBodyWrongSecretStaleOrMissingHeadersFail() {
        Instant now = Instant.now();
        long ts = now.getEpochSecond();
        String body = "{\"amount\":100}";
        String sig = WebhookSignature.sign(SECRET, ts, body);
        Duration tolerance = Duration.ofMinutes(5);

        assertThat(WebhookSignature.verify(SECRET, Long.toString(ts), sig, "{\"amount\":1}", now, tolerance)).isFalse();
        assertThat(WebhookSignature.verify("another-secret-0123456789abcdefgh", Long.toString(ts), sig, body, now, tolerance)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, Long.toString(ts), sig, body, now.plus(Duration.ofMinutes(6)), tolerance)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, null, sig, body, now, tolerance)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, "abc", sig, body, now, tolerance)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, Long.toString(ts), "v1=00", body, now, tolerance)).isFalse();
    }
}
