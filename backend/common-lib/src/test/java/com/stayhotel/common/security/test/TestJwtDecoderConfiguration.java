package com.stayhotel.common.security.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Replaces the JWKS-based decoder in service tests so no running auth-service is needed. */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtDecoderConfiguration {

    @Bean
    JwtDecoder jwtDecoder() {
        return TestJwtSupport.decoder();
    }
}
