# Project Audit

Date: 2026-10-16 · Scope: Phase 1 survey + M0 foundation. Only verified facts are recorded here.

## 1. Starting state (survey)
| Item | Finding |
|------|---------|
| Working folder `D:\Documents\stay-hotel` | **Empty**, not a git repository |
| Design document | `hotel-stayed-report.docx` found at `OneDrive/Documents/Máy tính/`. Text extracted and read: chapters 1–6 fully, chapter 7 up to §7.6; §7.8–8 headings only. Contains OBJ-01..08, FR-*, NFR-01..10, BR-01..32, sequence/state diagrams, ERD, service design and API reference tables. |
| Prior code (outside the folder) | `Fresher_CMC-Global/Java BE/.../microservices_hotel_booking` (git, 19 commits, remote `vanmanh23/Microservices_Hotel_Booking_System`, an uncommitted `docs/` folder) and three skeletal Angular 21 apps (~1000 TS lines or less, no feature pages). |
| Owner decision | Build from scratch in `stay-hotel` (DECISIONS D-001). The prior code was **not modified and not reused**. |
| `AGENTS.md` / `CLAUDE.md` | None present. |

### Environment
| Tool | Version | Note |
|------|---------|------|
| JDK | 17.0.1 (Oracle) | |
| Maven | 3.9.8 | |
| Node / npm | 22.12.0 / 10.9.0 | npm 10.9.0 crashes on install, so npm 11 is used via npx (D-008) |
| Docker | Engine 28.1.1, Compose 2.35.1 | Docker Desktop was not running at session start; I started it |
| psql (host) | not installed | DB checks run via `docker compose exec postgres psql` |

Old containers from the previous project exist on this machine (`auth-service`, `postgres-booking`, ... all exited). They were left untouched. This stack uses compose project `stayhotel` with no fixed container names, so names do not clash.

## 2. Architecture as built (M0)
| Component | Tech | Port | DB | State |
|-----------|------|------|----|-------|
| frontend | Angular 21.2 → nginx 1.27 | 4200→80 | — | shell, home and 404 pages; `/api` proxied to the gateway; security headers and CSP |
| api-gateway | Spring Cloud Gateway 4.3 (WebFlux) | 8080 | — | `/api/v1` routes to 6 services, CORS allow-list, timeouts |
| auth-service | Spring Boot 3.5.7 MVC | 8081 | auth_db | Flyway V1 `users`, `user_roles` |
| hotel-service | ″ | 8082 | hotel_db | skeleton, no migrations |
| booking-service | ″ | 8083 | booking_db | skeleton |
| payment-service | ″ | 8084 | payment_db | skeleton |
| review-service | ″ | 8085 | review_db | skeleton |
| notification-service | ″ | 8086 | notification_db | skeleton |
| common-lib | jar | — | — | `ApiError`, `BusinessException`, `GlobalExceptionHandler` (auto-configured) |
| postgres | 17 | 127.0.0.1:5432 | 6 DBs, 1 owner role each | init script `infra/postgres/init` |
| kafka | apache/kafka 3.9.1 KRaft | 127.0.0.1:9094 | — | running and healthy; **no producers/consumers yet**; topic auto-create disabled |

Communication: browser → nginx (same origin) → gateway (REST) → services. Kafka is provisioned but unused until M4.

## 3. Functional state
Nothing user-facing is implemented yet. Every FR is *Not Started* except the auth schema (see `REQUIREMENTS_TRACEABILITY.md`). The home page has no mock data; it says search arrives in M2.

