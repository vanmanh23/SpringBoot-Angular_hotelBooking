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

---

## Pending — need an owner decision (blocking the milestone shown)

| ID | Question | Recommended default | Blocks |
|----|----------|---------------------|--------|
| P-01 | Inventory model: per room type (count) or per physical room? | **Model A:** per room type per night, a `room_type_inventory(room_type_id, stay_date, total, held, booked)` row in booking_db, reserved with a conditional `UPDATE ... WHERE held+booked+qty <= total` per night inside one transaction. Physical rooms remain hotel data only. | M3 |
| P-02 | Payment hold time for `PENDING_PAYMENT` | 15 minutes, released by a scheduled expiry job | M3 |
| P-03 | Currency, rounding, tax and fees | VND only, 0 decimals, HALF_UP; tax and fee as % configured per hotel and snapshotted per booking | M3 |
| P-04 | Cancellation and refund policy | Free cancel until 48 h before check-in (local hotel time); after that, no refund. A refund is a separate record (`REFUND_PENDING` → `REFUNDED`). | M3/M4 |
| P-05 | Payment gateway | No merchant account is available, so build a **sandbox provider** inside payment-service with HMAC-SHA256 signed webhooks (same shape as VNPay/Stripe). A real gateway needs credentials from the owner. | M4 |
| P-06 | Late payment (succeeds after the booking EXPIRED) | Never auto-confirm: if inventory is still free, re-reserve and confirm; otherwise refund automatically and flag it for review | M4 |
| P-07 | Token storage in the browser | Access JWT in memory; refresh token in an HttpOnly, Secure, SameSite=Strict cookie scoped to `/api/v1/auth`, rotated on each use and stored hashed server-side | M1 |
| P-08 | How a Hotel Manager is linked to hotels | `hotel_managers(hotel_id, user_id)` in hotel_db, assigned by an Admin | M2/M5 |
| P-09 | Business time zone | Stay dates are hotel-local `DATE`s; timestamps are UTC `TIMESTAMPTZ`; each hotel stores an IANA zone (default `Asia/Ho_Chi_Minh`) | M2/M3 |
