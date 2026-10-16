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
| Correlation (planned) | `X-Correlation-Id` request/response header | allowed/exposed by gateway CORS |

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
| `/api/v1/auth/**`, `/api/v1/users/**` | auth-service (8081) |
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

No business endpoints exist yet (M0 scope).

## Planned (design §6.7, not yet implemented)
| Group | Method | Path | Auth | Milestone |
|-------|--------|------|------|-----------|
| Auth | POST | `/api/v1/auth/register` | none | M1 |
| Auth | POST | `/api/v1/auth/login` | none | M1 |
| Auth | POST | `/api/v1/auth/refresh` | refresh cookie | M1 |
| Auth | POST | `/api/v1/auth/logout` | refresh cookie | M1 |
| Users | GET/PUT | `/api/v1/users/me` | bearer | M1 |
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
- Refresh token travels in an HttpOnly cookie instead of the body (P-07, pending confirmation in M1).
- Webhook path fixed as `/api/v1/payments/webhooks/{provider}` (design: "path configured by backend").
