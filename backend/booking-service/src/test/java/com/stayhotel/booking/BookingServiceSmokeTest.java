package com.stayhotel.booking;

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
class BookingServiceSmokeTest {

    @Autowired
    TestRestTemplate http;

    @Test
    void healthIsUpWithDatabaseAndMigrations() {
        ResponseEntity<String> res = http.getForEntity("/actuator/health", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void unknownPathReturnsUniformErrorBody() {
        ResponseEntity<String> res = http.getForEntity("/api/v1/does-not-exist", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("\"code\":\"NOT_FOUND\"").doesNotContain("trace");
    }
}
