package com.stayhotel.payment.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.payment.config.PaymentProperties;
import com.stayhotel.payment.provider.PaymentProvider;
import com.stayhotel.payment.provider.WebhookSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Simulated external payment gateway (DECISIONS D-023). It owns the {@code sandbox_*} tables, hosts a checkout page,
 * and reports outcomes ONLY through HMAC-signed webhooks delivered over HTTP (with redelivery), exactly like a real
 * provider. The rest of payment-service talks to it through {@link PaymentProvider} and the webhook endpoint.
 */
@Component
public class SandboxGateway implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(SandboxGateway.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final PaymentProperties properties;
    private final Clock clock;
    private final RestClient http;
    private final Environment environment;

    SandboxGateway(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper objectMapper, PaymentProperties properties,
                   Clock clock, RestClient.Builder builder, Environment environment) {
        this.environment = environment;
        this.jdbc = jdbc;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.requestFactory(factory).build();
    }

    // ---------------------------------------------------------------- PaymentProvider (merchant-facing API)

    @Override
    public Checkout createCheckout(UUID merchantReference, BigDecimal amount, String currency, String returnUrl) {
        String sessionId = "cs_" + randomHex(12);
        jdbc.update("""
                INSERT INTO sandbox_sessions (id, merchant_reference, amount, currency_code, return_url, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'OPEN', ?)""", sessionId, merchantReference, amount, currency, returnUrl,
                Timestamp.from(clock.instant()));
        return new Checkout(sessionId, properties.publicBaseUrl() + "/sandbox-gateway/checkout/" + sessionId);
    }

    @Override
    public void requestRefund(String idempotencyKey, String sessionId, BigDecimal amount) {
        Optional<Session> session = find(sessionId);
        boolean refundable = session.filter(s -> "PAID".equals(s.status()) && amount.compareTo(s.amount()) <= 0).isPresent();
        // Idempotent on the merchant's key: a repeated request never creates a second refund.
        int inserted = jdbc.update("""
                INSERT INTO sandbox_refunds (idempotency_key, session_id, amount, outcome, webhook_event_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING""",
                idempotencyKey, sessionId, amount, refundable ? "SUCCEEDED" : "FAILED", "evt_" + randomHex(12),
                Timestamp.from(clock.instant()));
        if (inserted == 0) {
            log.info("Sandbox: duplicate refund request {} ignored", idempotencyKey);
        }
        deliverRefundWebhook(idempotencyKey);
    }

    // ---------------------------------------------------------------- hosted checkout (customer-facing)

    public Optional<Session> find(String sessionId) {
        return jdbc.query("SELECT * FROM sandbox_sessions WHERE id = ?", (rs, i) -> new Session(rs.getString("id"),
                rs.getObject("merchant_reference", UUID.class), rs.getBigDecimal("amount"), rs.getString("currency_code"),
                rs.getString("return_url"), rs.getString("status"), rs.getString("webhook_event_id"),
                rs.getBoolean("webhook_delivered")), sessionId).stream().findFirst();
    }

    /** Customer pressed Pay/Decline. Returns the return URL, or empty if the session was already completed. */
    public Optional<String> complete(String sessionId, boolean paid) {
        String eventId = "evt_" + randomHex(12);
        Integer updated = tx.execute(s -> jdbc.update("""
                UPDATE sandbox_sessions SET status = ?, webhook_event_id = ?, completed_at = ?
                WHERE id = ? AND status = 'OPEN'""", paid ? "PAID" : "DECLINED", eventId, Timestamp.from(clock.instant()),
                sessionId));
        if (updated == null || updated == 0) {
            return Optional.empty();
        }
        deliverPaymentWebhook(sessionId);
        return find(sessionId).map(Session::returnUrl);
    }

    /** Test/support hook: send the stored webhook again (providers do this on timeouts). */
    public void redeliver(String sessionId) {
        jdbc.update("UPDATE sandbox_sessions SET webhook_delivered = FALSE WHERE id = ?", sessionId);
        deliverPaymentWebhook(sessionId);
    }

    /** Providers retry until the merchant answers 2xx. */
    @Scheduled(fixedDelayString = "${payment.sandbox.redelivery-interval:10000}")
    public void redeliverPending() {
        List<String> sessions = jdbc.queryForList(
                "SELECT id FROM sandbox_sessions WHERE status <> 'OPEN' AND NOT webhook_delivered", String.class);
        sessions.forEach(this::deliverPaymentWebhook);
        List<String> refunds = jdbc.queryForList(
                "SELECT idempotency_key FROM sandbox_refunds WHERE NOT webhook_delivered", String.class);
        refunds.forEach(this::deliverRefundWebhook);
    }

    // ---------------------------------------------------------------- webhook delivery

    private void deliverPaymentWebhook(String sessionId) {
        find(sessionId).filter(s -> !"OPEN".equals(s.status())).ifPresent(s -> {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventId", s.webhookEventId());
            event.put("type", "PAID".equals(s.status()) ? "payment.succeeded" : "payment.failed");
            event.put("sessionId", s.id());
            event.put("merchantReference", s.merchantReference());
            event.put("amount", s.amount());
            event.put("currency", s.currency());
            event.put("occurredAt", clock.instant());
            if (!"PAID".equals(s.status())) {
                event.put("reason", "Card declined (sandbox)");
            }
            if (send(event)) {
                jdbc.update("UPDATE sandbox_sessions SET webhook_delivered = TRUE WHERE id = ?", sessionId);
            }
        });
    }

    private void deliverRefundWebhook(String idempotencyKey) {
        jdbc.query("""
                SELECT r.idempotency_key, r.session_id, r.amount, r.outcome, r.webhook_event_id, s.merchant_reference, s.currency_code
                FROM sandbox_refunds r JOIN sandbox_sessions s ON s.id = r.session_id WHERE r.idempotency_key = ?""",
                (rs, i) -> {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("eventId", rs.getString("webhook_event_id"));
                    event.put("type", "SUCCEEDED".equals(rs.getString("outcome")) ? "refund.succeeded" : "refund.failed");
                    event.put("sessionId", rs.getString("session_id"));
                    event.put("merchantReference", rs.getObject("merchant_reference", UUID.class));
                    event.put("refundReference", UUID.fromString(rs.getString("idempotency_key")));
                    event.put("amount", rs.getBigDecimal("amount"));
                    event.put("currency", rs.getString("currency_code"));
                    event.put("occurredAt", clock.instant());
                    return event;
                }, idempotencyKey).forEach(event -> {
                    if (send(event)) {
                        jdbc.update("UPDATE sandbox_refunds SET webhook_delivered = TRUE WHERE idempotency_key = ?",
                                idempotencyKey);
                    }
                });
    }

    private boolean send(Map<String, Object> event) {
        try {
            String body = objectMapper.writeValueAsString(event);
            long timestamp = clock.instant().getEpochSecond();
            // Resolved per call so a placeholder like ${local.server.port} works once the server is up.
            http.post().uri(environment.resolvePlaceholders(properties.sandbox().webhookUrl()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WebhookSignature.TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(WebhookSignature.SIGNATURE_HEADER,
                            WebhookSignature.sign(properties.sandbox().webhookSecret(), timestamp, body))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Sandbox webhook {} not delivered ({}); will retry", event.get("eventId"), e.getClass().getSimpleName());
            return false;
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public record Session(String id, UUID merchantReference, BigDecimal amount, String currency, String returnUrl,
                          String status, String webhookEventId, boolean webhookDelivered) {
    }
}
