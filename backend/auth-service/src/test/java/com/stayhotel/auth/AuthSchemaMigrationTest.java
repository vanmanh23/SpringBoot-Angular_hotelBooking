package com.stayhotel.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuthSchemaMigrationTest {

    private static final String INSERT_USER =
            "INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'x', 'Test', ?)";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayAppliedV1() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void emailMustBeUniqueAndNormalized() {
        jdbc.update(INSERT_USER, UUID.randomUUID(), "unique@example.com", "ACTIVE");

        assertThatThrownBy(() -> jdbc.update(INSERT_USER, UUID.randomUUID(), "unique@example.com", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(INSERT_USER, UUID.randomUUID(), "Mixed@Example.com", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownStatusAndRoleAreRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(INSERT_USER, id, "status@example.com", "SUPERUSER"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update(INSERT_USER, id, "role@example.com", "ACTIVE");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'ROOT')", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
