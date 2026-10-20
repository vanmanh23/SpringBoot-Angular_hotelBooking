// k6 load test (M7) against the running stack through nginx (:4200).
//   docker run --rm -i -e BASE=http://host.docker.internal:4200 -e ADMIN_EMAIL=… -e ADMIN_PASSWORD=… \
//     -v "$PWD/qa/k6:/scripts" grafana/k6:1.3.0 run /scripts/load.js
// Scenarios: public search (constant arrival rate) and booking create + cancel by signed-in customers.
// Thresholds are the acceptance targets recorded in docs (NFR-01); k6 exits non-zero if one is missed.
import http from 'k6/http';
import { check, fail } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://host.docker.internal:4200';
const SEARCH_RPS = Number(__ENV.SEARCH_RPS || 50);
const BOOKING_RPS = Number(__ENV.BOOKING_RPS || 5);
const DURATION = __ENV.DURATION || '2m';
const CUSTOMERS = 20;

const bookingErrors = new Rate('booking_errors');
const bookingCreate = new Trend('booking_create_ms', true);

export const options = {
  setupTimeout: '120s',
  scenarios: {
    search: { executor: 'constant-arrival-rate', exec: 'search', rate: SEARCH_RPS, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 20, maxVUs: 100 },
    booking: { executor: 'constant-arrival-rate', exec: 'book', rate: BOOKING_RPS, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 10, maxVUs: 50 },
  },
  thresholds: {
    'http_req_failed{scenario:search}': ['rate<0.01'],
    'http_req_duration{scenario:search}': ['p(95)<300', 'p(99)<800'],
    booking_errors: ['rate<0.01'],
    booking_create_ms: ['p(95)<800', 'p(99)<1500'],
  },
};

const json = { headers: { 'Content-Type': 'application/json' } };
const auth = (token, extra = {}) => ({ headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, ...extra } });
const uid = () => Math.random().toString(36).slice(2, 10);

function login(email, password) {
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ email, password }), json);
  if (res.status !== 200) fail(`login ${email}: ${res.status}`);
  return res.json('accessToken');
}

export function setup() {
  const admin = login(__ENV.ADMIN_EMAIL, __ENV.ADMIN_PASSWORD);
  const city = `Load City ${uid()}`;
  const hotel = http.post(`${BASE}/api/v1/hotels`, JSON.stringify({ name: `Load Hotel ${uid()}`, address: '1 Load', city, countryCode: 'VN' }),
    auth(admin)).json();
  const rt = http.post(`${BASE}/api/v1/hotels/${hotel.id}/room-types`,
    JSON.stringify({ name: 'Load Std', capacity: 2, basePrice: 600000, totalUnits: 1000 }), auth(admin)).json();
  http.patch(`${BASE}/api/v1/hotels/${hotel.id}/status`, JSON.stringify({ status: 'ACTIVE' }), auth(admin));
  const tokens = [];
  for (let i = 0; i < CUSTOMERS; i++) {
    const email = `load-${uid()}@example.com`;
    http.post(`${BASE}/api/v1/auth/register`, JSON.stringify({ email, password: 'Passw0rd-load', fullName: 'Load' }), json);
    tokens.push(login(email, 'Passw0rd-load'));
  }
  return { admin, hotelId: hotel.id, roomTypeId: rt.id, tokens };
}

const CITIES = ['Da Nang', 'Hue', 'Ha Noi', 'Ho Chi Minh', 'Load City', 'Nha Trang'];

export function search() {
  const city = CITIES[Math.floor(Math.random() * CITIES.length)];
  const day = 10 + Math.floor(Math.random() * 60);
  const res = http.get(`${BASE}/api/v1/hotels?city=${encodeURIComponent(city)}&checkIn=${date(day)}&checkOut=${date(day + 2)}&guests=2&sort=price_asc`,
    { tags: { name: 'GET /hotels' } });
  check(res, { 'search 200': (r) => r.status === 200 });
}

export function book(data) {
  const token = data.tokens[Math.floor(Math.random() * data.tokens.length)];
  const day = 30 + Math.floor(Math.random() * 300);
  const body = { hotelId: data.hotelId, checkInDate: date(day), checkOutDate: date(day + 1), adults: 1, children: 0,
    rooms: [{ roomTypeId: data.roomTypeId, quantity: 1 }], contactName: 'Load', contactEmail: 'load@example.com' };
  const res = http.post(`${BASE}/api/v1/bookings`, JSON.stringify(body), Object.assign(auth(token, { 'Idempotency-Key': `load-${uid()}${uid()}` }),
    { tags: { name: 'POST /bookings' } }));
  bookingCreate.add(res.timings.duration);
  const ok = check(res, { 'booking 201': (r) => r.status === 201 });
  bookingErrors.add(!ok);
  if (ok) {
    // release the unit again so a long run never exhausts inventory
    http.post(`${BASE}/api/v1/bookings/${res.json('id')}/cancel`, JSON.stringify({ reason: 'load test' }),
      Object.assign(auth(token), { tags: { name: 'POST /bookings/{id}/cancel' } }));
  }
}

export function teardown(data) {
  http.patch(`${BASE}/api/v1/hotels/${data.hotelId}/status`, JSON.stringify({ status: 'INACTIVE' }), auth(data.admin));
}

function date(daysFromToday) {
  const d = new Date(Date.now() + daysFromToday * 86400000);
  return d.toISOString().slice(0, 10);
}
