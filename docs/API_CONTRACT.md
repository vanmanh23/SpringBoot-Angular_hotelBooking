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
| `/api/v1/hotels[/**]`, `/api/v1/room-types/**`, `/api/v1/amenities[/**]`, `/api/v1/manage/hotels[/**]` | hotel-service (8082) |
| `/api/v1/bookings[/**]`, `/api/v1/availability` | booking-service (8083) |
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

## M2 — Hotels, room types, amenities, search — **Implemented & verified**
Evidence: `HotelManagementIntegrationTest` (19), `HotelSearchIntegrationTest` (22), `DomainRulesTest` (16), `GatewayRoutingTest`, stack E2E 19/19 (`PROJECT_AUDIT.md` §7).

Hotel status machine:
- `DRAFT` → `ACTIVE`: ADMIN only (approval). Sets `approvedAt`.
- `ACTIVE` → `INACTIVE`: assigned MANAGER or ADMIN (hide).
- `INACTIVE` → `ACTIVE`: ADMIN, or an assigned MANAGER if the hotel was approved before.
- any → `SUSPENDED`: ADMIN only.
- `SUSPENDED` → `ACTIVE` / `INACTIVE`: ADMIN only.
- Any other transition → `409 INVALID_STATUS_TRANSITION`.
- Only `ACTIVE` hotels are public (BR-06). For non-public hotels the public endpoints answer 404.

Management rights (P-08 → D-013):
- ADMIN: every hotel.
- HOTEL_MANAGER: only hotels listed for them in `hotel_managers`. Another hotel → `403 FORBIDDEN`.
- A manager who creates a hotel is assigned to it automatically. ADMIN assigns or unassigns managers.
- Assigning a user grants power only while their token also carries the `HOTEL_MANAGER` role.

Money: `VND`, whole numbers (P-03 default, enforced by DB CHECK). Prices in JSON are numbers.
Times: `checkInTime`/`checkOutTime` are `HH:mm` local to the hotel; `timeZone` is an IANA zone, default `Asia/Ho_Chi_Minh` (P-09 → D-014).

`HotelSummary` = `{id, name, city, address, countryCode, starRating|null, avgRating|null, reviewCount, thumbnailUrl|null, fromPrice, currency, amenities: [code]}`
`HotelDetail` = `{id, name, description|null, address, city, countryCode, starRating|null, timeZone, checkInTime, checkOutTime, cancellationPolicy|null, avgRating|null, reviewCount, images: [{id, url, caption|null}], amenities: [{code, name}], roomTypes: [RoomType]}`
`RoomType` = `{id, hotelId, name, description|null, capacity, bedDescription|null, basePrice, currency, totalUnits, status, amenities: [{code, name}], version}`
`ManagedHotel` = `HotelDetail` + `{status, approvedAt|null, managerIds: [uuid], version}`; room types of every status are included.

### Public
| Method | Path | Query / body | Success | Errors |
|--------|------|--------------|---------|--------|
| GET | `/api/v1/hotels` | `city?`, `q?` (name/city text), `checkIn?`, `checkOut?` (ISO dates, both or neither), `guests` (1–40, default 1), `rooms` (1–10, default 1), `minPrice?`, `maxPrice?`, `stars?` (CSV 1–5), `amenities?` (CSV codes, all must match), `sort` = `recommended`\|`price_asc`\|`price_desc`\|`rating_desc`\|`name_asc`, `page` (≥ 0), `size` (1–50, default 20) | 200 `Page<HotelSummary>` | 400 `VALIDATION_FAILED` (checkOut ≤ checkIn, checkIn more than 1 day in the past, stay > 30 nights, minPrice > maxPrice, unknown sort, out-of-range values) |
| GET | `/api/v1/hotels/{id}` | — | 200 `HotelDetail` (active room types only) | 404 `HOTEL_NOT_FOUND` |
| GET | `/api/v1/hotels/{id}/room-types` | `guests?`, `rooms?` | 200 `[RoomType]`, active types where `capacity × rooms ≥ guests` and `totalUnits ≥ rooms` | 404 |
| GET | `/api/v1/amenities` | `category?` = `HOTEL`\|`ROOM` | 200 `[{id, code, name, category}]` | — |

Text matching ignores case and Vietnamese diacritics ("da nang" matches "Đà Nẵng").
**Search results are advisory (BR-08):** they filter by capacity and number of units, but bookings are not subtracted until M3 adds availability. Booking re-checks everything (BR-09).

