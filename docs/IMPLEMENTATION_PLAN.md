# Implementation Plan

One milestone (or one vertical slice) per session. Each task lists its goal, modules, dependencies, Definition of Done (DoD), how to verify it, and risks. Status and evidence live in `PROGRESS.md`.

Common DoD for every task: `mvn -B verify` (in `backend/`) and `npx ng build && npx ng test --watch=false` (in `frontend/`) are green; `API_CONTRACT.md` and `REQUIREMENTS_TRACEABILITY.md` are updated; no secrets committed.

Order rationale: M1 (identity) must come before anything that checks ownership. M2 provides the hotel and room-type data that M3 prices and reserves. M3 must exist before M4 can confirm bookings.

---

## M0 — Foundation (this session)
| ID | Goal | Modules | DoD | Verify |
|----|------|---------|-----|--------|
| M0-1 | Repo, git, structure, docs | root, docs/ | git repo, `.gitignore` covers `.env`, docs created | `git status` |
| M0-2 | Backend multi-module skeleton | backend/* | 8 modules build; each service has actuator health, Flyway, its own DB | `mvn -B clean verify` |
| M0-3 | Uniform error model | common-lib | handler tests pass; no stack traces in body | `GlobalExceptionHandlerTest` |
| M0-4 | Gateway routing + CORS | api-gateway | routes for all `/api/v1` prefixes, CORS allow-list | `GatewayRoutingTest` |
| M0-5 | First migration end-to-end | auth-service | V1 users/user_roles with constraints, applied by Flyway | `AuthSchemaMigrationTest` |
| M0-6 | Angular skeleton | frontend | core/shared/features layout, proxy, lazy routes | `ng build`, `ng test` |
| M0-7 | Docker Compose stack | root | all containers healthy; gateway reaches every service | `docker compose up --wait`, curl smoke |

## M1 — Auth, roles, ownership, errors, env config
| ID | Goal | Modules | Depends | DoD | Risks |
|----|------|---------|---------|-----|-------|
| M1-1 | Register/login/refresh/logout/me | auth-service | M0 | BCrypt hashing; normalized email; register always yields CUSTOMER; generic login error; refresh rotation and reuse detection (P-07); rate limit on login | token theft, user enumeration |
| M1-2 | JWT (RS256) issued by auth; validated by **every** service as a resource server (JWKS from auth) | all services | M1-1 | invalid/expired token → 401 `ApiError`; role checks → 403 | key distribution |
| M1-3 | Admin user management (list, lock, change roles) + audit log | auth-service | M1-2 | only ADMIN; cannot self-demote the last admin; `audit_logs` row per change (BR-32) | privilege escalation |
| M1-4 | Bootstrap admin from env on first start | auth-service | M1-1 | no default password in code | — |
| M1-5 | Angular auth: login/register pages, auth service, interceptor (single-flight refresh), guards, error interceptor | frontend | M1-1 | Reactive Forms, loading/error states, no token in localStorage | refresh races |
| M1-6 | Correlation ID filter + structured logging without secrets | common-lib, gateway | M0 | `X-Correlation-Id` propagated; log tests | — |

Verify: integration tests for each endpoint (happy path, validation, 401/403, duplicate email, locked user, refresh reuse); an IDOR test on `/users/me`.

## M2 — Hotels, search, room types, prices, amenities, images
- Hotel CRUD with an ownership check (Manager limited to assigned hotels, P-08; Admin approves/activates hotels, BR-06).
- Room types (capacity, base price, total_units), amenities catalogue, image URLs (no file upload in v1).
- Public search `GET /api/v1/hotels?city&checkIn&checkOut&guests&minPrice&maxPrice&stars&amenities&page&size&sort`, paginated, ACTIVE hotels only, with indexes per design §4.7; availability shown is advisory (BR-08).
- Angular: home search, results with URL-synced filters, detail page, all on the real API.
- Tests: repository and search tests with Testcontainers, authorization tests (manager on another hotel → 403), pagination edge cases.

## M3 — Booking core
- Pricing in the backend (nights × units × nightly price + tax + fee − discount, P-03); snapshot in `booking_rooms` / `booking_room_nights`.
- Inventory per P-01 with a conditional update under concurrency; hold expires after P-02; scheduled expiry releases the hold (BR-19/20).
- `Idempotency-Key` on `POST /bookings` (BR-21).
- Booking state machine with a `booking_status_history` table; invalid transitions → 409.
- Booking reads room-type data from hotel-service over REST (timeout plus circuit breaker).
- **Tests:** concurrency (N threads competing for the last room → exactly 1 succeeds), expiry, idempotent replay, invalid transitions, ownership (IDOR).

## M4 — Payment & refund
- Payment entity and state machine (P-05 sandbox provider), HMAC-signed webhook, idempotency by unique `(provider, transaction_ref)` and a processed-event table.
- Transactional outbox → Kafka `payment-events`; booking consumer is idempotent (processed `eventId`), retries with backoff, sends failures to a DLT.
- Late payment policy (P-06); cancellation → refund record; retries never refund twice.
- **Tests:** duplicate webhook, bad signature, late payment, failed payment, refund retry, Kafka duplicate delivery.

## M5 — Reviews, notifications, admin — done 2026-10-19 (see PROGRESS)
- Reviews only for COMPLETED bookings of the same user, one per booking; rating aggregate.
- Notification consumer (idempotent) writing `notifications` and sending through a pluggable sender (log/SMTP).
- Admin dashboards: bookings, payments, users (FR-ADMIN-*).

## M6 — Frontend completion — done 2026-10-19 (see PROGRESS)
All flows on the real API: booking form, payment page polling backend status, my bookings, cancel, reviews, profile, admin screens. Responsive, with loading/empty/error/retry states and double-submit guards. Add ESLint (angular-eslint).

## M7 — QA
Unit and integration coverage review, concurrency and duplicate-message suites, security checks (authz matrix, CORS, headers, dependency scan), k6 load script for search and booking with recorded p95/p99 and error rate.

## M8 — Delivery
Compose hardening, optional ELK profile, runbook, test report, acceptance checklist against design chapter 8.
