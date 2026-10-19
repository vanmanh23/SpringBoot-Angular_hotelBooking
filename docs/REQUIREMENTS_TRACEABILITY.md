# Requirements Traceability

Source: `hotel-stayed-report.docx` (OBJ §1.3.2, FR §2.3, NFR §2.4, BR §2.5).
Status values: **Not Started · In Progress · Implemented · Verified · Blocked**. "Verified" requires a passing automated test or recorded run; the evidence column names it.

Last updated: 2026-10-18 (M4).

## Objectives
| ID | Objective | Milestone | Status | Evidence |
|----|-----------|-----------|--------|----------|
| OBJ-01 | Users: register, login, profile, roles | M1/M5 | Verified (backend + FE; admin user screen M5) | `AuthFlowIntegrationTest`, FE specs, E2E 22/22 |
| OBJ-02 | Search by location, dates, guests | M2 (M3 adds booked-unit availability) | Verified (advisory availability) | `HotelSearchIntegrationTest` 22, search page specs, E2E |
| OBJ-03 | Hotel data: info, amenities, images, room types | M2/M5 | Verified (API + management UI, D-032) | `HotelManagementIntegrationTest` 19, `manage.spec.ts`, E2E |
| OBJ-04 | Booking: create, view, track, cancel | M3/M4 | Verified | booking tests (42), FE specs, E2E M3 16/16 + M4 21/21 |
| OBJ-05 | Payment integration | M4 | Verified with the sandbox provider (D-023); real gateway needs credentials | payment tests (30), E2E 21/21 |
| OBJ-06 | Administration | M1/M5 | Verified (API + UI); audit of non-user admin actions open (BR-32) | admin tests in auth/booking/payment/review, `admin.spec.ts`, E2E M5 32/32 |
| OBJ-07 | Quality: business, security and error tests | M0–M7 | In Progress | 220 backend + 78 frontend tests PASS (M5), incl. concurrency, Kafka duplicates, DLT, IDOR checks |
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
| FR-SEARCH-05 | Availability for dates and party | hotel (capacity) + booking (units) | `/hotels/{id}/room-types`, `GET /api/v1/availability` | `publicRoomTypesFilterByParty`, `availableDouble` checks, E2E | Verified on detail page; search list still capacity-only (advisory) |
| FR-HOTEL-01 | Create hotel | hotel-service | `POST /api/v1/hotels` | `CreationAndApproval` | Verified (API) |
| FR-HOTEL-02 | Update hotel within scope | hotel-service | `PUT /api/v1/hotels/{id}` (+version) | `Ownership`, `OptimisticLocking` | Verified (API) |
| FR-HOTEL-03 | Room types | hotel-service | `/hotels/{id}/room-types`, `/room-types/{id}` | room type validation and ownership tests | Verified (API) |
| FR-HOTEL-04 | Prices | hotel-service | `room_types.base_price` (VND whole) | validation tests | Verified (base price; seasonal rates out of scope) |
| FR-HOTEL-05 | Capacity and number of units | hotel-service | `capacity`, `total_units` | search capacity tests | Verified |
| FR-HOTEL-06 | Activate / suspend / hide | hotel-service | `PATCH /hotels/{id}/status`, `/room-types/{id}/status` | `StatusMachine`, `DomainRulesTest` | Verified |
| FR-BOOK-01 | Create booking | booking-service, FE checkout | `POST /api/v1/bookings` | `Creation` tests, checkout spec, E2E | Verified |
| FR-BOOK-02 | Price calculation | `PriceCalculator` | quote + booking | `PriceCalculatorTest`, E2E exact totals | Verified |
| FR-BOOK-03 | Store stay data and price snapshot | `bookings`, `booking_rooms` | — | `laterPriceChangesDoNotAlterExistingBookings` | Verified (no guest-name list, D-022) |
| FR-BOOK-04 | View own bookings | booking-service, FE my-bookings/detail | `GET /bookings/me`, `/bookings/{id}` | `Ownership` tests, FE specs | Verified |
| FR-BOOK-05 | Cancel per policy | booking-service, FE detail | `POST /bookings/{id}/cancel` | 48 h rule tests, E2E | Verified (refund in M4) |
| FR-BOOK-06 | State transitions | `BookingStatus`, history | `booking_status_history` | lifecycle + race tests | Verified |
| FR-BOOK-07 | No overbooking | `InventoryRepository` | `room_type_inventory` | `BookingConcurrencyTest`, E2E race | Verified |
| FR-PAY-01 | Start a payment for a booking | payment-service, FE booking page | `POST /api/v1/payments` | `Checkout` tests, FE spec, E2E | Verified |
| FR-PAY-02 | Verify provider result | `WebhookSignature`, `applyProviderEvent` | webhook endpoint | `PaymentRulesTest`, `Webhooks` tests, E2E forged/stale → 401 | Verified |
| FR-PAY-03 | Record success/failure/pending | payment state machine + history | `payments`, `payment_status_history` | decline/pay tests | Verified |
| FR-PAY-04 | No duplicate success | `webhook_events` PK, partial unique index | — | duplicate redelivery tests, E2E replay | Verified |
| FR-PAY-05 | Refund | `startRefund`, `RefundSubmitter`, sandbox | `refunds` | `Refunds` tests (4), E2E refund + late refund | Verified |
| FR-PAY-06 | Transaction history | `payment_status_history`, `GET /payments/booking/{id}` | — | flow tests | Verified (no admin UI yet) |
| FR-ADMIN-01 | Admin user management | auth-service | `/api/v1/admin/users`; `audit_logs` | `Administration` tests (6), E2E | Verified (API + `/admin` users UI) |
| FR-ADMIN-02 | Admin hotel moderation | hotel-service | approval/suspension, `/manage/hotels`, manager assignment | `CreationAndApproval`, assignment tests | Verified (API + `/admin` approvals, `/manage` managers tab) |
| FR-ADMIN-03..04 | Admin bookings, payments | booking/payment, FE `/admin` | `/admin/bookings`, `/admin/payments?needsReview`, admin refund | `BookingStaffViewsIntegrationTest`, `amountMismatchIsFlaggedNotConfirmed`, admin refund test, `admin.spec.ts`, E2E | Verified (lists; charts/dashboards not in scope) |
| FR-NOTI-01..02 | Booking and payment notifications | notification-service + Kafka, FE bell + `/notifications` | `notifications`, `booking_contacts`; `/notifications/me`, read, read-all (D-029) | `NotificationIntegrationTest` 6 (dedup, ownership, after-commit email, bounded retries, real broker), `notifications.page.spec.ts`, E2E (in-app + Mailpit) | Verified |
| FR-REVIEW-01 | Review a completed stay (one per booking) | review-service, FE booking detail | `POST /reviews`, `eligible_stays` (D-027) | `ReviewIntegrationTest` (eligibility, IDOR, 409, concurrent submit), `reviews.spec.ts`, E2E | Verified |
| FR-REVIEW-02 | Show reviews and hotel score | review + hotel-service, FE hotel detail | `GET /hotels/{id}/reviews`, `HotelRatingChanged` (D-028) | `RatingProjectionIntegrationTest` 3, E2E score 4.0 → hidden → 0 | Verified |
| FR-REVIEW-03 | Edit/delete own review; admin moderation | review-service, FE | `PUT/DELETE /reviews/{id}`, `/admin/reviews` (D-031) | `onlyTheAuthorEditsOrDeletesAndAdminCanModerate`, `admin` E2E | Verified |

