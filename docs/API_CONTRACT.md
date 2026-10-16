# API Contract

Only endpoints **verified in this repository's code and tests** are listed as Implemented. Design-reference endpoints (report §6.7) appear under *Planned* until they are built and tested.

Base URL (browser): relative `/api/v1` (same origin; proxied to the gateway). Gateway: `http://localhost:8080`.

## Conventions (in force)
| Topic | Rule | Evidence |
|-------|------|----------|
| Prefix | `/api/v1/...` (D-003) | `api-gateway/src/main/resources/application.yml` |
| Content type | `application/json; charset=UTF-8` | Spring default |
| Error body | see below (D-005) | `common-lib/.../ApiError.java`, `GlobalExceptionHandlerTest` |
| Pagination (planned) | `?page=0&size=20&sort=field,asc` → `{content, page, size, totalElements, totalPages}` | design §6.8.3, to implement in M2 |
| Idempotency (planned) | `Idempotency-Key` request header on POST that creates bookings/payments | header already allowed by gateway CORS |
| Correlation | `X-Correlation-Id`: the gateway keeps a valid client value (`[A-Za-z0-9-]{8,64}`) or generates a UUID, forwards it and returns it; services put it in the log MDC | `CorrelationIdFilter` (gateway and common-lib), `GatewayRoutingTest` |
| Authentication | `Authorization: Bearer <JWT>` validated by **each** service (D-010). Missing, invalid, expired, foreign-signed or wrong-audience token → `401 UNAUTHORIZED`; wrong role → `403 FORBIDDEN` | `*ServiceSmokeTest`, `AuthFlowIntegrationTest` |

### Error body
```json
{
  "timestamp": "2026-10-16T03:37:09.280Z",
  "status": 400,
  "code": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "path": "/api/v1/...",
  "fieldErrors": [{ "field": "email", "message": "must be a well-formed email address" }]
}
```
`fieldErrors` is omitted when empty. Codes defined so far (`ErrorCode`): `VALIDATION_FAILED` (400), `MALFORMED_REQUEST` (400), `UNAUTHORIZED` (401), `FORBIDDEN` (403), `NOT_FOUND` (404), `METHOD_NOT_ALLOWED` (405), `CONFLICT` (409), `UNSUPPORTED_MEDIA_TYPE` (415), `INTERNAL_ERROR` (500). Services add domain codes via `BusinessException(status, code, message)`.

## Gateway routing (implemented, verified)
Evidence: `GatewayRoutingTest` (13 cases PASS); runtime curl smoke recorded in `PROJECT_AUDIT.md`.

| Path pattern | Service (internal port) |
|--------------|-------------------------|
| `/api/v1/hotels/{id}/reviews[/**]` | review-service (8085), matched before the hotel route |
| `/api/v1/auth/**`, `/api/v1/users/**`, `/api/v1/admin/users[/**]` | auth-service (8081) |
| `/api/v1/hotels[/**]`, `/api/v1/room-types/**`, `/api/v1/amenities[/**]` | hotel-service (8082) |
| `/api/v1/bookings[/**]` | booking-service (8083) |
| `/api/v1/payments[/**]` | payment-service (8084) |
| `/api/v1/reviews[/**]` | review-service (8085) |
| `/api/v1/notifications[/**]` | notification-service (8086) |
| anything else | 404 at the gateway |

CORS: allow-list from `CORS_ALLOWED_ORIGINS`; methods GET, POST, PUT, PATCH, DELETE, OPTIONS; headers `Authorization`, `Content-Type`, `Idempotency-Key`, `X-Correlation-Id`; credentials allowed; other origins get 403 on preflight.

## Implemented endpoints
| Method | Path | Auth | Response | Evidence |
|--------|------|------|----------|----------|
| GET | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` (each service, **not** routed through the gateway) | none | `{"status":"UP"}` (details hidden) | `*ServiceSmokeTest`, Docker healthchecks |

Auth and user endpoints: see the M1 section below.

## M1 — Auth & users — **Implemented & verified**
Evidence: `AuthFlowIntegrationTest` (24 cases), `AuthServiceSmokeTest`, `*ServiceSmokeTest` (401 without a token or with an invalid one), `GatewayRoutingTest` (admin route), and the stack E2E script (22/22 PASS, see `PROJECT_AUDIT.md` §6).

Tokens (P-07 decided):
- **Access token:** JWT signed with RS256, 15-minute lifetime, kept in memory by the SPA and sent as `Authorization: Bearer`. Claims: `iss=https://auth.stayhotel.local`, `aud=stay-hotel-api`, `sub=<userId>`, `email`, `roles` (for example `["CUSTOMER"]`), `iat`, `exp`, `jti`.
- **Refresh token:** opaque random value, 7-day lifetime, delivered only as the cookie `SH_REFRESH` (HttpOnly; Secure (configurable for http dev); SameSite=Strict; Path=`/api/v1/auth`). It is stored server-side as a SHA-256 hash, rotated on every use, and grouped in a family. Reuse of an already rotated token revokes the whole family.
- Every service validates the JWT itself through JWKS. The gateway does not validate tokens.

`AuthResponse` = `{ "accessToken": "...", "tokenType": "Bearer", "expiresIn": 900, "user": UserResponse }`
`UserResponse` = `{ "id": "uuid", "email": "...", "fullName": "...", "phone": "..."|null, "status": "ACTIVE|LOCKED|DISABLED", "roles": ["CUSTOMER"], "createdAt": "ISO-8601" }`

