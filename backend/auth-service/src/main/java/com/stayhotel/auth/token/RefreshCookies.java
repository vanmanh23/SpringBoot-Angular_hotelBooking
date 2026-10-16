package com.stayhotel.auth.token;

import com.stayhotel.auth.config.AuthProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Builds the HttpOnly refresh cookie; the token is never exposed to JavaScript or JSON bodies. */
@Component
public class RefreshCookies {

    private final AuthProperties.Refresh properties;

    RefreshCookies(AuthProperties properties) {
        this.properties = properties.refresh();
    }

    public String name() {
        return properties.cookieName();
    }

    public ResponseCookie issue(String rawToken) {
        return base(rawToken).maxAge(properties.ttl()).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(properties.cookieName(), value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path(properties.cookiePath());
    }
}
