package com.stayhotel.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

@Validated
@ConfigurationProperties("auth")
public record AuthProperties(@Valid @NotNull Jwt jwt, @Valid @NotNull Refresh refresh, @Valid @NotNull Login login,
                             @NotNull BootstrapAdmin bootstrapAdmin) {

    /**
     * @param privateKey        base64-encoded PKCS#8 DER RSA private key (public key is derived from it)
     * @param allowEphemeralKey dev/test only: generate a throw-away key when none is configured
     */
    public record Jwt(String privateKey, @NotBlank String keyId, @NotBlank String issuer, @NotBlank String audience,
                      @NotNull Duration accessTokenTtl, boolean allowEphemeralKey) {
    }

    public record Refresh(@NotNull Duration ttl, @NotNull Duration reuseGrace, @NotBlank String cookieName,
                          @NotBlank String cookiePath, boolean cookieSecure) {
    }

    public record Login(@Min(1) int maxFailedAttempts, @NotNull Duration lockoutDuration) {
    }

    public record BootstrapAdmin(String email, String password, String fullName) {
    }
}