| Method | Path | Auth | Request | Success | Errors |
|--------|------|------|---------|---------|--------|
| POST | `/api/v1/auth/register` | none | `{email, password, fullName, phone?}` | 201 `UserResponse`; role is always `CUSTOMER` | 400 `VALIDATION_FAILED`; 409 `EMAIL_ALREADY_REGISTERED` |
| POST | `/api/v1/auth/login` | none | `{email, password}` | 200 `AuthResponse` + `Set-Cookie: SH_REFRESH` | 400; 401 `INVALID_CREDENTIALS` (same response for unknown email, wrong password or temporary lockout after 5 failures for 15 min); 403 `ACCOUNT_LOCKED` (admin-locked or disabled, only after a correct password) |
| POST | `/api/v1/auth/refresh` | `SH_REFRESH` cookie | — | 200 `AuthResponse` + rotated cookie | 401 `REFRESH_TOKEN_INVALID` (missing, unknown or expired; cookie cleared); 401 `REFRESH_TOKEN_REUSED` (family revoked; cookie cleared); 401 `REFRESH_TOKEN_STALE` (rotated less than 30 s ago, typically a parallel tab; client retries once with the new cookie); 403 `ACCOUNT_LOCKED` |
| POST | `/api/v1/auth/logout` | cookie (optional) | — | 204, cookie cleared, token revoked | — (idempotent) |
| GET | `/api/v1/users/me` | bearer | — | 200 `UserResponse` | 401 `UNAUTHORIZED` |
| PUT | `/api/v1/users/me` | bearer | `{fullName, phone?}` | 200 `UserResponse` | 400, 401 |
| PUT | `/api/v1/users/me/password` | bearer | `{currentPassword, newPassword}` | 204; all refresh tokens revoked | 400; 401; 422 `CURRENT_PASSWORD_INCORRECT` |
| GET | `/api/v1/admin/users?q&status&page&size` | ADMIN | — | 200 `Page<UserResponse>` | 401, 403 `FORBIDDEN` |
| PATCH | `/api/v1/admin/users/{id}/status` | ADMIN | `{status, reason?}` | 200 `UserResponse`; non-ACTIVE revokes refresh tokens; audited | 404 `USER_NOT_FOUND`; 409 `CANNOT_MODIFY_SELF` |
| PUT | `/api/v1/admin/users/{id}/roles` | ADMIN | `{roles: [..] (non-empty)}` | 200 `UserResponse`; audited | 404; 409 `CANNOT_MODIFY_SELF` |
| GET | `/oauth2/jwks` (internal only, **not routed** by the gateway) | none | — | 200 JWK set (public keys) | — |

Validation: email in RFC format, ≤ 255 characters, normalized with trim and lower-case. Password 8–72 characters with at least one letter and one digit. `fullName` 1–150 characters. `phone` optional, ≤ 30 characters, pattern `[0-9+() .-]`.
Password hashing: BCrypt (strength 12). A dummy hash comparison runs for unknown emails so response timing does not reveal which accounts exist.
`Page<T>` = `{content, page, size, totalElements, totalPages}`.

## Planned (design §6.7, not yet implemented)
| Group | Method | Path | Auth | Milestone |
|-------|--------|------|------|-----------|
| Hotels | GET | `/api/v1/hotels` (search, paginated) | none | M2 |
| Hotels | GET | `/api/v1/hotels/{hotelId}` | none | M2 |
| Hotels | GET | `/api/v1/hotels/{hotelId}/room-types` | none | M2 |
| Hotels | GET | `/api/v1/hotels/{hotelId}/availability` | none | M2/M3 |
| Hotels | POST/PUT | `/api/v1/hotels[/{hotelId}]` | ADMIN / assigned MANAGER | M2 |
| Bookings | POST | `/api/v1/bookings` | CUSTOMER + Idempotency-Key | M3 |
| Bookings | GET | `/api/v1/bookings/me` | bearer | M3 |
| Bookings | GET | `/api/v1/bookings/{bookingId}` | owner / manager / admin | M3 |
| Bookings | POST | `/api/v1/bookings/{bookingId}/cancel` | owner | M3 |
| Payments | POST | `/api/v1/payments` | owner of booking | M4 |
| Payments | GET | `/api/v1/payments/{paymentId}`, `/api/v1/payments/booking/{bookingId}` | owner / admin | M4 |
| Payments | POST | `/api/v1/payments/webhooks/{provider}` | provider signature | M4 |
| Reviews | GET | `/api/v1/hotels/{hotelId}/reviews` | none | M5 |
| Reviews | POST/PUT/DELETE | `/api/v1/reviews[/{reviewId}]` | owner | M5 |
| Notifications | GET | `/api/v1/notifications/me` | bearer | M5 |

### Differences from the design
- `/api/v1` prefix added (D-003).
- Refresh token travels in an HttpOnly cookie instead of the body (D-009).
- Password change (`PUT /users/me/password`) and admin user management (`/admin/users`) are additions not listed in design §6.7.1 (they cover FR-AUTH-05 and FR-ADMIN-01).
- Webhook path fixed as `/api/v1/payments/webhooks/{provider}` (design: "path configured by backend").
