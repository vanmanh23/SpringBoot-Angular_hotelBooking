#!/usr/bin/env python3
"""Seeds demo hotels through the public API (dev/demo only). Safe to re-run: existing demo hotels are skipped.

Usage: python scripts/seed-demo-data.py --admin-email ADMIN --admin-password PASS [--base http://localhost:4200/api/v1]
Creates a demo HOTEL_MANAGER account (password printed once), five hotels with room types, amenities and images,
and approves them as admin. Uses only the documented endpoints (docs/API_CONTRACT.md).
"""
import argparse
import json
import secrets
import sys
import urllib.error
import urllib.request

MANAGER_EMAIL = "demo.manager@stayhotel.local"

HOTELS = [
    {"name": "Sơn Trà Beach Resort", "city": "Đà Nẵng", "address": "12 Võ Nguyên Giáp, Sơn Trà", "starRating": 5,
     "description": "Beachfront resort with infinity pool and spa.", "amenities": ["WIFI", "POOL", "SPA", "RESTAURANT", "BREAKFAST"],
     "rooms": [("Deluxe Ocean View", 2, 2_400_000, 10, ["AIR_CONDITIONING", "SEA_VIEW", "BALCONY"]),
               ("Family Suite", 4, 4_200_000, 4, ["AIR_CONDITIONING", "SEA_VIEW", "KITCHENETTE"])]},
    {"name": "Hàn River Boutique Hotel", "city": "Đà Nẵng", "address": "45 Bạch Đằng, Hải Châu", "starRating": 4,
     "description": "Boutique hotel overlooking the Dragon Bridge.", "amenities": ["WIFI", "GYM", "FRONT_DESK_24H"],
     "rooms": [("Superior Double", 2, 1_100_000, 12, ["AIR_CONDITIONING", "TV"]),
               ("Twin Room", 2, 1_000_000, 8, ["AIR_CONDITIONING"])]},
    {"name": "Old Quarter Hostel", "city": "Hà Nội", "address": "8 Hàng Bạc, Hoàn Kiếm", "starRating": 2,
     "description": "Friendly hostel in the heart of the Old Quarter.", "amenities": ["WIFI", "FRONT_DESK_24H"],
     "rooms": [("Bunk in 6-bed Dorm", 1, 250_000, 18, ["AIR_CONDITIONING"]),
               ("Private Double", 2, 650_000, 4, ["AIR_CONDITIONING", "PRIVATE_BATHROOM"])]},
    {"name": "Saigon Central Hotel", "city": "Hồ Chí Minh", "address": "100 Nguyễn Huệ, Quận 1", "starRating": 4,
     "description": "Business hotel on the walking street.", "amenities": ["WIFI", "PARKING", "GYM", "RESTAURANT"],
     "rooms": [("Executive King", 2, 1_800_000, 15, ["AIR_CONDITIONING", "TV", "MINIBAR"])]},
    {"name": "Phú Quốc Sunset Villas", "city": "Phú Quốc", "address": "Bãi Trường, Dương Tơ", "starRating": 5,
     "description": "Private villas with pool and sunset views.", "amenities": ["WIFI", "POOL", "AIRPORT_SHUTTLE", "PET_FRIENDLY"],
     "rooms": [("Pool Villa", 4, 6_500_000, 6, ["AIR_CONDITIONING", "BATHTUB", "KITCHENETTE"])]},
]


class Api:
    def __init__(self, base):
        self.base = base

    def call(self, method, path, body=None, token=None):
        req = urllib.request.Request(self.base + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None)
        req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req) as res:
                raw = res.read()
                return res.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            raw = e.read()
            return e.code, json.loads(raw) if raw else None

    def must(self, expected, method, path, body=None, token=None):
        status, data = self.call(method, path, body, token)
        if status != expected:
            sys.exit(f"{method} {path} -> {status} {data}")
        return data

    def login(self, email, password):
        return self.must(200, "POST", "/auth/login", {"email": email, "password": password})["accessToken"]


def main():
    sys.stdout.reconfigure(encoding="utf-8")  # Windows consoles default to cp1252
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:4200/api/v1")
    parser.add_argument("--admin-email", required=True)
    parser.add_argument("--admin-password", required=True)
    args = parser.parse_args()
    api = Api(args.base)
    admin = api.login(args.admin_email, args.admin_password)

    manager_password = "Demo-" + secrets.token_urlsafe(9) + "1a"
    status, user = api.call("POST", "/auth/register",
                            {"email": MANAGER_EMAIL, "password": manager_password, "fullName": "Demo Manager"})
    if status == 201:
        api.must(200, "PUT", f"/admin/users/{user['id']}/roles", {"roles": ["CUSTOMER", "HOTEL_MANAGER"]}, admin)
        print(f"Created {MANAGER_EMAIL} / {manager_password}  (shown once)")
        manager = api.login(MANAGER_EMAIL, manager_password)
    elif status == 409:
        print(f"{MANAGER_EMAIL} already exists; hotels will be created by the admin instead")
        manager = admin
    else:
        sys.exit(f"register manager -> {status} {user}")

    existing = {h["name"] for h in api.must(200, "GET", "/manage/hotels?size=100", token=admin)["content"]}
    for i, spec in enumerate(HOTELS):
        if spec["name"] in existing:
            print(f"skip  {spec['name']}")
            continue
        body = {k: spec[k] for k in ("name", "city", "address", "starRating", "description")}
        body.update(countryCode="VN", cancellationPolicy="Free cancellation up to 48 hours before check-in.")
        hotel = api.must(201, "POST", "/hotels", body, manager)
        hid = hotel["id"]
        api.must(200, "PUT", f"/hotels/{hid}/amenities", {"codes": spec["amenities"]}, manager)
        for n in range(3):
            api.must(201, "POST", f"/hotels/{hid}/images",
                     {"url": f"https://picsum.photos/seed/stayhotel-{i}-{n}/800/450", "caption": f"{spec['name']} photo {n + 1}"},
                     manager)
        for name, capacity, price, units, amenities in spec["rooms"]:
            rt = api.must(201, "POST", f"/hotels/{hid}/room-types",
                          {"name": name, "capacity": capacity, "basePrice": price, "totalUnits": units}, manager)
            api.must(200, "PUT", f"/room-types/{rt['id']}/amenities", {"codes": amenities}, manager)
        api.must(200, "PATCH", f"/hotels/{hid}/status", {"status": "ACTIVE"}, admin)
        print(f"added {spec['name']} ({hid})")


if __name__ == "__main__":
    main()
