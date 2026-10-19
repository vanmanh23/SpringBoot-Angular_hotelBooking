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

### D-023 — Sandbox payment provider (was P-05; owner approved on 2026-10-18)
- **Decision:** No merchant account exists, so payment-service contains an isolated **sandbox gateway** module (`sandbox_*` tables, `/sandbox-gateway/**`). It behaves like a hosted checkout: the browser is redirected to its page (plain HTML forms, no card data), and the outcome is reported **only** by a webhook signed with `v1=HMAC-SHA256(secret, timestamp.body)`. Undelivered webhooks are redelivered, and refunds are idempotent on our refund id.
- **Why not a mock inside the domain code:** the merchant side (signature check, replay window, dedup, amount check, state machine, outbox) is exactly what a real provider integration needs. Swapping in VNPay/Stripe means a new `PaymentProvider` implementation plus a webhook adapter; the domain stays untouched.
- **Secrets:** `SANDBOX_WEBHOOK_SECRET` (≥ 32 chars) from the environment only. It is required: the service does not start without it.

### D-024 — Late payment policy (was P-06)
- **Decision:** payment-service never decides about bookings. booking-service applies `PaymentSucceeded` under a row lock:
  - Hold still valid → `CONFIRMED`.
  - Hold overdue or `EXPIRED`, and check-in still ahead → re-reserve every night **all-or-nothing** (rows locked `FOR UPDATE` in a fixed order, checked, then incremented). Success → `EXPIRED → CONFIRMED` (a new state-machine edge, used only here). Otherwise → `BookingPaymentRejected(NO_AVAILABILITY)`.
  - `CANCELLED` → reject `BOOKING_CANCELLED`; already confirmed by another payment → `ALREADY_PAID`; stay started → `STAY_STARTED`; wrong amount → `AMOUNT_MISMATCH`.
  - A rejection → payment-service refunds automatically and sets `needsReview`; the booking shows `refundStatus`.
- **Why no savepoint:** the first version used `PROPAGATION_NESTED`, which did not work with the JPA transaction manager (the late payment was wrongly rejected; caught by a test). The locked all-or-nothing method needs no savepoint.
- **Evidence:** `PaymentOutcomeIntegrationTest` (late accepted; late rejected with a partially free stay → nothing reserved), stack E2E (both cases live).

### D-025 — Events: transactional outbox, idempotent consumers, DLT
- **Outbox:** events are inserted into `outbox_events` in the same DB transaction as the state change; a relay publishes them every second (`FOR UPDATE SKIP LOCKED`, ordered by an identity `seq`). The order must be deterministic because one transaction can emit two events (`BookingExpired` then `BookingConfirmed`); `created_at` ties made the order random (caught by a test).
- **Delivery:** at-least-once. Every consumer inserts `(eventId, consumer)` into `processed_messages` in its handling transaction, so duplicates and retries are no-ops.
- **Errors:** 3 attempts with exponential backoff, then `<topic>.DLT`. Unreadable messages go to the DLT immediately. The DLT destination is set explicitly because Spring's default suffix `-dlt` did not match our topics (caught by a test).
- **Provisioning:** services create `payment-events`, `booking-events` and both `.DLT` topics at start-up (3 partitions); production brokers have auto-creation off. Verified on the stack broker.
- **Keys:** booking id, so all events of one booking keep their order within a partition.
- **Not done:** schema registry and versioned payload contracts beyond `eventVersion: 1`; consumer-lag monitoring (M8).

### D-026 — Payment amount comes from booking-service via the caller's token
- **Decision:** `POST /payments` carries only `bookingId`. payment-service reads the booking from booking-service **forwarding the caller's JWT**, so booking-service's ownership rule (404 for others) also protects payments, and the amount is the booking's own total (BR-22). There is no internal trust-me endpoint.
- **Guards:** the hold must have more than 30 s left (so a payment cannot start seconds before expiry); one active payment per booking (partial unique index); an open checkout is resumed rather than duplicated.

### D-027 — Review eligibility from a local projection (M5)
- **Decision:** review-service keeps `eligible_stays`, fed by `BookingCompleted` (`bookingId, userId, hotelId, checkOutDate`). A review needs a row for **this booking and this user**; one review per booking (`UNIQUE booking_id`, concurrent double submit → 409 `REVIEW_EXISTS`). Authors are shown as "Verified guest" only.
- **Why:** no synchronous call to booking-service on the write path; the check cannot be bypassed by sending another user's booking id (403 `REVIEW_NOT_ALLOWED`).
- **Edit/delete:** author only (admin may delete). Moderation (VISIBLE/HIDDEN + reason) is admin only; hidden reviews leave the public list and the score.

