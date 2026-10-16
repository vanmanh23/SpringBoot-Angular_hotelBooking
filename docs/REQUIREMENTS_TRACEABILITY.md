# Requirements Traceability

Source: `hotel-stayed-report.docx` (OBJ §1.3.2, FR §2.3, NFR §2.4, BR §2.5).
Status values: **Not Started · In Progress · Implemented · Verified · Blocked**. "Verified" requires a passing automated test or recorded run; the evidence column names it.

Last updated: 2026-10-16 (M0).

## Objectives
| ID | Objective | Milestone | Status | Evidence |
|----|-----------|-----------|--------|----------|
| OBJ-01 | Users: register, login, profile, roles | M1 | In Progress | `auth-service` V1 schema (`AuthSchemaMigrationTest` PASS) |
| OBJ-02 | Search by location, dates, guests | M2 | Not Started | |
| OBJ-03 | Hotel data: info, amenities, images, room types | M2 | Not Started | |
| OBJ-04 | Booking: create, view, track, cancel | M3 | Not Started | |
| OBJ-05 | Payment integration | M4 | Not Started (P-05 pending) | |
| OBJ-06 | Administration | M1/M5 | Not Started | |
| OBJ-07 | Quality: business, security and error tests | M0–M7 | In Progress | 32 backend + 3 frontend tests PASS (M0) |
| OBJ-08 | Deployment: packaging, env config, logging | M0/M8 | In Progress | `docker-compose.yml`, `.env.example`, health checks |

## Functional requirements
| ID | Requirement | Component | API / tables | Tests | Status |
|----|-------------|-----------|--------------|-------|--------|
| FR-AUTH-01 | Register | auth-service, FE auth | `POST /api/v1/auth/register`; `users`, `user_roles` | — | In Progress (schema only) |
| FR-AUTH-02 | Login | auth-service | `POST /api/v1/auth/login` | — | Not Started |
| FR-AUTH-03 | Refresh session | auth-service | `POST /api/v1/auth/refresh` | — | Not Started |
| FR-AUTH-04 | Logout | auth-service | `POST /api/v1/auth/logout` | — | Not Started |
| FR-AUTH-05 | Profile | auth-service | `GET/PUT /api/v1/users/me` | — | Not Started |
| FR-AUTH-06 | Authorization | all services | JWT resource server | — | Not Started |
| FR-SEARCH-01..05 | Search, filter, paginate, detail, availability | hotel-service (+booking for availability) | `/api/v1/hotels...` | — | Not Started |
| FR-HOTEL-01..06 | Hotel and room management | hotel-service | `hotels`, `room_types`, ... | — | Not Started |
| FR-BOOK-01..07 | Booking lifecycle, pricing, inventory | booking-service | `bookings`, `booking_rooms`, ... | — | Not Started |
| FR-PAY-01..06 | Payment, verification, idempotency, refund | payment-service | `payments`, `refunds` | — | Not Started |
| FR-ADMIN-01..04 | Admin users, hotels, bookings, payments | auth/hotel/booking/payment | — | — | Not Started |
| FR-NOTI-01..02 | Booking and payment notifications | notification-service + Kafka | `notifications` | — | Not Started |

## Non-functional requirements
| ID | Area | How it is addressed | Status | Evidence |
|----|------|---------------------|--------|----------|
| NFR-01 | Performance | k6 tests in M7; indexes per query | Not Started | |
| NFR-02 | Scalability | Stateless services behind the gateway; DB per service | In Progress | architecture (M0) |
| NFR-03 | Security | uniform errors with no internals; CORS allow-list; no secrets in repo; per-service DB roles | In Progress | `GlobalExceptionHandlerTest`, `GatewayRoutingTest` CORS cases PASS |
| NFR-04 | Consistency (no overbooking) | conditional inventory update (P-01) | Not Started | |
| NFR-05 | Reliability | gateway timeouts (2 s connect, 10 s response); retries/outbox in M4 | In Progress | `api-gateway/application.yml` |
| NFR-06 | Maintainability | module-per-service, common-lib limited to cross-cutting code | In Progress | repo structure |
| NFR-07 | Testability | JUnit 5, Testcontainers (Postgres 17), Vitest | In Progress | `mvn verify` PASS |
| NFR-08 | Observability | actuator health/readiness; correlation ID and logs in M1/M8 | In Progress | Docker healthchecks |
| NFR-09 | Recoverability | named volumes; backup procedure in M8 | Not Started | |
| NFR-10 | Compatibility | responsive layout | Not Started | |

## Business rules
| ID | Rule (short) | Where enforced | Status |
|----|--------------|----------------|--------|
| BR-01 | check-in < check-out | booking DTO + DB CHECK | Not Started |
| BR-02 | guests > 0 and ≤ capacity | booking-service | Not Started |
| BR-03 | nights = check-out − check-in | pricing | Not Started |
| BR-04 | booking window policy | booking-service | Not Started |
| BR-05 | hotel time zone | P-09 | Not Started |
| BR-06 | only ACTIVE hotels are public | hotel search query | Not Started |
| BR-07 | room type fits capacity | booking-service | Not Started |
| BR-08 | search availability is advisory | API docs + UI copy | Not Started |
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
| BR-28 | guest: public only | security config | Not Started |
| BR-29 | customer: own bookings | ownership | Not Started |
| BR-30 | manager: assigned hotels | P-08 | Not Started |
| BR-31 | admin: admin functions | role checks | Not Started |
| BR-32 | audit sensitive admin actions | `audit_logs` | Not Started |