### Management (bearer token)
| Method | Path | Who | Body | Success | Errors |
|--------|------|-----|------|---------|--------|
| GET | `/api/v1/manage/hotels?status&q&page&size` | ADMIN (all) / MANAGER (own) | — | 200 `Page<{id, name, city, countryCode, starRating, status, approvedAt, version}>` | 401, 403 |
| GET | `/api/v1/manage/hotels/{id}` | ADMIN / assigned MANAGER | — | 200 `ManagedHotel` | 403, 404 |
| POST | `/api/v1/hotels` | ADMIN / MANAGER | `HotelRequest` | 201 `ManagedHotel` (status `DRAFT`) | 400, 403 |
| PUT | `/api/v1/hotels/{id}` | ADMIN / assigned MANAGER | `HotelRequest` + `version` | 200 `ManagedHotel` | 400, 403, 404, 409 `CONCURRENT_MODIFICATION` |
| PATCH | `/api/v1/hotels/{id}/status` | per status machine | `{status}` | 200 `ManagedHotel` | 403, 409 `INVALID_STATUS_TRANSITION` |
| PUT | `/api/v1/hotels/{id}/amenities` | ADMIN / assigned MANAGER | `{codes: [..]}` (HOTEL category) | 200 `ManagedHotel` | 400 `UNKNOWN_AMENITY` |
| POST | `/api/v1/hotels/{id}/images` | ADMIN / assigned MANAGER | `{url (https, ≤ 1000), caption?}` | 201 `{id, url, caption}` | 400; 409 `IMAGE_LIMIT_REACHED` (max 20) |
| DELETE | `/api/v1/hotels/{id}/images/{imageId}` | ADMIN / assigned MANAGER | — | 204 | 404 |
| POST | `/api/v1/hotels/{id}/room-types` | ADMIN / assigned MANAGER | `RoomTypeRequest` | 201 `RoomType` (ACTIVE) | 400; 409 `ROOM_TYPE_NAME_TAKEN` |
| PUT | `/api/v1/room-types/{id}` | ADMIN / assigned MANAGER | `RoomTypeRequest` + `version` | 200 `RoomType` | 400, 403, 404, 409 |
| PATCH | `/api/v1/room-types/{id}/status` | ADMIN / assigned MANAGER | `{status: ACTIVE\|INACTIVE}` | 200 `RoomType` | 403, 404 |
| PUT | `/api/v1/room-types/{id}/amenities` | ADMIN / assigned MANAGER | `{codes: [..]}` (ROOM category) | 200 `RoomType` | 400 |
| GET / POST | `/api/v1/hotels/{id}/managers` | ADMIN | POST `{userId}` | 200 `[uuid]` / 201 `[uuid]` | 404 |
| DELETE | `/api/v1/hotels/{id}/managers/{userId}` | ADMIN | — | 204 | 404 |
| POST | `/api/v1/amenities` | ADMIN | `{code ([A-Z][A-Z0-9_]{1,39}), name, category}` | 201 | 409 `AMENITY_CODE_TAKEN` |

`HotelRequest` = `{name (1–200), description? (≤ 5000), address (1–500), city (1–100), countryCode ([A-Z]{2}), starRating? (1–5), timeZone? (IANA, default Asia/Ho_Chi_Minh), checkInTime (HH:mm, default 14:00), checkOutTime (HH:mm, default 12:00), cancellationPolicy? (≤ 2000)}`
`RoomTypeRequest` = `{name (1–150), description? (≤ 2000), capacity (1–20), bedDescription? (≤ 200), basePrice (1 000–1 000 000 000 VND, whole number), totalUnits (0–1000)}`

## M3 — Booking core — **Implemented & verified**
Evidence: `BookingFlowIntegrationTest` (18), `BookingConcurrencyTest` (6), `PriceCalculatorTest` (4), `HttpHotelCatalogTest` (3), frontend checkout/booking specs, stack E2E 16/16 (`PROJECT_AUDIT.md` §8).

Owner decisions (P-01..P-04, see DECISIONS D-018..D-021):
- **Inventory:** counted per room type per night in booking_db. A booking reserves `quantity` units on every night `[checkIn, checkOut)` in **one conditional UPDATE**, executed against the room type's current `totalUnits` read from hotel-service. If any night lacks capacity, nothing is reserved and the caller gets `409 ROOM_SOLD_OUT`. Holds (`PENDING_PAYMENT`) and `CONFIRMED` bookings both count (BR-10).
- **Hold:** 15 minutes (`expiresAt`). A scheduled job moves overdue holds to `EXPIRED` and releases the units (BR-19/20).
- **Price (BR-12..15):** computed by the backend only; the request carries no amounts.
  - `lineSubtotal = basePrice × quantity × nights`
  - `subtotal = Σ lineSubtotal`
  - `fee = round(subtotal × serviceFeePercent / 100)`
  - `tax = round((subtotal + fee) × taxRatePercent / 100)`
  - `discount = 0`
  - `total = subtotal + fee + tax − discount`
  - VND, rounded HALF_UP to whole đồng. Hotel defaults: VAT 8 %, service fee 5 %, editable per hotel.
  - Every component, both rates, the room type names and the unit prices are snapshotted on the booking (BR-14).
- **Cancellation (P-04):** `PENDING_PAYMENT` can always be cancelled. `CONFIRMED` can be cancelled until the hotel-local check-in time. It is **refund-eligible** only if the cancellation happens ≥ 48 h before that moment. The refund itself is handled by payment-service in M4 (BR-26). Cancelling releases inventory.
- **Booking state machine (BR-18):**
  - `PENDING_PAYMENT` → `CONFIRMED` (payment, M4) | `CANCELLED` | `EXPIRED`
  - `CONFIRMED` → `CANCELLED` | `COMPLETED` (job, after check-out date in the hotel time zone)
  - Any other transition → `409 INVALID_STATUS_TRANSITION`.
  - Every transition is written to `booking_status_history`.
  - Concurrent transitions on the same booking are serialized with a row lock, so only one wins.

