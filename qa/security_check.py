"""Security checks against the running Compose stack (M7).

Usage:  python qa/security_check.py --admin-email <ADMIN_EMAIL> --admin-password <ADMIN_PASSWORD>
        [--base http://localhost:4200] [--gateway http://localhost:8080]

Covers: authorization matrix per role (anonymous / customer / unassigned manager / assigned manager / admin) for every
route family, IDOR on owned resources, security headers, CORS, exposed ports and error-body hygiene. It creates its own
test users/hotel/booking through the public API (unique names) and leaves the hotel INACTIVE at the end.
Exit code 0 only if every check passes.
"""
import argparse
import datetime
import json
import socket
import sys
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("--admin-email", required=True)
parser.add_argument("--admin-password", required=True)
parser.add_argument("--base", default="http://localhost:4200")
parser.add_argument("--gateway", default="http://localhost:8080")
args = parser.parse_args()
BASE = args.base
results = []


def call(method, path, body=None, token=None, headers=None, base=None):
    req = urllib.request.Request((base or BASE) + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            raw = res.read()
            return res.status, raw.decode("utf-8", "replace"), res.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), e.headers


def check(name, ok, detail=""):
    results.append(bool(ok))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  [{detail}]"))


def login(email, password):
    s, body, _ = call("POST", "/api/v1/auth/login", {"email": email, "password": password})
    assert s == 200, (s, body)
    return json.loads(body)["accessToken"]


def register(prefix):
    email = f"{prefix}-{uuid.uuid4().hex[:10]}@example.com"
    s, body, _ = call("POST", "/api/v1/auth/register", {"email": email, "password": "Passw0rd-sec", "fullName": prefix})
    assert s == 201, (s, body)
    return email, json.loads(body)["id"]


def j(s_body):
    return json.loads(s_body[1])


# ---------------------------------------------------------------- fixtures
admin = login(args.admin_email, args.admin_password)
cust_email, cust_id = register("sec-customer")
other_email, other_id = register("sec-other")
mgr_email, mgr_id = register("sec-manager")
umgr_email, umgr_id = register("sec-unassigned")
for uid in (mgr_id, umgr_id):
    call("PUT", f"/api/v1/admin/users/{uid}/roles", {"roles": ["CUSTOMER", "HOTEL_MANAGER"]}, admin)
customer, other = login(cust_email, "Passw0rd-sec"), login(other_email, "Passw0rd-sec")
manager, unassigned = login(mgr_email, "Passw0rd-sec"), login(umgr_email, "Passw0rd-sec")

hotel = j(call("POST", "/api/v1/hotels", {"name": f"Sec Hotel {uuid.uuid4().hex[:6]}", "address": "1 Sec", "city": "Sec City",
                                          "countryCode": "VN"}, admin))
hid = hotel["id"]
rt = j(call("POST", f"/api/v1/hotels/{hid}/room-types", {"name": "Std", "capacity": 2, "basePrice": 500000, "totalUnits": 2}, admin))
call("PATCH", f"/api/v1/hotels/{hid}/status", {"status": "ACTIVE"}, admin)
call("POST", f"/api/v1/hotels/{hid}/managers", {"userId": mgr_id}, admin)
cin = datetime.date.today() + datetime.timedelta(days=50)
booking = j(call("POST", "/api/v1/bookings", {"hotelId": hid, "checkInDate": str(cin), "checkOutDate": str(cin + datetime.timedelta(days=1)),
                                              "adults": 1, "children": 0, "rooms": [{"roomTypeId": rt["id"], "quantity": 1}],
                                              "contactName": "Sec", "contactEmail": "sec@example.com"},
                 customer, {"Idempotency-Key": uuid.uuid4().hex}))
bid = booking["id"]
payment = j(call("POST", "/api/v1/payments", {"bookingId": bid}, customer, {"Idempotency-Key": uuid.uuid4().hex}))
pid = payment["id"]
X = str(uuid.uuid4())  # an id that exists nowhere

tokens = {"anon": None, "customer": customer, "unassigned": unassigned, "manager": manager, "admin": admin}
DENY = {401, 403}