## Non-functional requirements
| ID | Area | How it is addressed | Status | Evidence |
|----|------|---------------------|--------|----------|
| NFR-01 | Performance | k6 tests in M7; indexes per query | Not Started | |
| NFR-02 | Scalability | Stateless services behind the gateway; DB per service | In Progress | architecture (M0) |
| NFR-03 | Security | uniform errors; CORS allow-list; no secrets in repo; per-service DB roles; JWT in every service; BCrypt-12; hashed rotating refresh tokens; lockout; no secrets in logs | In Progress | M1 tests + E2E; log scan found 0 tokens/passwords |
| NFR-04 | Consistency (no overbooking) | conditional UPDATE per night (D-018) | Verified | `BookingConcurrencyTest` 6/6 (×3 repeats), E2E 10-way race |
| NFR-05 | Reliability | timeouts on every outbound call; outbox; at-least-once + dedup; retries/backoff + DLT; webhook redelivery; refund resubmission | In Progress (no circuit breaker yet) | M4 tests, E2E (0 dead letters) |
| NFR-06 | Maintainability | module-per-service, common-lib limited to cross-cutting code | In Progress | repo structure |
| NFR-07 | Testability | JUnit 5, Testcontainers (Postgres 17), Vitest | In Progress | `mvn verify` PASS |
| NFR-08 | Observability | actuator health/readiness; `X-Correlation-Id` from gateway into every service's log MDC | In Progress | `GatewayRoutingTest` correlation cases, smoke tests |
| NFR-09 | Recoverability | named volumes; backup procedure in M8 | Not Started | |
| NFR-10 | Compatibility | responsive CSS (grids collapse below 600–800 px) | In Progress | NOT RUN in real browsers yet (M6) |

