package com.stayhotel.payment.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param publicBaseUrl    origin the browser uses (checkout and return URLs are built from it, never from input)
 * @param bookingServiceUrl internal URL of booking-service
 */
@Validated
@ConfigurationProperties("payment")
public record PaymentProperties(@NotBlank String publicBaseUrl, @NotBlank String bookingServiceUrl,
                                @NotNull Sandbox sandbox, @NotNull Duration refundRetryAfter) {

    /**
     * @param webhookSecret shared HMAC secret (>= 32 chars), from SANDBOX_WEBHOOK_SECRET
     * @param webhookUrl    where the simulated provider delivers webhooks (internal URL of this service)
     */
    public record Sandbox(@NotBlank @Size(min = 32) String webhookSecret, @NotBlank String webhookUrl,
                          @NotNull Duration webhookTolerance) {
    }
}
