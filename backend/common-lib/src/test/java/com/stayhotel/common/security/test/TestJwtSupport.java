package com.stayhotel.common.security.test;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Test-only token factory. Signs RS256 tokens with an in-memory key and offers a matching decoder that
 * applies the same issuer/audience validation as production resource servers.
 */
public final class TestJwtSupport {

    public static final String ISSUER = "https://auth.stayhotel.local";
    public static final String AUDIENCE = "stay-hotel-api";

    private static final RSAKey KEY = generateKey("test-key");
    private static final RSAKey OTHER_KEY = generateKey("attacker-key");

    private TestJwtSupport() {
    }

    public static String token(UUID userId, String... roles) {
        return sign(KEY, userId, ISSUER, AUDIENCE, Instant.now().plus(Duration.ofMinutes(5)), roles);
    }

    public static String expiredToken(UUID userId, String... roles) {
        return sign(KEY, userId, ISSUER, AUDIENCE, Instant.now().minus(Duration.ofMinutes(5)), roles);
    }

    public static String tokenSignedByUnknownKey(UUID userId, String... roles) {
        return sign(OTHER_KEY, userId, ISSUER, AUDIENCE, Instant.now().plus(Duration.ofMinutes(5)), roles);
    }

    public static String tokenWithAudience(UUID userId, String audience, String... roles) {
        return sign(KEY, userId, ISSUER, audience, Instant.now().plus(Duration.ofMinutes(5)), roles);
    }

    public static JwtDecoder decoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256).build();
            applyValidators(decoder);
            return decoder;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(RSAKey key, UUID userId, String issuer, String audience, Instant expiresAt, String... roles) {
        Instant issuedAt = expiresAt.minus(Duration.ofMinutes(15));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("email", "user-" + userId + "@example.com")
                .claim("roles", List.of(roles))
                .build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static RSAKey generateKey(String kid) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate()).keyID(kid).build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Same validators the services get from spring.security.oauth2.resourceserver.jwt.{issuer-uri,audiences}. */
    private static void applyValidators(NimbusJwtDecoder decoder) {
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(AUDIENCE));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), audience));
    }
}
