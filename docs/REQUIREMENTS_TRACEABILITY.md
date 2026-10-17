# Requirements Traceability

Source: `hotel-stayed-report.docx` (OBJ §1.3.2, FR §2.3, NFR §2.4, BR §2.5).
Status values: **Not Started · In Progress · Implemented · Verified · Blocked**. "Verified" requires a passing automated test or recorded run; the evidence column names it.

Last updated: 2026-10-17 (M2).

## Objectives
| ID | Objective | Milestone | Status | Evidence |
|----|-----------|-----------|--------|----------|
| OBJ-01 | Users: register, login, profile, roles | M1 | Verified (backend + FE login/register/profile); admin UI in M5/M6 | `AuthFlowIntegrationTest`, FE specs, E2E 22/22 |
| OBJ-02 | Search by location, dates, guests | M2 (M3 adds booked-unit availability) | Verified (advisory availability) | `HotelSearchIntegrationTest` 22, search page specs, E2E |
| OBJ-03 | Hotel data: info, amenities, images, room types | M2 | Verified (API); management UI M5/M6 (D-017) | `HotelManagementIntegrationTest` 19, E2E |
| OBJ-04 | Booking: create, view, track, cancel | M3 | Not Started | |
| OBJ-05 | Payment integration | M4 | Not Started (P-05 pending) | |
| OBJ-06 | Administration | M1/M5 | In Progress | user admin API verified (M1); hotel/booking/payment admin in M5 |
| OBJ-07 | Quality: business, security and error tests | M0–M7 | In Progress | 132 backend + 37 frontend tests PASS (M2) |
| OBJ-08 | Deployment: packaging, env config, logging | M0/M8 | In Progress | `docker-compose.yml`, `.env.example`, health checks |

## Functional requirements
| ID | Requirement | Component | API / tables | Tests | Status |
|----|-------------|-----------|--------------|-------|--------|
| FR-AUTH-01 | Register | auth-service, FE `/register` | `POST /api/v1/auth/register`; `users`, `user_roles` | `Registration` tests (4), E2E | Verified |
| FR-AUTH-02 | Login | auth-service, FE `/login` | `POST /api/v1/auth/login`; lockout columns | `LoginFlow` (3), `LoginPage` spec (4), E2E | Verified |
| FR-AUTH-03 | Refresh session | auth-service, FE interceptor | `POST /api/v1/auth/refresh`; `refresh_tokens` | `RefreshRotation` (7 incl. concurrency), interceptor spec (5), E2E | Verified |
| FR-AUTH-04 | Logout | auth-service, FE shell | `POST /api/v1/auth/logout` | `logoutRevokesSessionAndIsIdempotent`, E2E | Verified |
| FR-AUTH-05 | Profile | auth-service, FE `/profile` | `GET/PUT /api/v1/users/me`, `PUT /users/me/password` | `Profile` tests (3), E2E | Verified |
| FR-AUTH-06 | Authorization | all services (D-010), FE guards | JWT resource server, `roles` claim | `*ServiceSmokeTest` (invalid tokens → 401), admin 403 tests, guard specs | Verified (role level); ownership checks per resource in M2–M5 |
| FR-SEARCH-01 | Search by place and stay | hotel-service, FE `/hotels` | `GET /api/v1/hotels`; `hotels.city_key` | city/diacritics test, E2E | Verified |
| FR-SEARCH-02 | Filters (price, stars, amenities) + sort | hotel-service, FE filters | same | `priceStarsAndAmenityFilters`, `sortingByPrice`, page spec | Verified |
| FR-SEARCH-03 | Pagination | hotel-service, FE pager | `Page<T>` | `paginationIsStableAndReportsTotals` | Verified |
| FR-SEARCH-04 | Hotel detail | hotel-service, FE `/hotels/:id` | `GET /api/v1/hotels/{id}` | `publicDetailShowsCatalogueData`, detail spec, E2E | Verified |
| FR-SEARCH-05 | Availability for dates and party | hotel (capacity/units) + booking (M3) | `GET /hotels/{id}/room-types?guests&rooms` | `publicRoomTypesFilterByParty` | In Progress (capacity only; booked units in M3) |
| FR-HOTEL-01 | Create hotel | hotel-service | `POST /api/v1/hotels` | `CreationAndApproval` | Verified (API) |
| FR-HOTEL-02 | Update hotel within scope | hotel-service | `PUT /api/v1/hotels/{id}` (+version) | `Ownership`, `OptimisticLocking` | Verified (API) |
| FR-HOTEL-03 | Room types | hotel-service | `/hotels/{id}/room-types`, `/room-types/{id}` | room type validation and ownership tests | Verified (API) |
| FR-HOTEL-04 | Prices | hotel-service | `room_types.base_price` (VND whole) | validation tests | Verified (base price; seasonal rates out of scope) |
| FR-HOTEL-05 | Capacity and number of units | hotel-service | `capacity`, `total_units` | search capacity tests | Verified |
| FR-HOTEL-06 | Activate / suspend / hide | hotel-service | `PATCH /hotels/{id}/status`, `/room-types/{id}/status` | `StatusMachine`, `DomainRulesTest` | Verified |
| FR-BOOK-01..07 | Booking lifecycle, pricing, inventory | booking-service | `bookings`, `booking_rooms`, ... | — | Not Started |
| FR-PAY-01..06 | Payment, verification, idempotency, refund | payment-service | `payments`, `refunds` | — | Not Started |
| FR-ADMIN-01 | Admin user management | auth-service | `/api/v1/admin/users`; `audit_logs` | `Administration` tests (6), E2E | Verified (API); UI in M5/M6 |
| FR-ADMIN-02 | Admin hotel moderation | hotel-service | approval/suspension, `/manage/hotels`, manager assignment | `CreationAndApproval`, assignment tests | Verified (API); UI M5/M6 |
| FR-ADMIN-03..04 | Admin bookings, payments | booking/payment | — | — | Not Started |
| FR-NOTI-01..02 | Booking and payment notifications | notification-service + Kafka | `notifications` | — | Not Started |

