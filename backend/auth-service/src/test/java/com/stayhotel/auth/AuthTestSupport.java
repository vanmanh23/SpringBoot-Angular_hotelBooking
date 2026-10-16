package com.stayhotel.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Small HTTP helpers shared by auth-service integration tests. */
final class AuthTestSupport {

    static final String PASSWORD = "Passw0rd-good";
    static final String ADMIN_EMAIL = "admin@stayhotel.test";
    static final String ADMIN_PASSWORD = "Admin-Passw0rd-123";

    private final TestRestTemplate http;
    private final ObjectMapper json;

    AuthTestSupport(TestRestTemplate http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    ResponseEntity<String> post(String path, Object body) {
        return exchange(HttpMethod.POST, path, body, null, null);
    }

    ResponseEntity<String> exchange(HttpMethod method, String path, Object body, String bearer, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    ResponseEntity<String> register(String email) {
        return post("/api/v1/auth/register", Map.of("email", email, "password", PASSWORD, "fullName", "Test User"));
    }

    Login login(String email, String password) {
        ResponseEntity<String> res = post("/api/v1/auth/login", Map.of("email", email, "password", password));
        return new Login(res, res.getStatusCode().is2xxSuccessful() ? field(res, "accessToken") : null,
                refreshCookie(res));
    }

    Login registerAndLogin() {
        String email = uniqueEmail();
        register(email);
        return login(email, PASSWORD);
    }

    Login refresh(String cookie) {
        ResponseEntity<String> res = exchange(HttpMethod.POST, "/api/v1/auth/refresh", null, null, cookie);
        return new Login(res, res.getStatusCode().is2xxSuccessful() ? field(res, "accessToken") : null,
                refreshCookie(res));
    }

    JsonNode body(ResponseEntity<String> res) {
        try {
            return json.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String field(ResponseEntity<String> res, String name) {
        return body(res).get(name).asText();
    }

    static String setCookieHeader(ResponseEntity<String> res) {
        List<String> cookies = res.getHeaders().get(HttpHeaders.SET_COOKIE);
        return cookies == null ? null : cookies.stream().filter(c -> c.startsWith("SH_REFRESH=")).findFirst().orElse(null);
    }

    /** "SH_REFRESH=value" as a request Cookie header, or null if absent/cleared. */
    static String refreshCookie(ResponseEntity<String> res) {
        String header = setCookieHeader(res);
        if (header == null) {
            return null;
        }
        String pair = header.split(";", 2)[0];
        return pair.equals("SH_REFRESH=") ? null : pair;
    }

    record Login(ResponseEntity<String> response, String accessToken, String cookie) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
