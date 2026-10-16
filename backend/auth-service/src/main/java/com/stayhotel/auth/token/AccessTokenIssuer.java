package com.stayhotel.auth.token;

import com.nimbusds.jose.jwk.RSAKey;
import com.stayhotel.auth.config.AuthProperties;
import com.stayhotel.auth.user.Role;
import com.stayhotel.auth.user.User;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final AuthProperties.Jwt properties;
    private final String keyId;

    AccessTokenIssuer(JwtEncoder encoder, AuthProperties properties, RSAKey signingKey) {
        this.encoder = encoder;
        this.properties = properties.jwt();
        this.keyId = signingKey.getKeyID();
    }

    String issue(User user, Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim("email", user.getEmail())
                .claim("roles", user.getRoles().stream().map(Role::name).sorted().toList())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    long ttlSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }
}
