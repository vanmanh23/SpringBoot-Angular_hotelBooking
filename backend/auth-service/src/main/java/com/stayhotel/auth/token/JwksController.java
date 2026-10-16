package com.stayhotel.auth.token;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/** Public verification keys for resource servers. Internal network only; not routed by the gateway. */
@RestController
class JwksController {

    private final Map<String, Object> jwks;

    JwksController(RSAKey signingKey) {
        this.jwks = new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }

    @GetMapping("/oauth2/jwks")
    ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5))).body(jwks);
    }
}
