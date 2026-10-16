package com.stayhotel.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AuthServiceSmokeTest {

    @Autowired
    TestRestTemplate http;

    @Test
    void healthIsPublicAndUp() {
        ResponseEntity<String> res = http.getForEntity("/actuator/health", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void unknownPathRequiresAuthenticationWithUniformBody() {
        ResponseEntity<String> res = http.getForEntity("/api/v1/does-not-exist", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains("\"code\":\"UNAUTHORIZED\"").doesNotContain("trace");
    }

    @Test
    void sensitiveActuatorEndpointsAreNotExposed() {
        assertThat(http.getForEntity("/actuator/env", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND);
    }
}