## 4. Verification results (M0)
| Check | Command | Result |
|-------|---------|--------|
| Backend build + tests | `cd backend && mvn -B clean verify` | **PASS**: BUILD SUCCESS, 1:59 min. 32 tests, 0 failures: common-lib 4, gateway 13, auth 3+2, hotel/booking/payment/review/notification 2 each (Testcontainers `postgres:17`) |
| Frontend build | `cd frontend && npx ng build` | **PASS**: initial bundle 214 kB raw / 58.6 kB transfer |
| Frontend tests | `npx ng test --watch=false` | **PASS**: 3/3 (Vitest 4.1) |
| Frontend lint | — | **N/A**: no linter configured yet (planned for M6) |
| Stack start | `docker compose up -d --build --wait` | **PASS** after one fix: 10/10 containers healthy. The first run had the frontend unhealthy because `localhost` resolved to `::1` in Alpine; the healthcheck now uses `127.0.0.1`. |
| E2E routing | `curl http://localhost:4200/api/v1/{auth/x, users/me, hotels/x, hotels/1/reviews, bookings/me, payments/x, reviews/x, notifications/me}` | **PASS**: each returns the downstream service's `ApiError` 404 body (`"code":"NOT_FOUND"`), proving nginx → gateway → service. Unknown prefix: gateway 404. |
| Actuator exposure | `curl :8080/actuator/env` | **PASS**: 404 (actuator not routed; services expose health/info only) |
| Security headers | `curl -I :4200/`, `/api/...`, `/main-*.js` | **PASS** after a fix: nosniff, `X-Frame-Options: DENY`, Referrer-Policy and CSP on all locations. The first build lost them through nginx `add_header` inheritance. |
| DB ownership | `psql ... pg_database` | **PASS**: 6 DBs, each owned by its own role |
| Flyway | `SELECT * FROM flyway_schema_history` (auth_db) | **PASS**: V1 applied, `users`/`user_roles` owned by `auth_user` |
| Cross-DB isolation | `psql -U auth_user -d hotel_db` | **PASS**: `permission denied for database "hotel_db"` |
| CORS (runtime) | `curl -X OPTIONS` with Origin `localhost:4200` / `evil.example` | **PASS**: 200 with `Access-Control-Allow-Origin`; 403 for the other origin |
| Load / performance | — | **NOT RUN** (M7) |

## 5. Risks and open issues
| # | Risk | Mitigation / next step |
|---|------|------------------------|
| R1 | Business decisions P-01..P-09 (inventory model, hold time, tax, refunds, payment provider, late payment) are not confirmed | Defaults proposed in `DECISIONS.md`; confirm before M3/M4 |
| R2 | No authentication yet; all routes are open (only 404s exist) | M1 adds JWT validation in **every** service, not only the gateway |
| R3 | Seven JVMs plus Kafka need about 4–5 GB RAM locally | heap capped per container (`JAVA_TOOL_OPTIONS`); optional profiles later |
| R4 | npm 11 skips install scripts (esbuild etc.) | build and tests pass; watch for native-binary issues |
| R5 | Dockerfiles build each service with Maven inside Docker (slow on a cold cache) | BuildKit cache mount for `~/.m2`; acceptable for now |
| R6 | No payment-gateway credentials | sandbox provider with HMAC webhooks (P-05) |

## 6. M1 verification (2026-10-16)
| Check | Command | Result |
|-------|---------|--------|
| Backend | `cd backend && mvn -B clean verify` | **PASS**: BUILD SUCCESS, 2:40 min, 71 tests (common 4, gateway 17, auth 30, other 5 services 4 each) |
| Frontend build | `npx ng build` | **PASS**: initial 262 kB raw / 73 kB transfer; auth pages lazy-loaded |
| Frontend tests | `npx ng test --watch=false` | **PASS**: 25/25 (interceptor, auth service, guards, login page, app) |
| Stack | `docker compose up -d --build --wait` | **PASS**: 10/10 healthy; auth_db Flyway V1 and V2 applied |
| E2E | `python -I m1_e2e.py` through `:4200` | **PASS**: 22/22 checks (register, role escalation ignored, duplicate, generic login error, hardened cookie, cross-service JWKS validation for 5 services, 403 admin for customer, rotation, stale reuse, logout, bootstrap admin, lock → 403, JWKS not public) |
| Secrets in logs | grep compose logs for the test password, `eyJ…` JWTs, `SH_REFRESH=` values and the admin password | **PASS**: 0 matches |
| Frontend lint | — | **N/A**: no linter yet (M6) |

## 7. M2 verification (2026-10-17)
| Check | Command | Result |
|-------|---------|--------|
| Backend | `cd backend && mvn -B clean verify` | **PASS**: BUILD SUCCESS, 2:42 min, 132 tests (hotel 61, gateway 21, auth 30, common 4, other services 4 each) |
| Frontend | `npx ng build`; `npx ng test --watch=false` | **PASS**: build OK (initial 273 kB / 75 kB transfer); 37/37 tests |
| Stack | `docker compose up -d --build --wait` | **PASS**: 10/10 healthy; hotel_db Flyway V1 applied |
| Demo data | `python scripts/seed-demo-data.py ...`, run twice | **PASS**: 5 hotels created; the second run skipped all of them |
| E2E | `m2_e2e.py` through `:4200` | **PASS**: 19/19 (accent-insensitive search, filters, sort, pagination, 400 on bad dates, detail, room-type fit, 401/403 on writes, draft hidden, no self-approval, cross-manager IDOR blocked, approval makes the hotel public, optimistic lock 409, hide) |
| Real browser / responsive | — | **NOT RUN**: planned for M6 |
| Search performance (EXPLAIN / load) | — | **NOT RUN**: dataset too small to be meaningful; M7 |
