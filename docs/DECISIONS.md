# Architecture & Product Decisions

Format: **Issue → Options → Decision → Rationale → Impact**. Pending items need a business/owner answer before the milestone that depends on them.

Source of truth order: confirmed business requirements & security rules → running system behaviour/contract → `hotel-stayed-report.docx` (design) → new proposals.

---

## Decided

### D-001 — Greenfield project in `stay-hotel` (2026-10-16)
- **Issue:** The working folder `D:\Documents\stay-hotel` was empty. Earlier code exists elsewhere (`Fresher_CMC-Global/.../microservices_hotel_booking`, a git repo with 19 commits, plus three skeletal Angular 21 front-ends).
- **Options:** (a) clone the old backend here; (b) work in the old repo in place; (c) build from scratch here.
- **Decision:** (c), chosen explicitly by the project owner: build backend and frontend from the start in `stay-hotel`.
- **Rationale:** Owner decision.
- **Impact:** No existing API contract or data to preserve. Old repos are **not** a source of truth and were not modified. The design report is the main reference; every endpoint in `API_CONTRACT.md` is verified against this repo's code only.

### D-002 — Technology versions
- **Decision:** Java 17 (installed JDK 17.0.1), Spring Boot 3.5.7, Spring Cloud 2025.0.0 (Gateway 4.3.0, WebFlux variant), Maven 3.9.8, PostgreSQL 17, Apache Kafka 3.9.1 (KRaft, no ZooKeeper), Angular 21.2 (standalone components, Vitest runner), Node 22.12.
- **Rationale:** Matches the toolchain installed on the dev machine; Boot 3.5 is the latest 3.x line supporting Java 17; the versions were already in the local Maven cache or image store.
- **Impact:** Upgrading Java to 21 later is cheap (same Boot line) and is not planned.

### D-003 — API URL prefix `/api/v1`
- **Issue:** Design §6.6.2 allows `/api/v1/...` but its tables list paths without a prefix (`/auth/login`, `/users/me`, ...).
- **Decision:** All public endpoints live under `/api/v1`, keeping the design's resource paths (e.g. `/api/v1/users/me`, `/api/v1/bookings/me`, `/api/v1/hotels/{id}/reviews`).
- **Rationale:** Greenfield, so no clients to break; versioning lets a breaking change ship as `/v2`.
- **Impact:** The gateway routes by these prefixes (see `API_CONTRACT.md` §Routing).

### D-004 — One PostgreSQL instance, one database + login role per service
- **Decision:** `auth_db`, `hotel_db`, `booking_db`, `payment_db`, `review_db`, `notification_db`, each owned by its own role (`auth_user`, ...). No cross-database foreign keys; other services' IDs are stored as logical references.
- **Rationale:** Design §4.10/§5.8 (data ownership) at low operational cost; separate roles enforce the boundary in the database, not only in code.
- **Impact:** Each service runs its own Flyway history. Cross-service consistency is handled with APIs/events (M3–M5).

### D-005 — Uniform error body
- **Decision:** Every servlet service returns `ApiError {timestamp, status, code, message, path, fieldErrors[]}` (in `common-lib`, auto-configured). Unknown errors become a generic `500 INTERNAL_ERROR` and are logged server-side only.
- **Rationale:** Design §6.8.4; no stack traces or secrets in responses.
- **Impact:** The Angular error interceptor (M1) will map `code` and `fieldErrors`.

### D-006 — CORS only at the gateway; same-origin frontend
- **Decision:** The browser calls relative `/api/v1/...`. `ng serve` proxies `/api` to the gateway (`proxy.conf.json`); in Docker, nginx proxies `/api/` to `api-gateway:8080`. The gateway also applies an explicit CORS allow-list (`CORS_ALLOWED_ORIGINS`, no wildcard) for any cross-origin setup.
- **Rationale:** No hard-coded hosts in the bundle, and same-origin requests let the M1 refresh cookie be `SameSite=Strict`.
- **Impact:** Business services set no CORS headers.

### D-007 — Gateway CORS via `CorsWebFilter` bean instead of `globalcors` properties
- **Issue:** The first gateway test run had a CORS preflight failing with 403.
- **Finding:** The root cause was the test client (auto-configured `WebTestClient` produced a request URI that Spring rejected as "origin is malformed"). A `curl` against the real running gateway returned 200 and the right headers. The switch to `CorsWebFilter` was made before this was known.
- **Decision:** Keep the explicit `CorsWebFilter` (`api-gateway/.../CorsConfig.java`); the test now uses a real HTTP client bound to the server port.
- **Rationale:** An explicit bean is version-independent (the `globalcors` keys moved in Gateway 4.3) and easy to test.

### D-008 — npm 11 for installs
- **Issue:** The installed npm 10.9.0 crashes in `npm install` (`Cannot read properties of null (reading 'edgesOut')`, arborist `#loadPeerSet`).
- **Decision:** Install with `npx -y npm@11 install` / `npx -y npm@11 ci`. The frontend Dockerfile does the same. The global npm was not changed.
- **Impact:** npm 11 does not run the install scripts of `esbuild`, `lmdb`, `msgpackr-extract` and `@parcel/watcher`. Build and tests pass without them; revisit if a native-binary problem appears.