# ---------------------------------------------------------------- authorization matrix
# (method, path, body, {role: expected}) ; "ok" = 2xx ; "deny" = 401/403 ; ints are exact.
# Mutations use bodies that are valid, so a missing guard would show up as 2xx instead of 401/403.
matrix = [
    ("GET", "/api/v1/hotels?city=Sec", None, {"anon": "ok", "customer": "ok", "admin": "ok"}),
    ("GET", f"/api/v1/hotels/{hid}", None, {"anon": "ok"}),
    ("GET", f"/api/v1/hotels/{hid}/reviews", None, {"anon": "ok"}),
    ("GET", f"/api/v1/availability?hotelId={hid}&checkIn={cin}&checkOut={cin + datetime.timedelta(days=1)}", None, {"anon": "ok"}),
    ("GET", "/api/v1/users/me", None, {"anon": 401, "customer": "ok"}),
    ("GET", "/api/v1/bookings/me", None, {"anon": 401, "customer": "ok"}),
    ("GET", f"/api/v1/bookings/{bid}", None, {"anon": 401, "customer": "ok", "unassigned": 404, "manager": 404, "admin": "ok"}),
    ("POST", f"/api/v1/bookings/{bid}/cancel", {"reason": "x"}, {"anon": 401, "unassigned": 404}),
    ("GET", f"/api/v1/payments/{pid}", None, {"anon": 401, "customer": "ok", "unassigned": 404, "admin": "ok"}),
    ("GET", f"/api/v1/payments/booking/{bid}", None, {"anon": 401, "unassigned": "empty", "admin": "ok"}),
    ("POST", "/api/v1/payments", {"bookingId": bid}, {"anon": 401, "unassigned": 404}),
    ("POST", f"/api/v1/payments/{pid}/refund", {"reason": "x"}, {"anon": 401, "customer": "deny", "manager": "deny"}),
    ("GET", "/api/v1/notifications/me", None, {"anon": 401, "customer": "ok"}),
    ("GET", "/api/v1/reviews/me", None, {"anon": 401, "customer": "ok"}),
    ("POST", "/api/v1/reviews", {"bookingId": bid, "rating": 5}, {"anon": 401, "customer": 403, "admin": "deny"}),
    ("GET", "/api/v1/manage/hotels", None, {"anon": 401, "customer": "deny", "manager": "ok", "admin": "ok"}),
    ("GET", f"/api/v1/manage/hotels/{hid}", None, {"anon": 401, "customer": "deny", "unassigned": "deny", "manager": "ok", "admin": "ok"}),
    ("GET", f"/api/v1/manage/hotels/{hid}/bookings", None, {"anon": 401, "customer": "deny", "unassigned": "deny", "manager": "ok", "admin": "ok"}),
    ("PUT", f"/api/v1/hotels/{hid}/amenities", {"codes": []}, {"anon": 401, "customer": "deny", "unassigned": "deny"}),
    ("POST", f"/api/v1/hotels/{hid}/room-types", {"name": f"N{uuid.uuid4().hex[:6]}", "capacity": 2, "basePrice": 500000, "totalUnits": 1},
     {"anon": 401, "customer": "deny", "unassigned": "deny"}),
    ("PATCH", f"/api/v1/hotels/{hid}/status", {"status": "SUSPENDED"}, {"anon": 401, "customer": "deny", "manager": "deny"}),
    ("POST", f"/api/v1/hotels/{hid}/managers", {"userId": umgr_id}, {"anon": 401, "customer": "deny", "manager": "deny"}),
    ("POST", "/api/v1/hotels", {"name": "x", "address": "x", "city": "x", "countryCode": "VN"}, {"anon": 401, "customer": "deny"}),
    ("POST", "/api/v1/amenities", {"code": "SEC_X", "name": "x", "category": "HOTEL"}, {"anon": 401, "customer": "deny", "manager": "deny"}),
    ("GET", "/api/v1/admin/users", None, {"anon": 401, "customer": "deny", "manager": "deny", "admin": "ok"}),
    ("GET", f"/api/v1/admin/users/{cust_id}", None, {"anon": 401, "customer": "deny", "manager": "deny", "admin": "ok"}),
    ("PATCH", f"/api/v1/admin/users/{other_id}/status", {"status": "LOCKED"}, {"anon": 401, "customer": "deny", "manager": "deny"}),
    ("PUT", f"/api/v1/admin/users/{cust_id}/roles", {"roles": ["ADMIN"]}, {"anon": 401, "customer": "deny", "manager": "deny"}),
    ("GET", "/api/v1/admin/bookings", None, {"anon": 401, "customer": "deny", "manager": "deny", "admin": "ok"}),
    ("GET", "/api/v1/admin/payments", None, {"anon": 401, "customer": "deny", "manager": "deny", "admin": "ok"}),
    ("GET", "/api/v1/admin/reviews", None, {"anon": 401, "customer": "deny", "manager": "deny", "admin": "ok"}),
    ("PATCH", f"/api/v1/admin/reviews/{X}/visibility", {"status": "HIDDEN"}, {"anon": 401, "customer": "deny", "manager": "deny", "admin": 404}),
    ("GET", "/api/v1/internal/anything", None, {"anon": 404, "admin": 404}),
]