Stay rules (BR-01..05):
- `checkOutDate > checkInDate`; 1–30 nights.
- `checkInDate` ≥ today **in the hotel time zone** and ≤ 365 days ahead.
- 1–5 room lines with distinct room types, 1–10 units per line, ≤ 10 rooms in total.
- `adults` ≥ number of rooms; `children` ≥ 0.
- `adults + children` ≤ Σ `capacity × quantity`.
- Hotel and room types must be ACTIVE and the room types must belong to the hotel.

`BookingRequest` = `{hotelId, checkInDate, checkOutDate, adults, children, rooms: [{roomTypeId, quantity}], contactName (1–150), contactEmail, contactPhone? (≤ 30), specialRequests? (≤ 1000)}`
`Quote` = `{hotelId, hotelName, checkInDate, checkOutDate, nights, currency, lines: [{roomTypeId, roomTypeName, quantity, unitPrice, nights, lineSubtotal}], subtotal, serviceFeePercent, feeAmount, taxRatePercent, taxAmount, discountAmount, totalAmount, available}`
`Booking` = `{id, bookingCode, status, hotelId, hotelName, hotelTimeZone, checkInDate, checkOutDate, checkInTime, nights, adults, children, lines[], subtotal, serviceFeePercent, feeAmount, taxRatePercent, taxAmount, discountAmount, totalAmount, currency, contactName, contactEmail, contactPhone, specialRequests, expiresAt|null, confirmedAt|null, cancelledAt|null, cancelReason|null, refundEligible|null, freeCancellationUntil, createdAt, history: [{fromStatus|null, toStatus, reason, actor, at}]}`

| Method | Path | Auth | Request | Success | Errors |
|--------|------|------|---------|---------|--------|
| GET | `/api/v1/availability?hotelId&checkIn&checkOut` | none | — | 200 `{hotelId, checkIn, checkOut, roomTypes: [{roomTypeId, totalUnits, availableUnits}]}`; advisory, not a hold | 400; 404 `HOTEL_NOT_AVAILABLE` |
| POST | `/api/v1/bookings/quote` | CUSTOMER | `BookingRequest` | 200 `Quote` (no reservation) | 400; 422 `HOTEL_NOT_AVAILABLE` / `ROOM_TYPE_NOT_AVAILABLE` / `CAPACITY_EXCEEDED`; 503 `HOTEL_SERVICE_UNAVAILABLE` |
| POST | `/api/v1/bookings` | CUSTOMER + header `Idempotency-Key` (8–100 chars `[A-Za-z0-9_-]`) | `BookingRequest` | 201 `Booking` (`PENDING_PAYMENT`, units reserved). The same key with the same body replays the original booking (200, header `Idempotent-Replayed: true`). | 400 (missing key → `fieldErrors[idempotencyKey]`); 409 `ROOM_SOLD_OUT`; 422 `IDEMPOTENCY_KEY_REUSED` (same key, different body) and the 422s above; 503 |
| GET | `/api/v1/bookings/me?status&page&size` | bearer | — | 200 `Page<BookingSummary>` (`{id, bookingCode, status, hotelId, hotelName, checkInDate, checkOutDate, nights, totalAmount, currency, expiresAt, createdAt}`), newest first | 401 |
| GET | `/api/v1/bookings/{id}` | owner or ADMIN | — | 200 `Booking` | 404 `BOOKING_NOT_FOUND` (also for other users' bookings: no existence leak) |
| GET | `/api/v1/bookings/{id}/status` | owner or ADMIN | — | 200 `{id, status, expiresAt}` | 404 |
| POST | `/api/v1/bookings/{id}/cancel` | owner or ADMIN | `{reason? (≤ 300)}` | 200 `Booking` (`CANCELLED`, `refundEligible` set) | 404; 409 `INVALID_STATUS_TRANSITION` / `CANCELLATION_NOT_ALLOWED` (check-in time passed) |

Hotel-service change: `taxRatePercent` and `serviceFeePercent` (0–30, two decimals; defaults 8 and 5) are added to `HotelRequest` (optional), `HotelDetail` and `ManagedHotel`.
Gateway change: `/api/v1/availability` → booking-service.

## Planned (design §6.7, not yet implemented)
| Group | Method | Path | Auth | Milestone |
|-------|--------|------|------|-----------|
| Bookings | GET | `/api/v1/manage/hotels/{hotelId}/bookings` (manager view, UC-11) | assigned MANAGER / ADMIN | M5 |
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
- Management reads live under `/api/v1/manage/hotels` so that public and management views never share a response shape.
- Availability lives at `/api/v1/availability?hotelId=…` (booking-service owns the data) instead of `/hotels/{id}/availability`.
- Booking request uses `checkInDate`/`checkOutDate` (design §6.8.1); search keeps `checkIn`/`checkOut`.
- Webhook path fixed as `/api/v1/payments/webhooks/{provider}` (design: "path configured by backend").
