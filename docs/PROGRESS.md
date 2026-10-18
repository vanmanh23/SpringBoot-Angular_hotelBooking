# Progress

Read this first at the start of every session; update it at the end.

Labels: PASS · FAIL · BLOCKED · NOT RUN · N/A

| Task ID | Status | Evidence | Next step |
|---------|--------|----------|-----------|
| P1 Survey | Completed | `PROJECT_AUDIT.md` §1; design doc read (ch. 1–6, part of 7) | — |
| M0-1 Repo, git, docs | Completed | git repo on branch `feat/m0-foundation`; `.env` ignored (`git check-ignore -v .env`) | — |
| M0-2 Backend skeleton | Completed | `mvn -B clean verify` → BUILD SUCCESS, 32 tests PASS | — |
| M0-3 Uniform errors | Completed | `GlobalExceptionHandlerTest` 4/4 PASS; runtime 404 bodies via gateway | Add 401/403 mapping in M1 |
| M0-4 Gateway routing + CORS | Completed | `GatewayRoutingTest` 13/13 PASS; curl preflight 200/403 | — |
| M0-5 First Flyway migration | Completed | `AuthSchemaMigrationTest` 3/3 PASS; `flyway_schema_history` V1 in auth_db | — |
| M0-6 Angular skeleton | Completed | `ng build` PASS, `ng test` 3/3 PASS | ESLint in M6 |
| M0-7 Compose stack | Completed | `docker compose up --wait` → 10/10 healthy; E2E curl smoke PASS; role isolation PASS | — |
| M1-1 Register/login/refresh/logout/me | Completed | `AuthFlowIntegrationTest` 24/24 PASS; E2E 22/22 | — |
| M1-2 JWT in every service (JWKS) | Completed | 5× `*ServiceSmokeTest` 4/4 PASS; E2E: each service rejects missing token, accepts auth token | — |
| M1-3 Admin user management + audit | Completed | `Administration` 6/6 PASS; E2E lock flow | Admin UI (M5/M6) |
| M1-4 Bootstrap admin from env | Completed | E2E admin login; log line "Bootstrap administrator account created" | — |
| M1-5 Angular auth (pages, interceptor, guards) | Completed | `ng test` 25/25 PASS; `ng build` PASS | — |
| M1-6 Correlation ID + no secrets in logs | Completed | `GatewayRoutingTest` 17/17 PASS; log scan 0 hits | Structured JSON logs (M8) |
| M2-1 Hotel catalogue schema (V1) | Completed | Flyway V1 applied in hotel_db (stack); CHECKs exercised by tests | — |
| M2-2 Hotel/room/amenity/image management API + ownership | Completed | `HotelManagementIntegrationTest` 19/19; E2E ownership checks | Management UI (M5/M6, D-017) |
| M2-3 Public search/detail/room types | Completed | `HotelSearchIntegrationTest` 22/22; E2E 8 search checks | pg_trgm if needed (M7) |
| M2-4 Angular home/search/detail on real API | Completed | `ng test` 37/37; `ng build` PASS | Browser/responsive check (M6) |
| M2-5 Demo seed script | Completed | `scripts/seed-demo-data.py` run twice (second run skips everything) | — |
| M3-1 Pricing + hotel tax/fee rates | Completed | `PriceCalculatorTest` 4/4; hotel V2; E2E exact totals | — |
| M3-2 Booking create/quote/view/cancel + idempotency | Completed | `BookingFlowIntegrationTest` 18/18; E2E | Manager booking view (M5) |
| M3-3 Atomic inventory + concurrency proof | Completed | `BookingConcurrencyTest` 6/6; E2E 10-way race 1×201 / 9×409 | Load test (M7) |
| M3-4 Hold expiry + completion job | Completed | lifecycle tests; live E2E expiry with 20 s hold | — |
| M3-5 Angular checkout, booking detail, my bookings | Completed | `ng test` 46/46; `ng build` PASS | Payment UI (M4) |
| M4-1 Sandbox provider + signed webhooks | Completed | `PaymentRulesTest` 14/14; webhook tests; E2E forged/stale → 401, replay → duplicate | Real gateway when credentials exist |
| M4-2 Payment API + idempotency + no double charge | Completed | `Checkout` tests 6/6; E2E | — |
| M4-3 Refunds (cancellation, rejected payment, admin) | Completed | `Refunds` tests 4/4; E2E refund and late refund, 1 provider refund | Admin refund UI (M5) |
| M4-4 Outbox + Kafka + dedup + DLT + topic provisioning | Completed | `PaymentOutcomeIntegrationTest` 8/8 (real Kafka); stack topics listed; 0 dead letters in E2E | Consumer-lag metrics (M8) |
| M4-5 Late payment policy | Completed | late accepted / rejected tests; both live in E2E | — |
| M4-6 Angular payment flow | Completed | `ng test` 57/57 | Browser check (M6) |
| M5–M8 | Not Started | — | — |

