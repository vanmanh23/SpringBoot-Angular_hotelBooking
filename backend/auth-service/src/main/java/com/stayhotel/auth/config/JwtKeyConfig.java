package com.stayhotel.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;

/** RS256 signing key for access tokens. The private key never leaves auth-service; others fetch /oauth2/jwks. */
@Configuration
class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    @Bean
    RSAKey signingKey(AuthProperties properties) throws GeneralSecurityException {
        AuthProperties.Jwt jwt = properties.jwt();
        if (jwt.privateKey() == null || jwt.privateKey().isBlank()) {
            if (!jwt.allowEphemeralKey()) {
                throw new IllegalStateException(
                        "AUTH_JWT_PRIVATE_KEY is not set. Generate one with scripts/generate-jwt-key.sh");
            }
            log.warn("No JWT signing key configured: using an EPHEMERAL key. Tokens become invalid on restart. Dev/test only.");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate()).keyID(jwt.keyId()).build();
        }
        KeyFactory factory = KeyFactory.getInstance("RSA");
        byte[] der = Base64.getDecoder().decode(jwt.privateKey().replaceAll("\\s", ""));
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
        if (privateKey.getModulus().bitLength() < 2048) {
            throw new IllegalStateException("JWT signing key must be at least 2048 bits");
        }
        RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(jwt.keyId()).build();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(signingKey)));
    }

    /** auth-service validates its own tokens locally with the same rules as the other services. */
    @Bean
    JwtDecoder jwtDecoder(RSAKey signingKey, AuthProperties properties) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        String audience = properties.jwt().audience();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience))));
        return decoder;
    }
}
