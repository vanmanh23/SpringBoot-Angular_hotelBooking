package com.stayhotel.payment.provider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * {@code v1=hex(HMAC-SHA256(secret, timestamp + "." + body))}, the scheme used by Stripe-like providers.
 * Verification is constant-time and rejects timestamps outside the tolerance window (replay protection).
 */
public final class WebhookSignature {

    public static final String SIGNATURE_HEADER = "X-Sandbox-Signature";
    public static final String TIMESTAMP_HEADER = "X-Sandbox-Timestamp";

    private WebhookSignature() {
    }

    public static String sign(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "v1=" + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(String secret, String timestampHeader, String signatureHeader, String body,
                                 Instant now, Duration tolerance) {
        if (timestampHeader == null || signatureHeader == null || body == null) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(timestamp), now).abs().compareTo(tolerance) > 0) {
            return false;
        }
        byte[] expected = sign(secret, timestamp, body).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
    }
}