for method, path, body, expectations in matrix:
    for role, expected in expectations.items():
        status, text, _ = call(method, path, body, tokens[role],
                               {"Idempotency-Key": uuid.uuid4().hex} if method == "POST" and "payments" in path else None)
        if expected == "ok":
            ok = 200 <= status < 300
        elif expected == "deny":
            ok = status in DENY
        elif expected == "empty":
            ok = status == 200 and json.loads(text) == []
        else:
            ok = status == expected
        check(f"authz {role:10} {method:6} {path.split('?')[0]} -> {expected}", ok, (status, text[:160]))

# privilege escalation through registration
s, body, _ = call("POST", "/api/v1/auth/register", {"email": f"esc-{uuid.uuid4().hex[:8]}@example.com", "password": "Passw0rd-sec",
                                                    "fullName": "Esc", "roles": ["ADMIN"]})
check("registration ignores requested roles (always CUSTOMER)", s == 201 and json.loads(body)["roles"] == ["CUSTOMER"], body)
s, _, _ = call("GET", "/api/v1/admin/users", token=customer[:-4] + "AAAA")
check("tampered JWT signature -> 401", s == 401, s)

# ---------------------------------------------------------------- error hygiene
for path in (f"/api/v1/bookings/not-a-uuid", "/api/v1/hotels?guests=abc", "/api/v1/hotels/" + X):
    s, text, _ = call("GET", path, token=customer)
    check(f"error body has no stack trace / internals ({path})", s in (400, 404) and '"code"' in text
          and not any(w in text for w in ("Exception", "at com.", "org.springframework", "SQL")), (s, text[:200]))
s, text, _ = call("POST", "/api/v1/auth/login", {"email": cust_email, "password": "wrong-password-1"})
check("wrong password gives the generic INVALID_CREDENTIALS", s == 401 and "INVALID_CREDENTIALS" in text, (s, text))

# ---------------------------------------------------------------- headers
_, _, h = call("GET", "/")
for header, must in (("Content-Security-Policy", "frame-ancestors 'none'"), ("X-Content-Type-Options", "nosniff"),
                     ("X-Frame-Options", "DENY"), ("Referrer-Policy", "strict-origin-when-cross-origin"),
                     ("Permissions-Policy", "camera=()")):
    check(f"header {header}", must in (h.get(header) or ""), h.get(header))
check("no server version disclosed", not any(c.isdigit() for c in (h.get("Server") or "")), h.get("Server"))
_, _, h = call("GET", "/api/v1/hotels?city=x")
check("API responses through nginx carry the security headers and X-Correlation-Id",
      h.get("X-Content-Type-Options") == "nosniff" and h.get("X-Correlation-Id"), dict(h))
s, _, _ = call("TRACE", "/api/v1/hotels")
check("TRACE not allowed", s in (400, 403, 405, 501), s)

# ---------------------------------------------------------------- CORS (direct to the gateway)
def preflight(origin):
    return call("OPTIONS", "/api/v1/bookings", headers={"Origin": origin, "Access-Control-Request-Method": "POST",
                                                        "Access-Control-Request-Headers": "authorization,content-type"},
                base=args.gateway)


s, _, h = preflight("http://localhost:4200")
check("CORS preflight from the allowed origin", s == 200 and h.get("Access-Control-Allow-Origin") == "http://localhost:4200", (s, dict(h)))
s, _, h = preflight("https://evil.example")
check("CORS preflight from another origin is refused", s == 403 and not h.get("Access-Control-Allow-Origin"), (s, dict(h)))
s, _, h = call("GET", "/api/v1/hotels", headers={"Origin": "https://evil.example"}, base=args.gateway)
check("no ACAO header for a foreign origin on simple requests", not h.get("Access-Control-Allow-Origin"), dict(h))

# ---------------------------------------------------------------- network exposure
def port_open(port):
    with socket.socket() as sock:
        sock.settimeout(1)
        return sock.connect_ex(("127.0.0.1", port)) == 0


exposed = [p for p in range(8081, 8087) if port_open(p)]
check("business services are not reachable from the host (only through the gateway)", not exposed, exposed)

call("PATCH", f"/api/v1/hotels/{hid}/status", {"status": "INACTIVE"}, admin)
print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
