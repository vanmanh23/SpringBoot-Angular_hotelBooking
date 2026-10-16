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
| M2 Hotels & search | Not Started | — | Needs P-08, P-09 |
| M3 Booking | Not Started | — | Needs P-01..P-04 |
| M4 Payment | Not Started | — | Needs P-05, P-06 |
| M5–M8 | Not Started | — | — |

## Session log
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