### D-009 — Token storage and session model (was P-07; owner approved the default on 2026-10-16)
- **Decision:** The access token is an RS256 JWT with a 15-minute lifetime, held only in SPA memory. The refresh token is opaque, has a 7-day lifetime and lives in an HttpOnly, Secure, SameSite=Strict cookie on path `/api/v1/auth`. Refresh tokens are stored server-side as SHA-256 hashes, rotate on every use and are grouped in families. Reusing a rotated token revokes the whole family.
- **Refinement:** A refresh within 30 s of a rotation returns `401 REFRESH_TOKEN_STALE` without revoking anything; the SPA retries once. Without this, two tabs (or a reload during a refresh) would look like token theft and log the user out everywhere.
- **Trade-off:** JWTs are stateless, so after an admin lock or a password change an already-issued access token stays valid for at most 15 minutes. Refresh tokens are revoked immediately.

### D-010 — Every service validates the JWT; the gateway does not
- **Decision:** Each business service is an OAuth2 resource server. It validates the signature against JWKS from `auth-service:8081/oauth2/jwks` (internal network only), plus `iss`, `aud`, `exp`; roles come from the `roles` claim. The gateway only routes and applies CORS.
- **Rationale:** Defense in depth (design §5.7): a service reached without the gateway is still protected, and services never trust identity headers. The identity used for ownership checks is always the token `sub` (`CurrentUser.from(jwt)`), never a request field.
- **Impact:** Unauthenticated requests to any non-public path get `401` from the service itself; default is deny.

### D-011 — Brute-force protection by account, with generic errors
- **Decision:** After 5 consecutive wrong passwords, the account is locked out for 15 minutes. During the lockout even a correct password returns the generic `401 INVALID_CREDENTIALS`. Unknown emails go through a dummy BCrypt comparison so that timing does not reveal which accounts exist. `403 ACCOUNT_LOCKED` (admin lock) is returned only after a correct password.
- **Known limitation:** Anyone who knows an email can deliberately trigger the 15-minute lockout (nuisance-level DoS). Per-IP rate limiting at the edge is planned for M7.

### D-012 — Admin safety rules
- **Decision:** Admins cannot change their own status or roles (`409 CANNOT_MODIFY_SELF`), which also guarantees the last admin can never be demoted. Every status or role change writes an `audit_logs` row (BR-32). The first admin comes only from `ADMIN_EMAIL`/`ADMIN_PASSWORD` at start-up; there is no default credential.

### D-013 — Hotel managers are assigned per hotel (was P-08; owner approved the default on 2026-10-17)
- **Decision:** A `hotel_managers(hotel_id, user_id, assigned_by, assigned_at)` table in hotel_db. A HOTEL_MANAGER can manage a hotel only if the token carries the role **and** an assignment row exists. ADMIN manages every hotel. A manager who creates a hotel is assigned to it automatically; ADMIN assigns or unassigns other managers.
- **Rationale:** BR-30 and anti-IDOR at the resource level. hotel-service does not call auth-service to check the user: an assignment gives no power until the user also has the role. This avoids a synchronous dependency between the services.
- **Impact:** An unknown hotel → 404; a hotel that is not yours → 403 (`HotelAccessPolicy`).

### D-014 — Business time zone (was P-09; owner approved the default on 2026-10-17)
- **Decision:** Every hotel stores an IANA `time_zone` (default `Asia/Ho_Chi_Minh`), plus `check_in_time`/`check_out_time` as local `TIME`. Stay dates are hotel-local `DATE`s; instants are UTC `TIMESTAMPTZ`.
- **Search nuance:** Search spans many hotels, so `checkIn` is accepted down to "yesterday in UTC". That way a hotel's local "today" is never rejected. Booking (M3) will validate against the specific hotel's zone.

### D-015 — Inventory unit on the hotel side is the room type
- **Decision:** hotel-service models `room_types.total_units` and does **not** model physical rooms (design §4.4.4 is optional). This matches the P-01 default (count-based inventory); the booking-side inventory table is decided in M3.
- **Rationale:** The design warns against two independent sources of counts. One number per room type is the single source.

### D-016 — Search implemented in SQL with normalized keys
- **Decision:** The public search is one `NamedParameterJdbcTemplate` query (+ count + one batched amenity query). Dynamic parts are fixed SQL fragments and every value is a bound parameter. City and name are matched through `city_key`/`name_key` columns that the application fills with lower-case, diacritics-free text, so "da nang" matches "Đà Nẵng" and a partial index serves `city_key` for ACTIVE hotels.
- **Alternatives rejected:** JPA Criteria (awkward aggregate + `HAVING` for "all amenities"); the PostgreSQL `unaccent()` extension (not IMMUTABLE, so it cannot be indexed directly).
- **Limit:** `q` (free text) uses `LIKE '%…%'`, which has no index. Add `pg_trgm` in M7 if measurements show it is needed.