## Non-functional requirements
| ID | Area | How it is addressed | Status | Evidence |
|----|------|---------------------|--------|----------|
| NFR-01 | Performance | k6 tests in M7; indexes per query | Not Started | |
| NFR-02 | Scalability | Stateless services behind the gateway; DB per service | In Progress | architecture (M0) |
| NFR-03 | Security | uniform errors; CORS allow-list; no secrets in repo; per-service DB roles; JWT in every service; BCrypt-12; hashed rotating refresh tokens; lockout; no secrets in logs | In Progress | M1 tests + E2E; log scan found 0 tokens/passwords |
| NFR-04 | Consistency (no overbooking) | conditional inventory update (P-01) | Not Started | |
| NFR-05 | Reliability | gateway timeouts (2 s connect, 10 s response); retries/outbox in M4 | In Progress | `api-gateway/application.yml` |
| NFR-06 | Maintainability | module-per-service, common-lib limited to cross-cutting code | In Progress | repo structure |
| NFR-07 | Testability | JUnit 5, Testcontainers (Postgres 17), Vitest | In Progress | `mvn verify` PASS |
| NFR-08 | Observability | actuator health/readiness; `X-Correlation-Id` from gateway into every service's log MDC | In Progress | `GatewayRoutingTest` correlation cases, smoke tests |
| NFR-09 | Recoverability | named volumes; backup procedure in M8 | Not Started | |
| NFR-10 | Compatibility | responsive CSS (grids collapse below 600–800 px) | In Progress | NOT RUN in real browsers yet (M6) |

## Business rules
| ID | Rule (short) | Where enforced | Status |
|----|--------------|----------------|--------|
| BR-01 | check-in < check-out | booking DTO + DB CHECK | Not Started |
| BR-02 | guests > 0 and ≤ capacity | booking-service | Not Started |
| BR-03 | nights = check-out − check-in | pricing | Not Started |
| BR-04 | booking window policy | booking-service | Not Started |
| BR-05 | hotel time zone | `hotels.time_zone` (D-014) | Implemented (stored and exposed); applied to stay rules in M3 |
| BR-06 | only ACTIVE hotels are public | search SQL, `requirePublic` | Verified |
| BR-07 | room type fits capacity | search and room-type filter; booking re-check in M3 | In Progress |
| BR-08 | search availability is advisory | API_CONTRACT note + UI copy on results and detail | Implemented |
| BR-09 | re-check availability on booking | booking transaction | Not Started |
| BR-10 | holds and confirmed bookings count toward inventory | inventory model | Not Started |
| BR-11 | concurrency control | conditional update + concurrency test | Not Started |
| BR-12 | price computed in backend | booking-service | Not Started |
| BR-13 | total from nights, qty, price, tax, fee | pricing | Not Started |
| BR-14 | price snapshot | `booking_rooms`/nights | Not Started |
| BR-15 | currency and rounding | P-03 | Not Started |
| BR-16 | unique booking code | DB UNIQUE | Not Started |
| BR-17 | only authorized users view/edit/cancel | ownership checks | Not Started |
| BR-18 | valid state transitions | state machine + history | Not Started |
| BR-19 | payment deadline | `expires_at` + job | Not Started |
| BR-20 | release on expiry/cancel | inventory release | Not Started |
| BR-21 | idempotent create | `Idempotency-Key` | Not Started |
| BR-22 | payment amount from backend | payment-service | Not Started |
| BR-23 | only verified results update state | signed webhook | Not Started |
| BR-24 | duplicate webhook is a no-op | unique transaction ref | Not Started |
| BR-25 | payment ≠ booking status | two state machines | Not Started |
| BR-26 | cancel ≠ refunded | separate refund record | Not Started |
| BR-27 | refund follows policy | P-04 | Not Started |
| BR-28 | guest: public only | deny-by-default security config in every service | Verified (no public business endpoints yet; health only) |
| BR-29 | customer: own bookings | ownership | Not Started |
| BR-30 | manager: assigned hotels | `HotelAccessPolicy` (D-013) | Verified (hotels, room types, images, amenities) |
| BR-31 | admin: admin functions | URL rule + `@PreAuthorize`; no self-modification | Verified for user admin |
| BR-32 | audit sensitive admin actions | `audit_logs` | Verified for user status/role changes |