## Business rules
| ID | Rule (short) | Where enforced | Status |
|----|--------------|----------------|--------|
| BR-01 | check-in < check-out | `StayPolicy` + DB CHECK | Verified |
| BR-02 | guests > 0 and ≤ capacity | `StayPolicy` | Verified |
| BR-03 | nights = check-out − check-in | DB CHECK `nights = check_out - check_in` | Verified |
| BR-04 | booking window policy | 1–30 nights, ≤ 365 days ahead | Verified |
| BR-05 | hotel time zone | hotel-local today / check-in moment / completion | Verified |
| BR-06 | only ACTIVE hotels are public | search SQL, `requirePublic` | Verified |
| BR-07 | room type fits capacity | search filter + booking re-check | Verified |
| BR-08 | search availability is advisory | API_CONTRACT note + UI copy on results and detail | Implemented |
| BR-09 | re-check availability on booking | atomic reserve in the booking transaction | Verified |
| BR-10 | holds and confirmed bookings count toward inventory | reserved until EXPIRED/CANCELLED | Verified |
| BR-11 | concurrency control | conditional UPDATE + row locks | Verified |
| BR-12 | price computed in backend | request has no amounts; extra fields ignored (tested) | Verified |
| BR-13 | total from nights, qty, price, tax, fee | `PriceCalculator` + DB CHECK on total | Verified |
| BR-14 | price snapshot | `bookings` + `booking_rooms` | Verified |
| BR-15 | currency and rounding | VND, HALF_UP, whole đồng (D-020) | Verified |
| BR-16 | unique booking code | `SH-yyMMdd-XXXXXXXX` + UNIQUE | Verified |
| BR-17 | only authorized users view/edit/cancel | owner or ADMIN, 404 otherwise | Verified |
| BR-18 | valid state transitions | `BookingStatus` + history + row lock | Verified |
| BR-19 | payment deadline | 15-minute hold + job (live E2E with 20 s hold) | Verified |
| BR-20 | release on expiry/cancel | `release()` in the same transaction | Verified |
| BR-21 | idempotent create | mandatory key, per user, hash check | Verified |
| BR-22 | payment amount from backend | booking read with caller token (D-026) | Verified |
| BR-23 | only verified results update state | HMAC + timestamp; redirect never trusted (FE) | Verified |
| BR-24 | duplicate webhook is a no-op | `webhook_events` + state machine | Verified |
| BR-25 | payment ≠ booking status | separate machines and services, linked by events | Verified |
| BR-26 | cancel ≠ refunded | `refunds` + booking `refundStatus` | Verified |
| BR-27 | refund follows policy | 48 h rule (D-021), one refund per payment, idempotent provider key | Verified |
| BR-28 | guest: public only | deny-by-default security config in every service | Verified (no public business endpoints yet; health only) |
| BR-29 | customer: own bookings | `loadVisible`, `/bookings/me` | Verified |
| BR-30 | manager: assigned hotels | `HotelAccessPolicy` (D-013); booking view via hotel-service (D-030) | Verified (hotels, room types, images, amenities, hotel bookings) |
| BR-31 | admin: admin functions | URL rule + `@PreAuthorize`; no self-modification | Verified (users, bookings, payments, reviews: non-admin → 403) |
| BR-32 | audit sensitive admin actions | `audit_logs` | Partial: user status/role changes audited; review moderation keeps reason + timestamp; hotel status/manager changes and manual refunds not in an audit log yet (M7) |