### D-017 — Hotel management UI deferred
- **Decision:** In M2 the Angular app covers the **public** flows (home search, results, detail). Hotel/room management for managers and admins is available through the API (verified by tests and E2E) and gets its UI in M5/M6 together with the other admin screens.

### D-018 — Inventory per room type per night (was P-01; owner approved on 2026-10-17)
- **Decision:** `room_type_inventory(room_type_id, stay_date, reserved_units)` in booking_db. To reserve, booking-service runs, inside the booking transaction:
  1. `INSERT … ON CONFLICT DO NOTHING` creates missing night rows.
  2. **One** `UPDATE … SET reserved_units = reserved_units + :qty WHERE … AND reserved_units + :qty <= :totalUnits`; success only if every night was updated.
- **Where capacity comes from:** `totalUnits` is the room type's current value from hotel-service, so there is one source of truth (D-015).
- **Why it is safe:** PostgreSQL row locks + re-evaluation of the WHERE clause on the latest committed row. Two bookings can never both take the last unit, and no read-then-write race is possible. Room types are reserved in id order, so concurrent multi-line bookings lock in the same order (no deadlock). Deadlocks or lock timeouts still map to 409 (`ConcurrencyFailureException`).
- **Evidence:** `BookingConcurrencyTest` (12 parallel requests for 3 units, ×3 runs; overlapping stays; same key; cancel vs. expire) and the stack E2E (10 customers, 1 unit → 1 created, 9 × 409).
- **Trade-off:** If a manager lowers `totalUnits` below what is already reserved, existing bookings are kept and new ones are refused until reservations drop.

### D-019 — Hold time and lifecycle job (was P-02)
- **Decision:** A hold lasts 15 minutes (`BOOKING_HOLD_DURATION`, default `15m`).
  - `BookingLifecycleJob` runs every 30 s: overdue holds → `EXPIRED`, units released.
  - Every 15 min: `CONFIRMED` bookings whose check-out date has passed in the hotel time zone → `COMPLETED`.
  - Both queries use `FOR UPDATE SKIP LOCKED`, so several instances can run the job, and a booking being cancelled at that moment is skipped rather than double-processed.
- **Payment arriving after the deadline:** `confirmPaid` re-checks `expiresAt`. An overdue hold is expired (and that change is committed) and the confirmation is refused; P-06 decides what happens to the money in M4.

### D-020 — Pricing (was P-03): VAT 8 %, service fee 5 %, per hotel
- **Decision:** `hotels.tax_rate_percent` (default 8) and `service_fee_percent` (default 5), each 0–30, editable per hotel.
  - `fee = round(subtotal × fee%)`, then `VAT = round((subtotal + fee) × VAT%)`. VAT is charged on the service fee too, as is usual for Vietnamese hotel invoices.
  - VND, HALF_UP, whole đồng.
  - The booking stores every component, both rates, the room type names and the unit prices (BR-14). A later price change never alters an existing booking (tested).
- **Not in scope:** seasonal or nightly rates (a `booking_room_nights` table would be needed) and discounts (`discount_amount` is always 0 for now).

### D-021 — Cancellation policy (was P-04)
- **Decision:**
  - `PENDING_PAYMENT`: can always be cancelled; `refundEligible = null` because nothing was paid.
  - `CONFIRMED`: can be cancelled until the hotel-local check-in moment (`check_in` + hotel `check_in_time` in the hotel time zone). `refundEligible = true` if ≥ 48 h before that moment, else `false`. After the check-in moment → `409 CANCELLATION_NOT_ALLOWED`.
  - Cancelling never means "refunded" (BR-26): the refund is a separate payment-service record (M4).

### D-022 — Booking scope choices
- **Idempotency:** `Idempotency-Key` is mandatory for `POST /bookings`. It is scoped per user, and the request body is stored as a SHA-256 hash. If two concurrent requests use the same key, the unique constraint decides the winner and the other request replays the result.
- **No existence leak:** another user's booking answers 404, not 403.
- **Not implemented:** individual guest names (`booking_guests`) are not collected; the booking keeps adult/child counts and one contact person. Hotel managers do not yet see the bookings of their hotels (UC-11) — that view comes in M5.
- **Catalogue call:** booking-service calls hotel-service's **public** hotel detail with 2 s connect / 3 s read timeouts, outside any DB transaction. A failure → `503 HOTEL_SERVICE_UNAVAILABLE` and nothing is reserved. No retry and no circuit breaker yet (M7).

---

## Pending — need an owner decision (blocking the milestone shown)

| ID | Question | Recommended default | Blocks |
|----|----------|---------------------|--------|
| P-05 | Payment gateway | No merchant account is available, so build a **sandbox provider** inside payment-service with HMAC-SHA256 signed webhooks (same shape as VNPay/Stripe). A real gateway needs credentials from the owner. | M4 |
| P-06 | Late payment (succeeds after the booking EXPIRED) | Never auto-confirm: if inventory is still free, re-reserve and confirm; otherwise refund automatically and flag it for review | M4 |