### D-028 — Hotel score as absolute, versioned events (M5)
- **Decision:** after each review change review-service locks `hotel_rating_stats` for the hotel, recomputes `{avgRating (1 decimal, HALF_UP), reviewCount}` from VISIBLE reviews, increments `version` and publishes `HotelRatingChanged` on `review-events` (keyed by hotelId). hotel-service applies it only `WHERE rating_version < version`.
- **Why:** duplicates and reordering cannot corrupt the score; no deltas to lose.
- **Detail:** hotel-service writes the score with plain JDBC and maps the two columns `updatable = false`, so a manager editing the hotel never overwrites the score and the rating event does not bump the hotel's optimistic-lock version (no spurious 409).

### D-029 — Notifications: in-app + email, contact data only from BookingCreated (M5; owner approved "in-app + SMTP/Mailpit")
- **Decision:** notification-service consumes `booking-events` and `payment-events`. `BookingCreated` is the only event with personal data (`userId, contactName, contactEmail, bookingCode, hotelName`, dates, total); it is stored once in `booking_contacts`. Each relevant event creates one notification keyed by its `eventId` (`UNIQUE event_id` + `processed_messages`).
- **Email:** plain text (no HTML rendering → no markup injection), sent **after commit**; each attempt leases the row (`FOR UPDATE SKIP LOCKED`) so the immediate send and the retry job never send twice; backoff 30 s → 4 min, at most 5 attempts, then FAILED. Errors are logged by exception class only (never the address). Mail health is excluded from `/actuator/health`, so an SMTP outage never makes the service unhealthy or blocks consumption (R-06). Dev SMTP is the Mailpit container.
- **Events without a stored contact** (bookings created before M5) are skipped with a WARN instead of failing into the DLT. booking-events are keyed by bookingId and payment events only exist after a booking, so this is not a race in normal operation.

### D-030 — Manager booking view authorised by hotel-service (M5)
- **Decision:** `GET /api/v1/manage/hotels/{id}/bookings` in booking-service first calls hotel-service `GET /api/v1/manage/hotels/{id}` **with the caller's token** (2 s connect / 3 s read): 200 → allowed, 403/401 → 403, 404 → 404, other failure → 503. hotel-service remains the only owner of manager assignments. The staff view shows the contact **name** (front desk) but not email/phone or payment data.

### D-031 — Admin review moderation list (M5 addition)
- **Decision:** added `GET /api/v1/admin/reviews?status&hotelId&page&size` (ADMIN). Without it a hidden review could never be found again to restore it. Additive; no existing endpoint changed.

### D-032 — Staff UI (supersedes D-017)
- **Decision:** `/manage` (managers: own hotels; admins: all) covers hotel details, status transitions, amenities, photos, room types, bookings and (admin) manager assignment; `/admin` covers users (roles, lock), hotel approvals, bookings, payments (needs-review queue, manual refund) and review moderation. The UI only *offers* actions; every rule is enforced by the backend (the specs check the UI mirrors the hotel status machine).
- **Limit:** manager assignment searches users that already have the HOTEL_MANAGER role; hotel-service itself does not verify the role of the assigned user id (it cannot see auth data). Tracked as R-M5-1.

### D-033 — Frontend quality gates (M6)
- **Lint:** angular-eslint 21 (matches Angular 21) with `tsRecommended`, `templateRecommended` **and `templateAccessibility`**, applied to inline templates too; OnPush is enforced. Single documented exception: `FieldError` stays Default because reactive-form control state is not signal-based.
- **Browser E2E:** Playwright 1.64 (Chromium) runs against the **real Compose stack** on :4200 (no mocks): desktop project for every spec, `Pixel 7` mobile project for specs tagged `@mobile`. Test data is created through the public API with the bootstrap admin (`E2E_ADMIN_EMAIL/PASSWORD` from the environment); hotels created by the run are set INACTIVE at the end.
- **Accessibility:** `@axe-core/playwright`, WCAG 2.0/2.1 A + AA tags, zero violations required on public and staff pages; plus a keyboard-only search check.
- **Dependencies:** dev dependencies only were added; the lock file diff was checked: 110 packages added, **0 existing versions changed**.

---

## Pending — need an owner decision (blocking the milestone shown)

| ID | Question | Recommended default | Blocks |
|----|----------|---------------------|--------|
