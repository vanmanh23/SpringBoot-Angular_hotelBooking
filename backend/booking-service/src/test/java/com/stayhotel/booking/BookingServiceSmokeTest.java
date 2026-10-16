package com.stayhotel.booking;

import com.stayhotel.common.security.test.TestJwtDecoderConfiguration;
import com.stayhotel.common.security.test.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestJwtDecoderConfiguration.class})
class BookingServiceSmokeTest {

    @Autowired
    TestRestTemplate http;

    @Test
    void healthIsPublicAndUp() {
        ResponseEntity<String> res = http.getForEntity("/actuator/health", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void apiRequiresAuthentication() {
        ResponseEntity<String> res = http.getForEntity("/api/v1/anything", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains("\"code\":\"UNAUTHORIZED\"");
        assertThat(res.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void invalidTokensAreRejected() {
        UUID user = UUID.randomUUID();
        for (String token : new String[]{TestJwtSupport.expiredToken(user, "CUSTOMER"),
                TestJwtSupport.tokenSignedByUnknownKey(user, "CUSTOMER"),
                TestJwtSupport.tokenWithAudience(user, "other-api", "CUSTOMER"), "not-a-jwt"}) {
            assertThat(get("/api/v1/anything", token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void validTokenReachesMvcAndGetsUniform404() {
        ResponseEntity<String> res = get("/api/v1/does-not-exist", TestJwtSupport.token(UUID.randomUUID(), "CUSTOMER"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("\"code\":\"NOT_FOUND\"").doesNotContain("trace");
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