## Session log
### 2026-10-18 — Session 5 (M4)
- The owner accepted P-05/P-06 → D-023/D-024; D-025 (events), D-026 (amount via caller token). Branch `feat/m4-payment`.
- Backend 198/198, frontend 57/57, stack E2E 21/21 PASS (one check re-run separately after fixing the script).
- Fixed during verification: no topics provisioned at start-up / DLT suffix mismatch; outbox order not deterministic (added `seq`); nested savepoint not working with JPA (replaced by a locked all-or-nothing reservation); tests pointing at the stack broker instead of the Testcontainers one (use `KafkaConnectionDetails`); E2E hold of 20 s shorter than the 30 s pay margin.
- Open: notification-service does not consume events yet (M5); no admin payment/booking lists yet; no circuit breaker.

### 2026-10-17 — Session 4 (M3)
- The owner accepted P-01..P-04 with VAT 8 % / fee 5 % → D-018..D-021. Branch `feat/m3-booking`.
- Backend 164/164, frontend 46/46, stack E2E 16/16 PASS.
- Fixed during verification: `confirmPaid` rolled back the expiry it performed before refusing (added noRollbackFor); `quote()` held a DB transaction during the HTTP call (removed); the checkout quote never fired because inputs arrive after construction (combined with `toObservable`); test-only issues (shared DB counts, microsecond rounding, single-threaded stub, rxjs vs. fake timers).
- Open: payment (M4) is the only way to CONFIRM; the search list does not subtract booked units; no guest-name list.

### 2026-10-17 — Session 3 (M2)
- The owner accepted P-08/P-09 → D-013/D-014. Branch `feat/m2-hotels`, started from `feat/m1-auth`.
- Backend 132/132, frontend 37/37, stack E2E 19/19 PASS.
- Fixed during verification: a seeded amenity code with digits violated our own CHECK (pattern widened consistently in SQL, DTO, search and contract; V1 was not applied anywhere yet); a Java text block dropped the space before `AND` in the amenity filter (500, fixed and covered by tests); the seed script crashed printing Vietnamese on a cp1252 console.
- Open: real availability (booked units) arrives with M3; management UI deferred (D-017); UI not yet checked in a real browser.

### 2026-10-16 — Session 2 (M1)
- The owner accepted P-07, recorded as D-009. Branch `feat/m1-auth`, started from `feat/m0-foundation`.
- Backend 71/71 tests, frontend 25/25 tests, stack E2E 22/22 checks PASS.
- Fixed during verification: V2 `CHAR(64)` vs entity mismatch (not applied anywhere shared; changed to VARCHAR); inner `@Transactional(MANDATORY)` marked the transaction rollback-only and lost the reuse revocation (fixed with `noRollbackFor`).
- Known limitations: access token valid up to 15 min after a lock or password change (D-009); lockout can be triggered by anyone who knows an email (D-011, per-IP rate limit planned for M7).

### 2026-10-16 — Session 1 (survey + M0)
- The working folder was empty; the owner chose to build from scratch here (D-001).
- Built the backend (8 modules), frontend skeleton, Compose stack and docs.
- Fixed during verification: CORS test client (D-007), frontend IPv6 healthcheck, nginx header inheritance, CSP vs. critical CSS inlining.
- Running stack left up: `docker compose ps` (stop with `docker compose down`; volumes kept).
