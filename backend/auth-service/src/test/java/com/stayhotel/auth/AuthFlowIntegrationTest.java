package com.stayhotel.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.auth.AuthTestSupport.Login;
import com.stayhotel.auth.admin.AuditLog;
import com.stayhotel.auth.admin.AuditLogRepository;
import com.stayhotel.common.security.test.TestJwtSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.stayhotel.auth.AuthTestSupport.PASSWORD;
import static com.stayhotel.auth.AuthTestSupport.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, AuthTestSupport.ClockConfig.class})
class AuthFlowIntegrationTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    MutableClock clock;
    @Autowired
    AuditLogRepository auditLogs;

    AuthTestSupport api;

    @BeforeEach
    void setUp() {
        api = new AuthTestSupport(http, objectMapper);
    }

    @Nested
    class Registration {

        @Test
        void createsCustomerWithoutLeakingPassword() {
            String email = uniqueEmail();
            ResponseEntity<String> res = api.register(email);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode body = api.body(res);
            assertThat(body.get("email").asText()).isEqualTo(email);
            assertThat(body.get("roles").toString()).isEqualTo("[\"CUSTOMER\"]");
            assertThat(res.getBody()).doesNotContain(PASSWORD).doesNotContainIgnoringCase("passwordHash");
        }

        @Test
        void cannotSelfAssignAdminRole() {
            ResponseEntity<String> res = api.post("/api/v1/auth/register", Map.of("email", uniqueEmail(),
                    "password", PASSWORD, "fullName", "Sneaky", "roles", List.of("ADMIN"), "status", "ACTIVE"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(api.body(res).get("roles").toString()).isEqualTo("[\"CUSTOMER\"]");
        }

        @Test
        void duplicateEmailIsRejectedCaseInsensitively() {
            String email = uniqueEmail();
            api.register(email);

            ResponseEntity<String> res = api.register(email.toUpperCase());
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(api.field(res, "code")).isEqualTo("EMAIL_ALREADY_REGISTERED");
        }

        @Test
        void invalidInputReturnsFieldErrors() {
            ResponseEntity<String> res = api.post("/api/v1/auth/register",
                    Map.of("email", "not-an-email", "password", "short", "fullName", ""));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(api.field(res, "code")).isEqualTo("VALIDATION_FAILED");
            assertThat(api.body(res).get("fieldErrors").findValuesAsText("field"))
                    .containsExactlyInAnyOrder("email", "password", "fullName");
        }
    }

    @Nested
    class LoginFlow {

        @Test
        void issuesAccessTokenAndHardenedRefreshCookie() {
            String email = uniqueEmail();
            api.register(email);
            Login login = api.login(email.toUpperCase(), PASSWORD);

            assertThat(login.response().getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode body = api.body(login.response());
            assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
            assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
            assertThat(login.response().getBody()).doesNotContain(login.cookie().substring("SH_REFRESH=".length()));
            assertThat(login.response().getHeaders().getCacheControl()).contains("no-store");

            String setCookie = AuthTestSupport.setCookieHeader(login.response());
            assertThat(setCookie).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=604800");

            ResponseEntity<String> me = api.exchange(HttpMethod.GET, "/api/v1/users/me", null, login.accessToken(), null);
            assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(api.field(me, "email")).isEqualTo(email);
        }

        @Test
        void unknownEmailAndWrongPasswordAreIndistinguishable() {
            String email = uniqueEmail();
            api.register(email);

            ResponseEntity<String> wrongPassword = api.login(email, "Wrong-passw0rd").response();
            ResponseEntity<String> unknownEmail = api.login(uniqueEmail(), PASSWORD).response();

            assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(api.field(wrongPassword, "code")).isEqualTo(api.field(unknownEmail, "code"))
                    .isEqualTo("INVALID_CREDENTIALS");
            assertThat(api.field(wrongPassword, "message")).isEqualTo(api.field(unknownEmail, "message"));
        }

        @Test
        void fiveFailuresLockOutTemporarilyEvenForCorrectPassword() {
            String email = uniqueEmail();
            api.register(email);
            for (int i = 0; i < 5; i++) {
                assertThat(api.login(email, "Wrong-passw0rd").response().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            }

            assertThat(api.login(email, PASSWORD).response().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            clock.advance(Duration.ofMinutes(16));
            assertThat(api.login(email, PASSWORD).response().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    class RefreshRotation {

        @Test
        void refreshRotatesTokenAndCookie() {
            Login login = api.registerAndLogin();

            Login refreshed = api.refresh(login.cookie());

            assertThat(refreshed.response().getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(refreshed.accessToken()).isNotBlank();
            assertThat(refreshed.cookie()).isNotNull().isNotEqualTo(login.cookie());
        }

        @Test
        void reuseOfRotatedTokenRevokesWholeFamily() {
            Login login = api.registerAndLogin();
            Login refreshed = api.refresh(login.cookie());
            clock.advance(Duration.ofSeconds(31));

            Login replay = api.refresh(login.cookie());
            assertThat(replay.response().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(api.field(replay.response(), "code")).isEqualTo("REFRESH_TOKEN_REUSED");
            assertThat(AuthTestSupport.setCookieHeader(replay.response())).contains("Max-Age=0");

            Login legitimate = api.refresh(refreshed.cookie());
            assertThat(legitimate.response().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void parallelRefreshWithinGraceIsStaleWithoutRevokingSession() {
            Login login = api.registerAndLogin();
            Login first = api.refresh(login.cookie());

            Login second = api.refresh(login.cookie());
            assertThat(second.response().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(api.field(second.response(), "code")).isEqualTo("REFRESH_TOKEN_STALE");
            assertThat(AuthTestSupport.setCookieHeader(second.response())).isNull();

            assertThat(api.refresh(first.cookie()).response().getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void concurrentRefreshesWithSameTokenRotateExactlyOnce() throws Exception {
            Login login = api.registerAndLogin();
            int parallel = 4;
            ExecutorService pool = Executors.newFixedThreadPool(parallel);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<Login>> futures = new ArrayList<>();
                for (int i = 0; i < parallel; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return api.refresh(login.cookie());
                    }));
                }
                start.countDown();
                List<Login> results = new ArrayList<>();
                for (Future<Login> f : futures) {
                    results.add(f.get(30, TimeUnit.SECONDS));
                }

                assertThat(results).filteredOn(r -> r.response().getStatusCode() == HttpStatus.OK).hasSize(1);
                assertThat(results).filteredOn(r -> r.response().getStatusCode() != HttpStatus.OK)
                        .allSatisfy(r -> assertThat(api.field(r.response(), "code")).isEqualTo("REFRESH_TOKEN_STALE"));
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        void missingOrForgedCookieIsRejected() {
            assertThat(api.field(api.refresh(null).response(), "code")).isEqualTo("REFRESH_TOKEN_INVALID");
            assertThat(api.refresh("SH_REFRESH=forged").response().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void expiredRefreshTokenIsRejected() {
            Login login = api.registerAndLogin();
            clock.advance(Duration.ofDays(7).plusSeconds(1));

            assertThat(api.field(api.refresh(login.cookie()).response(), "code")).isEqualTo("REFRESH_TOKEN_INVALID");
        }

        @Test
        void logoutRevokesSessionAndIsIdempotent() {
            Login login = api.registerAndLogin();

            ResponseEntity<String> logout = api.exchange(HttpMethod.POST, "/api/v1/auth/logout", null, null, login.cookie());
            assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(AuthTestSupport.setCookieHeader(logout)).contains("Max-Age=0");
            assertThat(api.exchange(HttpMethod.POST, "/api/v1/auth/logout", null, null, login.cookie()).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);

            assertThat(api.field(api.refresh(login.cookie()).response(), "code")).isEqualTo("REFRESH_TOKEN_INVALID");
        }
    }

    @Nested
    class Profile {

        @Test
        void requiresValidToken() {
            assertThat(api.exchange(HttpMethod.GET, "/api/v1/users/me", null, null, null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            String forged = TestJwtSupport.tokenSignedByUnknownKey(UUID.randomUUID(), "ADMIN");
            assertThat(api.exchange(HttpMethod.GET, "/api/v1/users/me", null, forged, null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void updatesOwnProfile() {
            Login login = api.registerAndLogin();
            ResponseEntity<String> res = api.exchange(HttpMethod.PUT, "/api/v1/users/me",
                    Map.of("fullName", "  New Name ", "phone", "+84 912 345 678"), login.accessToken(), null);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(api.field(res, "fullName")).isEqualTo("New Name");
            assertThat(api.field(res, "phone")).isEqualTo("+84 912 345 678");
        }

        @Test
        void changingPasswordEndsOtherSessions() {
            String email = uniqueEmail();
            api.register(email);
            Login login = api.login(email, PASSWORD);

            ResponseEntity<String> wrong = api.exchange(HttpMethod.PUT, "/api/v1/users/me/password",
                    Map.of("currentPassword", "Wrong-passw0rd", "newPassword", "N3w-password"), login.accessToken(), null);
            assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            ResponseEntity<String> ok = api.exchange(HttpMethod.PUT, "/api/v1/users/me/password",
                    Map.of("currentPassword", PASSWORD, "newPassword", "N3w-password"), login.accessToken(), null);
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            assertThat(api.refresh(login.cookie()).response().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(api.login(email, PASSWORD).response().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(api.login(email, "N3w-password").response().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    class Administration {

        String adminToken() {
            return api.login(AuthTestSupport.ADMIN_EMAIL, AuthTestSupport.ADMIN_PASSWORD).accessToken();
        }

        @Test
        void customersCannotUseAdminApi() {
            Login customer = api.registerAndLogin();
            String targetId = api.field(api.register(uniqueEmail()), "id");

            assertThat(api.exchange(HttpMethod.GET, "/api/v1/admin/users", null, customer.accessToken(), null)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            ResponseEntity<String> lock = api.exchange(HttpMethod.PATCH, "/api/v1/admin/users/" + targetId + "/status",
                    Map.of("status", "LOCKED"), customer.accessToken(), null);
            assertThat(lock.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(api.field(lock, "code")).isEqualTo("FORBIDDEN");
        }

        @Test
        void adminReadsOneUserOthersCannot() {
            String email = uniqueEmail();
            String id = api.field(api.register(email), "id");
            ResponseEntity<String> res = api.exchange(HttpMethod.GET, "/api/v1/admin/users/" + id, null, adminToken(), null);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(api.field(res, "email")).isEqualTo(email);
            assertThat(res.getBody()).doesNotContain("password");
            assertThat(api.exchange(HttpMethod.GET, "/api/v1/admin/users/" + UUID.randomUUID(), null, adminToken(), null)
                    .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(api.exchange(HttpMethod.GET, "/api/v1/admin/users/" + id, null, api.registerAndLogin().accessToken(), null)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void adminSearchIsPaginated() {
            String email = uniqueEmail();
            api.register(email);

            ResponseEntity<String> res = api.exchange(HttpMethod.GET,
                    "/api/v1/admin/users?q=" + email.substring(0, 20) + "&size=5", null, adminToken(), null);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode body = api.body(res);
            assertThat(body.get("size").asInt()).isEqualTo(5);
            assertThat(body.get("content").findValuesAsText("email")).containsExactly(email);

            assertThat(api.exchange(HttpMethod.GET, "/api/v1/admin/users?size=1000", null, adminToken(), null)
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void lockingUserBlocksLoginAndRefreshAndIsAudited() {
            String email = uniqueEmail();
            String userId = api.field(api.register(email), "id");
            Login session = api.login(email, PASSWORD);

            ResponseEntity<String> res = api.exchange(HttpMethod.PATCH, "/api/v1/admin/users/" + userId + "/status",
                    Map.of("status", "LOCKED", "reason", "fraud check"), adminToken(), null);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(api.field(res, "status")).isEqualTo("LOCKED");

            assertThat(api.refresh(session.cookie()).response().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            ResponseEntity<String> login = api.login(email, PASSWORD).response();
            assertThat(login.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(api.field(login, "code")).isEqualTo("ACCOUNT_LOCKED");

            List<AuditLog> audit = auditLogs.findByTargetIdOrderByCreatedAtAsc(userId);
            assertThat(audit).singleElement().satisfies(a -> {
                assertThat(a.getAction()).isEqualTo("USER_STATUS_CHANGED");
                assertThat(a.getDetails()).containsEntry("from", "ACTIVE").containsEntry("to", "LOCKED")
                        .containsEntry("reason", "fraud check");
            });
        }

        @Test
        void roleChangeIsAuditedAndAppearsInNextToken() {
            String email = uniqueEmail();
            String userId = api.field(api.register(email), "id");

            ResponseEntity<String> res = api.exchange(HttpMethod.PUT, "/api/v1/admin/users/" + userId + "/roles",
                    Map.of("roles", List.of("CUSTOMER", "HOTEL_MANAGER")), adminToken(), null);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

            ResponseEntity<String> me = api.exchange(HttpMethod.GET, "/api/v1/users/me", null,
                    api.login(email, PASSWORD).accessToken(), null);
            assertThat(api.body(me).get("roles").toString()).isEqualTo("[\"CUSTOMER\",\"HOTEL_MANAGER\"]");
            assertThat(auditLogs.findByTargetIdOrderByCreatedAtAsc(userId))
                    .extracting(AuditLog::getAction).containsExactly("USER_ROLES_CHANGED");
        }

        @Test
        void adminCannotModifyThemselves() {
            String token = adminToken();
            String adminId = api.field(api.exchange(HttpMethod.GET, "/api/v1/users/me", null, token, null), "id");

            ResponseEntity<String> res = api.exchange(HttpMethod.PUT, "/api/v1/admin/users/" + adminId + "/roles",
                    Map.of("roles", List.of("CUSTOMER")), token, null);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(api.field(res, "code")).isEqualTo("CANNOT_MODIFY_SELF");
        }

        @Test
        void unknownUserAndEmptyRolesAreRejected() {
            String token = adminToken();
            assertThat(api.exchange(HttpMethod.PATCH, "/api/v1/admin/users/" + UUID.randomUUID() + "/status",
                    Map.of("status", "LOCKED"), token, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(api.exchange(HttpMethod.PUT, "/api/v1/admin/users/" + UUID.randomUUID() + "/roles",
                    Map.of("roles", List.of()), token, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void jwksExposesOnlyPublicKeyMaterial() {
        ResponseEntity<String> res = http.getForEntity("/oauth2/jwks", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode key = api.body(res).get("keys").get(0);
        assertThat(key.get("kty").asText()).isEqualTo("RSA");
        assertThat(key.has("n")).isTrue();
        assertThat(key.has("d")).isFalse();
        assertThat(key.has("p")).isFalse();
    }
}
