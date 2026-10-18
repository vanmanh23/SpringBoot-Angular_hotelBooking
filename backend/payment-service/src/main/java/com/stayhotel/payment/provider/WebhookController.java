package com.stayhotel.payment.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.payment.PaymentErrors;
import com.stayhotel.payment.config.PaymentProperties;
import com.stayhotel.payment.payment.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;

/**
 * Provider → merchant notifications. The raw body is verified BEFORE parsing; only then is anything applied.
 * Duplicates answer 200 so the provider stops retrying, without changing state again (BR-24).
 */
@RestController
class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final PaymentService payments;
    private final ObjectMapper objectMapper;
    private final PaymentProperties properties;
    private final Clock clock;

    WebhookController(PaymentService payments, ObjectMapper objectMapper, PaymentProperties properties, Clock clock) {
        this.payments = payments;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping("/api/v1/payments/webhooks/sandbox")
    ResponseEntity<Map<String, Object>> sandbox(@RequestHeader(name = WebhookSignature.TIMESTAMP_HEADER, required = false) String timestamp,
                                                @RequestHeader(name = WebhookSignature.SIGNATURE_HEADER, required = false) String signature,
                                                @RequestBody String body) {
        if (!WebhookSignature.verify(properties.sandbox().webhookSecret(), timestamp, signature, body, clock.instant(),
                properties.sandbox().webhookTolerance())) {
            log.warn("Rejected webhook with invalid signature or timestamp");
            throw PaymentErrors.invalidSignature();
        }
        ProviderEvent event;
        try {
            event = objectMapper.readValue(body, ProviderEvent.class);
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "unreadable event"));
        }
        boolean applied = payments.applyProviderEvent(event);
        return ResponseEntity.ok(Map.of("received", true, "duplicate", !applied));
    }
}
