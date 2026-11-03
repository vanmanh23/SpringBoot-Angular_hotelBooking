#!/usr/bin/env python3
"""Generates realistic demo data as SQL, one file per service database (dev/demo only).

Usage:
  python scripts/demo-data/generate.py            # writes scripts/demo-data/sql/*.sql
  python scripts/demo-data/generate.py --apply    # ...and loads them into the running compose Postgres

What it creates (all ids are deterministic, so re-running replaces the same rows instead of duplicating them):
  auth_db          1 demo admin, 4 hotel managers, 40 customers (+1 disabled)       password: DemoStay2026
  hotel_db         32 hotels in 12 cities (29 ACTIVE, 2 DRAFT, 1 SUSPENDED), room types, amenities, images, managers
  booking_db       ~260 bookings (completed / confirmed / in-house / cancelled / expired) + history + inventory
  payment_db       sandbox payments, refunds, sessions and status history consistent with the bookings
  review_db        eligible stays, reviews (a few hidden) and hotel rating stats
  notification_db  booking contacts and the guest notifications each booking would have produced

Dates are relative to the day the script runs (past stays are COMPLETED, future ones CONFIRMED). The data is written
straight into the databases (no Kafka events, no outbox rows), mirroring exactly what the services would have stored.
Business rules respected: pricing formula (PriceCalculator), booking code format, per-night inventory never above
total units, 48h free-cancellation, one review per completed stay, rating projection = AVG(visible) rounded HALF_UP.
"""
import argparse
import hashlib
import random
import subprocess
import sys
import unicodedata
import uuid
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta, timezone
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

import bcrypt

NS = uuid.UUID("5e7d2a1c-4b9f-4c1e-9a77-0d3c5f1e2b60")
PASSWORD = "DemoStay2026"
APP_URL = "http://localhost:4200"
VN = timezone(timedelta(hours=7))
OUT = Path(__file__).resolve().parent / "sql"
rnd = random.Random(20261103)


def uid(*parts) -> str:
    return str(uuid.uuid5(NS, ":".join(map(str, parts))))


def hexid(n, *parts) -> str:
    return hashlib.sha256(":".join(map(str, parts)).encode()).hexdigest()[:n]


def q(v) -> str:
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, (int, Decimal)):
        return str(v)
    if isinstance(v, datetime):
        return "'" + v.isoformat() + "'"
    if isinstance(v, (date, time)):
        return "'" + v.isoformat() + "'"
    return "'" + str(v).replace("'", "''") + "'"


def insert(table, cols, rows) -> str:
    if not rows:
        return ""
    values = ",\n".join("  (" + ", ".join(q(r[c]) for c in cols) + ")" for r in rows)
    return f"INSERT INTO {table} ({', '.join(cols)}) VALUES\n{values};\n\n"


def in_list(ids) -> str:
    return "(" + ", ".join(q(i) for i in ids) + ")" if ids else "(NULL)"


def search_key(s: str) -> str:
    s = unicodedata.normalize("NFD", s.replace("đ", "d").replace("Đ", "D"))
    return " ".join("".join(c for c in s if unicodedata.category(c) != "Mn").split()).lower()


def pct(amount: int, percent: Decimal) -> int:
    return int((Decimal(amount) * percent / 100).quantize(Decimal(1), rounding=ROUND_HALF_UP))


def vnd(n) -> str:
    return f"{int(n):,}".replace(",", ".") + " VND"


def at(d: date, h: int, m: int = 0) -> datetime:
    return datetime.combine(d, time(h, m), VN)


# --------------------------------------------------------------------------------------------- reference data ---

FIRST = ["Anh", "Bảo", "Chi", "Dũng", "Giang", "Hà", "Hải", "Hằng", "Hiếu", "Hoa", "Hùng", "Huyền", "Khánh", "Lan",
         "Linh", "Long", "Mai", "Minh", "My", "Nam", "Ngọc", "Nhung", "Phong", "Phương", "Quân", "Quỳnh", "Sơn",
         "Tâm", "Thảo", "Thành", "Trang", "Trung", "Tú", "Tuấn", "Uyên", "Việt", "Vy", "Yến", "Đạt", "Đức"]
LAST = ["Nguyễn", "Trần", "Lê", "Phạm", "Hoàng", "Huỳnh", "Phan", "Vũ", "Võ", "Đặng", "Bùi", "Đỗ", "Hồ", "Ngô",
        "Dương", "Lý"]
MIDDLE = ["Văn", "Thị", "Minh", "Thu", "Đức", "Ngọc", "Hoàng", "Thanh", "Quốc", "Gia", "Bảo", "Hữu"]

POLICY = {
    "flex": "Free cancellation up to 48 hours before check-in. Later cancellations are not refunded.",
    "hostel": "Free cancellation up to 48 hours before check-in. No-shows are charged the full stay.",
    "resort": "Free cancellation up to 48 hours before check-in. Peak-season dates (Tết, 30/4–1/5, 2/9) may "
              "require a non-refundable deposit.",
}

# name, city, address, stars, check-in, check-out, tax%, fee%, policy, description, hotel amenities,
# rooms: (name, capacity, bed, price, units, room amenities, description)
H = [
    ("Hoàn Kiếm Lakeside Hotel", "Hà Nội", "27 Lê Thái Tổ, Hoàn Kiếm", 4, "14:00", "12:00", "8", "5", "flex",
     "Four-star hotel facing Hoàn Kiếm Lake, a short walk from the Old Quarter night market and the Opera House. "
     "Rooftop bar with lake views.", ["WIFI", "RESTAURANT", "BREAKFAST", "GYM", "FRONT_DESK_24H"],
     [("Superior Double", 2, "1 queen bed", 1_350_000, 14, ["AIR_CONDITIONING", "TV", "PRIVATE_BATHROOM"],
       "22 m² room facing the inner courtyard."),
      ("Deluxe Lake View", 2, "1 king bed", 1_950_000, 10, ["AIR_CONDITIONING", "TV", "MINIBAR", "BALCONY"],
       "28 m² with a balcony over the lake."),
      ("Family Room", 4, "2 double beds", 2_600_000, 4, ["AIR_CONDITIONING", "TV", "MINIBAR"],
       "36 m², ideal for two adults and two children.")]),
    ("Old Quarter Lantern Hostel", "Hà Nội", "41 Mã Mây, Hoàn Kiếm", 2, "13:00", "11:00", "8", "0", "hostel",
     "Lively hostel with free walking tours, a common kitchen and nightly family dinners.", ["WIFI", "FRONT_DESK_24H"],
     [("Bed in 8-bed Mixed Dorm", 1, "1 bunk bed", 190_000, 24, ["AIR_CONDITIONING"], "Lockers and reading lights."),
      ("Private Twin", 2, "2 single beds", 520_000, 5, ["AIR_CONDITIONING", "PRIVATE_BATHROOM"], "Quiet floor.")]),
    ("Tây Hồ Garden Residence", "Hà Nội", "18 Xuân Diệu, Tây Hồ", 4, "14:00", "12:00", "8", "5", "flex",
     "Serviced apartments by West Lake with a garden pool — popular for long stays.",
     ["WIFI", "POOL", "GYM", "PARKING", "PET_FRIENDLY"],
     [("Studio Apartment", 2, "1 queen bed", 1_450_000, 10, ["AIR_CONDITIONING", "KITCHENETTE", "TV"],
       "35 m² studio with kitchenette."),
      ("Two-Bedroom Apartment", 5, "1 king bed + 2 single beds", 3_200_000, 4,
       ["AIR_CONDITIONING", "KITCHENETTE", "TV", "BALCONY"], "75 m² with living room and washing machine.")]),
    ("Ba Đình Heritage Boutique", "Hà Nội", "9 Phan Đình Phùng, Ba Đình", 3, "14:00", "12:00", "8", "5", "flex",
     "Restored 1930s villa on a tree-lined boulevard, close to the Temple of Literature.",
     ["WIFI", "BREAKFAST", "RESTAURANT"],
     [("Classic Queen", 2, "1 queen bed", 980_000, 8, ["AIR_CONDITIONING", "PRIVATE_BATHROOM"], "High ceilings."),
      ("Heritage Suite", 3, "1 king bed + sofa bed", 1_750_000, 3, ["AIR_CONDITIONING", "BATHTUB", "TV"],
       "Original tiles and a clawfoot bathtub.")]),

    ("Saigon Riverside Grand", "Hồ Chí Minh", "8 Tôn Đức Thắng, Quận 1", 5, "15:00", "12:00", "10", "5", "flex",
     "Five-star tower on the Saigon River with an infinity pool, spa and three restaurants.",
     ["WIFI", "POOL", "SPA", "GYM", "RESTAURANT", "BREAKFAST", "AIRPORT_SHUTTLE", "FRONT_DESK_24H"],
     [("Deluxe City View", 2, "1 king bed", 2_900_000, 20, ["AIR_CONDITIONING", "TV", "MINIBAR", "BATHTUB"],
       "38 m², floor-to-ceiling windows."),
      ("Premier River View", 2, "1 king bed", 3_800_000, 12, ["AIR_CONDITIONING", "TV", "MINIBAR", "BATHTUB"],
       "42 m² facing the river."),
      ("Executive Suite", 3, "1 king bed + sofa bed", 6_200_000, 4, ["AIR_CONDITIONING", "TV", "MINIBAR",
       "BATHTUB", "BALCONY"], "75 m² with lounge access.")]),
    ("Bến Thành Central Hotel", "Hồ Chí Minh", "65 Lê Lợi, Quận 1", 3, "14:00", "12:00", "8", "5", "flex",
     "Practical business hotel two minutes from Bến Thành Market and the metro.",
     ["WIFI", "BREAKFAST", "FRONT_DESK_24H"],
     [("Standard Double", 2, "1 double bed", 850_000, 18, ["AIR_CONDITIONING", "TV"], "18 m², no window."),
      ("Superior Twin", 2, "2 single beds", 990_000, 12, ["AIR_CONDITIONING", "TV"], "20 m² with city window.")]),
    ("Thảo Điền Urban Lofts", "Hồ Chí Minh", "22 Quốc Hương, Thủ Đức", 4, "14:00", "11:00", "8", "5", "flex",
     "Design lofts in the expat district, surrounded by cafés and craft-beer bars.",
     ["WIFI", "POOL", "GYM", "PARKING", "PET_FRIENDLY"],
     [("Loft King", 2, "1 king bed", 1_650_000, 10, ["AIR_CONDITIONING", "KITCHENETTE", "TV"], "Mezzanine bedroom."),
      ("Garden Loft", 4, "1 king bed + 2 single beds", 2_700_000, 4, ["AIR_CONDITIONING", "KITCHENETTE", "BALCONY"],
       "Private garden terrace.")]),
    ("Chợ Lớn Pagoda Inn", "Hồ Chí Minh", "112 Hải Thượng Lãn Ông, Quận 5", 2, "14:00", "12:00", "8", "0", "hostel",
     "Family-run guesthouse in Chinatown, next to Bình Tây Market and the old pagodas.", ["WIFI"],
     [("Budget Double", 2, "1 double bed", 420_000, 8, ["AIR_CONDITIONING", "PRIVATE_BATHROOM"], "Simple and clean."),
      ("Triple Room", 3, "3 single beds", 590_000, 4, ["AIR_CONDITIONING", "PRIVATE_BATHROOM"], "Good for friends.")]),

    ("Mỹ Khê Ocean Pearl", "Đà Nẵng", "150 Võ Nguyên Giáp, Sơn Trà", 5, "14:00", "12:00", "8", "5", "resort",
     "Beachfront resort on Mỹ Khê with a 50 m pool, kids' club and seafood restaurant.",
     ["WIFI", "POOL", "SPA", "GYM", "RESTAURANT", "BREAKFAST", "AIRPORT_SHUTTLE"],
     [("Deluxe Ocean View", 2, "1 king bed", 2_600_000, 18, ["AIR_CONDITIONING", "SEA_VIEW", "BALCONY", "MINIBAR"],
       "40 m² with sea-facing balcony."),
      ("Family Suite Ocean", 4, "1 king bed + 2 single beds", 4_500_000, 6,
       ["AIR_CONDITIONING", "SEA_VIEW", "BALCONY", "BATHTUB"], "70 m², two rooms."),
      ("Beach Villa", 6, "2 king beds + sofa bed", 9_800_000, 2, ["AIR_CONDITIONING", "SEA_VIEW", "KITCHENETTE",
       "BATHTUB"], "Private plunge pool, direct beach access.")]),
    ("Dragon Bridge Sky Hotel", "Đà Nẵng", "36 Bạch Đằng, Hải Châu", 4, "14:00", "12:00", "8", "5", "flex",
     "Riverside tower with a rooftop pool — best seat in town for the weekend Dragon Bridge fire show.",
     ["WIFI", "POOL", "GYM", "RESTAURANT", "FRONT_DESK_24H"],
     [("Superior City", 2, "1 queen bed", 1_150_000, 16, ["AIR_CONDITIONING", "TV"], "24 m²."),
      ("Deluxe River View", 2, "1 king bed", 1_600_000, 10, ["AIR_CONDITIONING", "TV", "BALCONY"], "30 m².")]),
    ("An Thượng Surf Hostel", "Đà Nẵng", "14 An Thượng 4, Ngũ Hành Sơn", 2, "13:00", "11:00", "8", "0", "hostel",
     "Backpacker favourite 300 m from the beach; surfboard rental and rooftop bar.", ["WIFI", "FRONT_DESK_24H"],
     [("Bed in 6-bed Dorm", 1, "1 bunk bed", 220_000, 18, ["AIR_CONDITIONING"], "Female-only dorm available."),
      ("Double with Balcony", 2, "1 double bed", 600_000, 4, ["AIR_CONDITIONING", "BALCONY", "PRIVATE_BATHROOM"],
       "")]),

    ("Hội An Riverside Lantern Villa", "Hội An", "88 Nguyễn Phúc Chu, Minh An", 4, "14:00", "12:00", "8", "5",
     "flex", "Villa across the Thu Bồn River from the Ancient Town, with free bicycles and a lantern-making class.",
     ["WIFI", "POOL", "BREAKFAST", "RESTAURANT", "AIRPORT_SHUTTLE"],
     [("Garden Deluxe", 2, "1 king bed", 1_400_000, 10, ["AIR_CONDITIONING", "BALCONY", "BATHTUB"], "Garden view."),
      ("River Suite", 3, "1 king bed + day bed", 2_300_000, 4, ["AIR_CONDITIONING", "BALCONY", "BATHTUB", "MINIBAR"],
       "Panoramic river terrace.")]),
    ("An Bàng Beach Bungalows", "Hội An", "An Bàng, Cẩm An", 3, "14:00", "11:00", "8", "5", "flex",
     "Thatched bungalows among the palms, 50 m from An Bàng beach.", ["WIFI", "BREAKFAST", "RESTAURANT", "PARKING"],
     [("Garden Bungalow", 2, "1 queen bed", 1_050_000, 8, ["AIR_CONDITIONING", "PRIVATE_BATHROOM"], ""),
      ("Beachfront Bungalow", 3, "1 king bed + single bed", 1_700_000, 3, ["AIR_CONDITIONING", "SEA_VIEW"], "")]),
    ("Ancient Town Homestay", "Hội An", "21 Trần Phú, Minh An", 2, "14:00", "12:00", "8", "0", "hostel",
     "Rooms in a 150-year-old merchant house inside the Ancient Town. Hosts cook cao lầu for breakfast.",
     ["WIFI", "BREAKFAST"],
     [("Heritage Double", 2, "1 double bed", 650_000, 5, ["AIR_CONDITIONING", "PRIVATE_BATHROOM"], "")]),

    ("Nha Trang Bay Horizon", "Nha Trang", "60 Trần Phú, Lộc Thọ", 4, "14:00", "12:00", "8", "5", "resort",
     "Oceanfront high-rise on Trần Phú promenade; island-hopping tours bookable at the desk.",
     ["WIFI", "POOL", "GYM", "RESTAURANT", "BREAKFAST", "FRONT_DESK_24H"],
     [("Deluxe Partial Sea View", 2, "1 queen bed", 1_250_000, 20, ["AIR_CONDITIONING", "TV", "SEA_VIEW"], ""),
      ("Premium Ocean Front", 3, "1 king bed + sofa bed", 1_900_000, 10, ["AIR_CONDITIONING", "TV", "SEA_VIEW",
       "BALCONY"], "")]),
    ("Hòn Tằm Island Resort", "Nha Trang", "Đảo Hòn Tằm, Vĩnh Nguyên", 5, "14:00", "12:00", "10", "5", "resort",
     "Private-island resort reached by speedboat, with house reef snorkelling and a mud spa.",
     ["WIFI", "POOL", "SPA", "RESTAURANT", "BREAKFAST", "AIRPORT_SHUTTLE"],
     [("Hillside Villa", 2, "1 king bed", 3_900_000, 8, ["AIR_CONDITIONING", "SEA_VIEW", "BALCONY", "BATHTUB"], ""),
      ("Beachfront Pool Villa", 4, "1 king bed + 2 single beds", 7_500_000, 4,
       ["AIR_CONDITIONING", "SEA_VIEW", "BATHTUB", "KITCHENETTE"], "")]),

    ("Đà Lạt Pine Hill Lodge", "Đà Lạt", "5 Trần Hưng Đạo, Phường 3", 3, "14:00", "12:00", "8", "5", "flex",
     "Wooden lodge in the pine forest with fireplaces in every room; 10 minutes to Xuân Hương Lake.",
     ["WIFI", "BREAKFAST", "PARKING", "PET_FRIENDLY"],
     [("Forest Double", 2, "1 queen bed", 900_000, 10, ["PRIVATE_BATHROOM", "TV"], "Heater and fireplace."),
      ("Attic Family Room", 4, "1 queen bed + 2 single beds", 1_500_000, 4, ["PRIVATE_BATHROOM", "BALCONY"], "")]),
    ("Langbiang Colonial Palace", "Đà Lạt", "2 Trần Phú, Phường 3", 5, "15:00", "12:00", "10", "5", "flex",
     "1920s French palace hotel with rose gardens, a golf course view and afternoon tea.",
     ["WIFI", "SPA", "RESTAURANT", "BREAKFAST", "GYM", "AIRPORT_SHUTTLE", "FRONT_DESK_24H"],
     [("Palace Deluxe", 2, "1 king bed", 3_300_000, 12, ["BATHTUB", "TV", "MINIBAR"], ""),
      ("Grand Suite", 3, "1 king bed + sofa bed", 6_800_000, 3, ["BATHTUB", "TV", "MINIBAR", "BALCONY"], "")]),
    ("Valley of Love Homestay", "Đà Lạt", "120 Phù Đổng Thiên Vương, Phường 8", 2, "14:00", "11:00", "8", "0",
     "hostel", "Cosy homestay with a strawberry garden, BBQ nights and motorbike rental.", ["WIFI", "PARKING"],
     [("Garden Double", 2, "1 double bed", 480_000, 6, ["PRIVATE_BATHROOM"], ""),
      ("Dorm Bed", 1, "1 bunk bed", 180_000, 12, [], "")]),

    ("Phú Quốc Sunset Cove Resort", "Phú Quốc", "Bãi Trường, Dương Tơ", 5, "14:00", "12:00", "10", "5", "resort",
     "Long Beach resort with a sunset bar on the sand, three pools and a PADI dive centre.",
     ["WIFI", "POOL", "SPA", "GYM", "RESTAURANT", "BREAKFAST", "AIRPORT_SHUTTLE"],
     [("Garden Bungalow", 2, "1 king bed", 2_800_000, 14, ["AIR_CONDITIONING", "BALCONY", "MINIBAR"], ""),
      ("Ocean Pool Villa", 4, "1 king bed + 2 single beds", 8_200_000, 5,
       ["AIR_CONDITIONING", "SEA_VIEW", "BATHTUB", "KITCHENETTE"], "Private 8 m pool.")]),
    ("Dương Đông Night Market Hotel", "Phú Quốc", "15 Bạch Đằng, Dương Đông", 3, "14:00", "12:00", "8", "5", "flex",
     "Mid-range hotel steps from the night market and Dinh Cậu Temple.", ["WIFI", "POOL", "BREAKFAST"],
     [("Superior Double", 2, "1 queen bed", 950_000, 14, ["AIR_CONDITIONING", "TV"], ""),
      ("Family Triple", 3, "1 double + 1 single bed", 1_300_000, 6, ["AIR_CONDITIONING", "TV"], "")]),

    ("Huế Imperial River Hotel", "Huế", "5 Lê Lợi, Vĩnh Ninh", 4, "14:00", "12:00", "8", "5", "flex",
     "On the Perfume River opposite the Citadel; dragon-boat trips leave from the private pier.",
     ["WIFI", "POOL", "RESTAURANT", "BREAKFAST", "SPA"],
     [("Deluxe Citadel View", 2, "1 king bed", 1_500_000, 12, ["AIR_CONDITIONING", "TV", "BALCONY"], ""),
      ("Royal Suite", 3, "1 king bed + sofa bed", 3_100_000, 3, ["AIR_CONDITIONING", "TV", "BATHTUB"], "")]),
    ("Kim Long Garden House", "Huế", "47 Kim Long", 3, "14:00", "12:00", "8", "5", "flex",
     "Traditional garden house (nhà vườn) with lotus ponds and vegetarian royal cuisine.",
     ["WIFI", "BREAKFAST", "PARKING"],
     [("Garden Room", 2, "1 queen bed", 850_000, 6, ["AIR_CONDITIONING", "PRIVATE_BATHROOM"], "")]),

    ("Sa Pa Cloud Valley Eco Lodge", "Sa Pa", "Bản Tả Van", 4, "14:00", "11:00", "8", "5", "flex",
     "Eco lodge overlooking the Mường Hoa rice terraces; guided treks with H'Mông guides.",
     ["WIFI", "RESTAURANT", "BREAKFAST", "SPA"],
     [("Valley View Room", 2, "1 king bed", 1_600_000, 10, ["BALCONY", "BATHTUB"], "Herbal bath."),
      ("Family Chalet", 4, "1 king bed + 2 single beds", 2_500_000, 4, ["BALCONY"], "")]),
    ("Fansipan Trekkers Inn", "Sa Pa", "33 Cầu Mây", 2, "13:00", "11:00", "8", "0", "hostel",
     "Budget inn near the stone church, with gear storage and early breakfast for summit treks.",
     ["WIFI", "BREAKFAST"],
     [("Mountain Double", 2, "1 double bed", 450_000, 8, ["PRIVATE_BATHROOM"], ""),
      ("Dorm Bed", 1, "1 bunk bed", 160_000, 16, [], "")]),

    ("Hạ Long Bay Pearl Hotel", "Hạ Long", "18 Hạ Long, Bãi Cháy", 4, "14:00", "12:00", "8", "5", "flex",
     "Bay-front hotel near the cruise harbour and Sun World cable car.",
     ["WIFI", "POOL", "GYM", "RESTAURANT", "BREAKFAST", "PARKING"],
     [("Deluxe Bay View", 2, "1 king bed", 1_550_000, 16, ["AIR_CONDITIONING", "SEA_VIEW", "TV"], ""),
      ("Junior Suite", 3, "1 king bed + sofa bed", 2_400_000, 5, ["AIR_CONDITIONING", "SEA_VIEW", "BATHTUB"], "")]),

    ("Vũng Tàu Front Beach Hotel", "Vũng Tàu", "29 Quang Trung, Phường 1", 3, "14:00", "12:00", "8", "5", "flex",
     "Weekend getaway hotel facing Front Beach, 2 hours from Saigon.", ["WIFI", "POOL", "PARKING", "BREAKFAST"],
     [("Standard Sea View", 2, "1 queen bed", 900_000, 14, ["AIR_CONDITIONING", "SEA_VIEW", "TV"], ""),
      ("Family Room", 4, "2 double beds", 1_600_000, 6, ["AIR_CONDITIONING", "TV"], "")]),

    ("Quy Nhơn Coral Bay Resort", "Quy Nhơn", "Ghềnh Ráng, Bãi Xép", 4, "14:00", "12:00", "8", "5", "resort",
     "Quiet cliffside resort above a private cove; ideal base for Kỳ Co and Eo Gió.",
     ["WIFI", "POOL", "RESTAURANT", "BREAKFAST", "SPA", "AIRPORT_SHUTTLE"],
     [("Ocean View Deluxe", 2, "1 king bed", 1_800_000, 12, ["AIR_CONDITIONING", "SEA_VIEW", "BALCONY"], ""),
      ("Cliff Villa", 4, "2 queen beds", 4_200_000, 4, ["AIR_CONDITIONING", "SEA_VIEW", "BATHTUB", "KITCHENETTE"],
       "")]),

    ("Cần Thơ Mekong Riverside", "Cần Thơ", "2 Hai Bà Trưng, Ninh Kiều", 4, "14:00", "12:00", "8", "5", "flex",
     "Riverside hotel with early-morning boat trips to the Cái Răng floating market.",
     ["WIFI", "POOL", "RESTAURANT", "BREAKFAST", "FRONT_DESK_24H"],
     [("Superior River View", 2, "1 queen bed", 1_100_000, 14, ["AIR_CONDITIONING", "TV"], ""),
      ("Deluxe Family", 4, "1 king bed + 2 single beds", 1_900_000, 4, ["AIR_CONDITIONING", "TV", "BALCONY"], "")]),

    # Not bookable: show the admin approval queue and the suspended state.
    ("Ninh Bình Tam Cốc Retreat", "Ninh Bình", "Thôn Văn Lâm, Ninh Hải", 3, "14:00", "12:00", "8", "5", "flex",
     "Bungalows among limestone karsts and rice fields, next to the Tam Cốc boat pier.",
     ["WIFI", "POOL", "BREAKFAST", "PARKING"],
     [("Rice Field Bungalow", 2, "1 queen bed", 1_200_000, 8, ["AIR_CONDITIONING", "BALCONY"], "")]),
    ("Mũi Né Sand Dune Resort", "Phan Thiết", "68 Nguyễn Đình Chiểu, Hàm Tiến", 4, "14:00", "12:00", "8", "5",
     "resort", "Kitesurfing resort on Mũi Né beach; jeep tours to the white sand dunes at sunrise.",
     ["WIFI", "POOL", "RESTAURANT", "BREAKFAST"],
     [("Garden Deluxe", 2, "1 king bed", 1_300_000, 10, ["AIR_CONDITIONING", "BALCONY"], "")]),
    ("Bãi Cháy Budget Hotel", "Hạ Long", "102 Vườn Đào, Bãi Cháy", 2, "14:00", "12:00", "8", "0", "hostel",
     "Simple rooms near the night market.", ["WIFI"],
     [("Standard Double", 2, "1 double bed", 400_000, 10, ["AIR_CONDITIONING"], "")]),
]
STATUS_OVERRIDE = {29: "DRAFT", 30: "DRAFT", 31: "SUSPENDED"}

REVIEWS = {
    5: ["Tuyệt vời! Phòng sạch, nhân viên rất nhiệt tình. Chắc chắn sẽ quay lại.",
        "Vị trí đẹp, view xuất sắc, bữa sáng nhiều món. Rất đáng tiền.",
        "Perfect stay — spotless room, friendly staff and a great breakfast.",
        "Gia đình mình rất hài lòng, bé thích hồ bơi lắm. Check-in nhanh gọn.",
        "One of the best hotels we stayed at in Vietnam. Highly recommended!",
        "Nhân viên lễ tân hỗ trợ đặt tour rất chu đáo, phòng thơm và yên tĩnh."],
    4: ["Khách sạn tốt, phòng rộng, chỉ có điều wifi hơi yếu buổi tối.",
        "Great location and comfortable bed. Breakfast could have more variety.",
        "Nhìn chung ổn, giá hợp lý. Cách âm chưa tốt lắm.",
        "Nice staff and clean room; the pool was a bit crowded at weekends.",
        "Phòng đẹp như hình, nhưng check-in phải chờ khoảng 20 phút."],
    3: ["Tạm ổn so với giá tiền, phòng hơi cũ.",
        "Average. Location is good but the room smelled of smoke.",
        "Bữa sáng đơn giản, điều hòa hơi ồn. Nhân viên thân thiện."],
    2: ["Phòng không giống ảnh, máy nước nóng chập chờn.",
        "Noisy at night and the bathroom wasn't clean on arrival."],
    1: ["Rất thất vọng, đặt phòng hướng biển nhưng được xếp phòng không cửa sổ."],
}
HIDE = ("Contains offensive language", "Đồ dở tệ, lừa đảo!!! Ai ở đây là ngu.")
REQUESTS = [None, None, None, None, "Late check-in around 22:00, please.", "Phòng tầng cao nếu có thể.",
            "Celebrating an anniversary — a small cake would be lovely.", "Cần giường phụ cho bé 4 tuổi.",
            "Non-smoking room please.", "Đón sân bay lúc 14h.", "Twin beds instead of a double if possible."]
CANCEL_REASONS = ["Cancelled by customer", "Change of travel plans", "Flight was cancelled",
                  "Đổi lịch công tác", "Found a closer hotel"]


# ------------------------------------------------------------------------------------------------- generation ---

@dataclass
class Booking:
    id: str
    code: str
    user: dict
    hotel: dict
    lines: list
    check_in: date
    nights: int
    status: str
    created_at: datetime
    adults: int
    children: int
    refund_eligible: bool = None
    payment_paid: bool = False
    payment_failed: bool = False
    cancelled_at: datetime = None
    cancel_reason: str = None
    events: list = field(default_factory=list)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="load the SQL into the compose Postgres")
    ap.add_argument("--container", default="stayhotel-postgres-1")
    args = ap.parse_args()

    today = datetime.now(VN).date()
    now = datetime.now(VN).replace(microsecond=0)
    pw_hash = bcrypt.hashpw(PASSWORD.encode(), bcrypt.gensalt(12)).decode().replace("$2b$", "$2a$")

    # ---- users
    def person(key, role, email, name, created):
        return {"id": uid("user", key), "email": email, "password_hash": pw_hash, "full_name": name,
                "phone": "09" + "".join(rnd.choice("0123456789") for _ in range(8)), "status": "ACTIVE",
                "created_at": created, "updated_at": created, "roles": role}

    admin = person("admin", ["CUSTOMER", "ADMIN"], "admin.demo@stayhotel.local", "Demo Admin", now - timedelta(days=400))
    managers = [person(f"mgr{i}", ["CUSTOMER", "HOTEL_MANAGER"], f"manager.{slug}@stayhotel.local", name,
                       now - timedelta(days=380 - i))
                for i, (slug, name) in enumerate([("north", "Trần Minh Quân"), ("south", "Lê Thị Thu Hà"),
                                                  ("central", "Phạm Đức Hải"), ("islands", "Võ Ngọc Yến")])]
    customers, used = [], set()
    for i in range(40):
        while True:
            last, mid, first = rnd.choice(LAST), rnd.choice(MIDDLE), rnd.choice(FIRST)
            if (last, first) not in used:
                used.add((last, first))
                break
        email = f"{search_key(first).replace(' ', '')}.{search_key(last)}{rnd.randint(80, 99) if i % 3 else ''}@example.com"
        customers.append(person(f"cust{i}", ["CUSTOMER"], email, f"{last} {mid} {first}",
                                now - timedelta(days=rnd.randint(30, 360), hours=rnd.randint(0, 23))))
    disabled = person("cust-disabled", ["CUSTOMER"], "spam.account@example.com", "Spam Account",
                      now - timedelta(days=90))
    disabled["status"] = "DISABLED"
    users = [admin, *managers, *customers, disabled]

    # ---- hotels
    region = {"Hà Nội": 0, "Sa Pa": 0, "Hạ Long": 0, "Ninh Bình": 0, "Hồ Chí Minh": 1, "Vũng Tàu": 1, "Cần Thơ": 1,
              "Phan Thiết": 1, "Đà Nẵng": 2, "Hội An": 2, "Huế": 2, "Quy Nhơn": 2, "Đà Lạt": 1, "Nha Trang": 3,
              "Phú Quốc": 3}
    hotels, rooms, images, h_amen, r_amen, h_mgrs = [], [], [], [], [], []
    for i, (name, city, addr, stars, cin, cout, tax, fee, pol, desc, amen, rts) in enumerate(H):
        created = now - timedelta(days=370 - i * 3)
        status = STATUS_OVERRIDE.get(i, "ACTIVE")
        h = {"id": uid("hotel", i), "name": name, "name_key": search_key(name), "description": desc, "address": addr,
             "city": city, "city_key": search_key(city), "country_code": "VN", "star_rating": stars,
             "time_zone": "Asia/Ho_Chi_Minh", "check_in_time": time.fromisoformat(cin),
             "check_out_time": time.fromisoformat(cout), "cancellation_policy": POLICY[pol], "status": status,
             "approved_at": None if status == "DRAFT" else created + timedelta(days=1), "avg_rating": None,
             "review_count": 0, "rating_version": 0, "tax_rate_percent": Decimal(tax),
             "service_fee_percent": Decimal(fee), "created_at": created, "updated_at": created, "rooms": []}
        hotels.append(h)
        for c in amen:
            h_amen.append({"hotel_id": h["id"], "code": c})
        for n in range(4 if stars >= 4 else 3):
            images.append({"id": uid("img", i, n), "hotel_id": h["id"], "sort_order": n, "created_at": created,
                           "url": f"https://picsum.photos/seed/stay-{search_key(name).replace(' ', '-')}-{n}/1200/800",
                           "caption": ["Exterior", "Room", "Lobby", "Pool & facilities"][n]})
        for j, (rname, cap, bed, price, units, ramen, rdesc) in enumerate(rts):
            rt = {"id": uid("room", i, j), "hotel_id": h["id"], "name": rname, "description": rdesc or None,
                  "capacity": cap, "bed_description": bed, "base_price": price, "total_units": units,
                  "status": "ACTIVE", "created_at": created, "updated_at": created}
            rooms.append(rt)
            h["rooms"].append(rt)
            for c in ramen:
                r_amen.append({"room_type_id": rt["id"], "code": c})
        mgr = managers[region[city]]
        h_mgrs.append({"hotel_id": h["id"], "user_id": mgr["id"], "assigned_by": admin["id"], "assigned_at": created})
    # one retired room type, to show INACTIVE in the manager UI
    hotels[0]["rooms"][-1]["status"] = "INACTIVE"
    bookable = [h for h in hotels if h["status"] == "ACTIVE"]
    weights = [3 if h["star_rating"] >= 4 else 2 for h in bookable]

    # ---- bookings
    inventory, bookings, codes = {}, [], set()

    def fits(lines, check_in, nights):
        return all(inventory.get((rt["id"], check_in + timedelta(d)), 0) + qty <= rt["total_units"]
                   for rt, qty in lines for d in range(nights))

    def reserve(lines, check_in, nights):
        for rt, qty in lines:
            for d in range(nights):
                k = (rt["id"], check_in + timedelta(d))
                inventory[k] = inventory.get(k, 0) + qty

    plan = ([("past", 150)] + [("inhouse", 12)] + [("future", 100)])
    n = 0
    for kind, count in plan:
        made = 0
        while made < count:
            h = rnd.choices(bookable, weights)[0]
            active_rooms = [r for r in h["rooms"] if r["status"] == "ACTIVE"]
            nights = rnd.choices([1, 2, 3, 4, 5, 7], [20, 30, 25, 12, 8, 5])[0]
            if kind == "past":
                check_in = today - timedelta(days=rnd.randint(nights + 1, 300))
            elif kind == "inhouse":
                nights = max(nights, 2)
                check_in = today - timedelta(days=rnd.randint(0, nights - 1))
            else:
                check_in = today + timedelta(days=rnd.choices([rnd.randint(1, 14), rnd.randint(15, 60),
                                                               rnd.randint(61, 150)], [3, 4, 2])[0])
            rt = rnd.choice(active_rooms)
            lines = [(rt, rnd.choices([1, 2, 3], [75, 20, 5])[0])]
            if len(active_rooms) > 1 and rnd.random() < 0.12:
                other = rnd.choice([r for r in active_rooms if r is not rt])
                lines.append((other, 1))
            cap = sum(r["capacity"] * q_ for r, q_ in lines)
            adults = rnd.randint(max(1, sum(q_ for _, q_ in lines)), max(1, min(cap, 2 * sum(q_ for _, q_ in lines))))
            children = rnd.choice([0, 0, 0, 1, 2]) if cap - adults > 0 else 0
            children = min(children, cap - adults)

            lead = rnd.choices([rnd.randint(1, 7), rnd.randint(8, 30), rnd.randint(31, 90)], [3, 5, 2])[0]
            created = at(check_in - timedelta(days=lead), rnd.randint(7, 22), rnd.randint(0, 59))
            if created > now - timedelta(hours=1):
                created = now - timedelta(hours=rnd.randint(2, 72))
            roll = rnd.random()
            if kind == "past":
                status = "COMPLETED" if roll < 0.74 else "CANCELLED" if roll < 0.90 else "EXPIRED"
            elif kind == "inhouse":
                status = "CONFIRMED"
            else:
                status = "CONFIRMED" if roll < 0.78 else "CANCELLED" if roll < 0.90 else "EXPIRED"
            if status in ("COMPLETED", "CONFIRMED") and not fits(lines, check_in, nights):
                continue
            while True:
                code = "SH-" + check_in.strftime("%y%m%d") + "-" + "".join(
                    rnd.choice("0123456789ABCDEFGHJKMNPQRSTVWXYZ") for _ in range(8))
                if code not in codes:
                    codes.add(code)
                    break
            user = rnd.choice(customers[:30] if rnd.random() < 0.8 else customers)
            b = Booking(uid("booking", n), code, user, h, lines, check_in, nights, status, created, adults, children)
            if status in ("COMPLETED", "CONFIRMED"):
                reserve(lines, check_in, nights)
                b.payment_paid = True
            elif status == "EXPIRED":
                b.payment_failed = rnd.random() < 0.4
            else:  # CANCELLED: mostly after payment, a few while still pending
                paid_first = rnd.random() < 0.85
                b.payment_paid = paid_first
                deadline = at(check_in, h["check_in_time"].hour) - timedelta(hours=48)
                start = created + timedelta(minutes=15)
                end = min(at(check_in, 9), now - timedelta(minutes=30))
                if not paid_first:  # a pending hold can only be cancelled within its 15-minute window
                    end = created + timedelta(minutes=14)
                    start = created + timedelta(minutes=2)
                b.cancelled_at = (start + (end - start) * rnd.random() if end > start else start).replace(microsecond=0)
                b.refund_eligible = (b.cancelled_at <= deadline) if paid_first else None
                b.cancel_reason = rnd.choice(CANCEL_REASONS)
            bookings.append(b)
            made += 1
            n += 1

    # ---- derive rows per booking
    b_rows, br_rows, hist, pay, pay_hist, refunds, sess, sref, webhooks = [], [], [], [], [], [], [], [], []
    contacts, notes = [], []
    stays, reviews = [], []
    for b in bookings:
        h = b.hotel
        priced = [(rt, qty, rt["base_price"] * qty * b.nights) for rt, qty in b.lines]
        subtotal = sum(p[2] for p in priced)
        fee = pct(subtotal, h["service_fee_percent"])
        tax = pct(subtotal + fee, h["tax_rate_percent"])
        total = subtotal + fee + tax
        check_out = b.check_in + timedelta(b.nights)
        expires = b.created_at + timedelta(minutes=15)
        u = b.user
        def hist_add(frm, to, reason, actor, ts):
            hist.append({"id": uid("hist", b.id, f"{frm}-{to}"), "booking_id": b.id, "from_status": frm,
                         "to_status": to, "reason": reason, "actor": actor, "created_at": ts})

        hist_add(None, "PENDING_PAYMENT", "Booking created", u["id"], b.created_at)
        contact = {"booking_id": b.id, "user_id": u["id"], "contact_name": u["full_name"],
                   "contact_email": u["email"], "booking_code": b.code, "hotel_name": h["name"],
                   "check_in": b.check_in, "check_out": check_out, "total_amount": total, "currency_code": "VND",
                   "created_at": b.created_at}
        contacts.append(contact)
        stay = f"{h['name']}, {b.check_in} → {check_out}"
        link = f"{APP_URL}/bookings/{b.id}"
        hi = f"Hi {u['full_name']},\n\n"

        def note(kind, title, body, ts):
            notes.append({"id": uid("note", b.id, kind), "event_id": uid("event", b.id, kind), "user_id": u["id"],
                          "booking_id": b.id, "type": kind, "title": title, "body": body, "email_to": u["email"],
                          "email_status": "SENT", "email_attempts": 1, "next_attempt_at": None, "last_error": None,
                          "read_at": ts + timedelta(hours=rnd.randint(1, 30)) if ts < now - timedelta(days=2)
                          and rnd.random() < 0.7 else None, "created_at": ts + timedelta(seconds=3)})

        note("BOOKING_CREATED", f"Booking {b.code} received",
             hi + f"We are holding your rooms at {stay} for 15 minutes. Total: {vnd(total)}. "
                  f"Complete the payment to confirm:\n{link}", b.created_at)

        payment_id, confirmed_at, updated = None, None, b.created_at
        if b.payment_paid or b.payment_failed:
            payment_id = uid("payment", b.id)
            sid = "cs_" + hexid(24, "sess", b.id)
            p_created = b.created_at + timedelta(minutes=1)
            p_done = p_created + timedelta(minutes=rnd.randint(1, 8), seconds=rnd.randint(0, 59))
            ok = b.payment_paid
            pay.append({"id": payment_id, "booking_id": b.id, "user_id": u["id"], "provider": "SANDBOX",
                        "provider_reference": sid, "idempotency_key": uid("pay-idem", b.id), "amount": total,
                        "currency_code": "VND", "status": "SUCCEEDED" if ok else "FAILED",
                        "checkout_url": f"{APP_URL}/sandbox-gateway/checkout/{sid}",
                        "failure_reason": None if ok else "Card declined (sandbox)", "needs_review": False,
                        "succeeded_at": p_done if ok else None, "created_at": p_created, "updated_at": p_done})
            pay_hist.append({"id": uid("phist", payment_id, 0), "payment_id": payment_id, "from_status": None,
                             "to_status": "PENDING", "reason": "Checkout started", "created_at": p_created})
            pay_hist.append({"id": uid("phist", payment_id, 1), "payment_id": payment_id, "from_status": "PENDING",
                             "to_status": "SUCCEEDED" if ok else "FAILED",
                             "reason": "Provider confirmed payment" if ok else "Card declined (sandbox)",
                             "created_at": p_done})
            evt = "evt_" + hexid(24, "evt", b.id)
            sess.append({"id": sid, "merchant_reference": payment_id, "amount": total, "currency_code": "VND",
                         "return_url": f"{link}?payment=return", "status": "PAID" if ok else "DECLINED",
                         "webhook_event_id": evt, "webhook_delivered": True, "created_at": p_created,
                         "completed_at": p_done})
            webhooks.append({"provider": "SANDBOX", "event_id": evt,
                             "event_type": "payment.succeeded" if ok else "payment.failed", "received_at": p_done})
            if ok:
                confirmed_at = p_done + timedelta(seconds=2)
                hist_add("PENDING_PAYMENT", "CONFIRMED", f"Payment {payment_id} received", "SYSTEM", confirmed_at)
                note("BOOKING_CONFIRMED", f"Booking {b.code} confirmed",
                     hi + f"Your stay at {stay} is confirmed. Booking code: {b.code}.\n{link}", confirmed_at)
                updated = confirmed_at
            else:
                note("PAYMENT_FAILED", f"Payment for {b.code} failed",
                     hi + f"Your payment was declined. Your rooms are still held for a short time; "
                          f"you can try again here:\n{link}", p_done)
                payment_id = None  # bookings.payment_id only links the payment that confirmed it
            if not ok:
                updated = p_done

        refund_status = "NONE"
        if b.status == "EXPIRED":
            exp_at = expires + timedelta(seconds=rnd.randint(5, 50))
            hist_add("PENDING_PAYMENT", "EXPIRED", "Payment not received within the hold time", "SYSTEM", exp_at)
            note("BOOKING_EXPIRED", f"Booking {b.code} expired",
                 hi + f"We did not receive the payment in time, so the rooms at {stay} were released. "
                      f"You can search again at any time.", exp_at)
            updated = exp_at
        elif b.status == "COMPLETED":
            done = at(check_out, h["check_out_time"].hour, 5)
            hist_add("CONFIRMED", "COMPLETED", "Stay completed", "SYSTEM", done)
            note("STAY_COMPLETED", "How was your stay?",
                 hi + f"Thank you for staying at {h['name']}. You can now leave a review:\n{link}", done)
            updated = done
            stays.append({"booking_id": b.id, "user_id": u["id"], "hotel_id": h["id"], "check_out_date": check_out,
                          "recorded_at": done + timedelta(seconds=4)})
            if rnd.random() < 0.72:
                weights_by_stars = {5: [1, 1, 3, 10, 22], 4: [1, 2, 5, 14, 12], 3: [2, 3, 8, 10, 6],
                                    2: [3, 4, 8, 9, 5]}  # for ratings 1..5
                rating = rnd.choices([1, 2, 3, 4, 5], weights_by_stars[h["star_rating"]])[0]
                r_at = done + timedelta(days=rnd.randint(0, 10), hours=rnd.randint(1, 12))
                if r_at > now:
                    r_at = now - timedelta(minutes=rnd.randint(5, 600))
                reviews.append({"id": uid("review", b.id), "booking_id": b.id, "user_id": u["id"], "hotel_id": h["id"],
                                "rating": rating, "comment": rnd.choice(REVIEWS[rating]) if rnd.random() < 0.9 else None,
                                "status": "VISIBLE", "hidden_reason": None, "created_at": r_at, "updated_at": r_at})
        elif b.status == "CANCELLED":
            frm = "CONFIRMED" if b.payment_paid else "PENDING_PAYMENT"
            hist_add(frm, "CANCELLED", b.cancel_reason, u["id"], b.cancelled_at)
            body = hi + f"Your booking at {stay} was cancelled."
            if b.refund_eligible:
                body += f" A refund of {vnd(total)} is on its way; we will tell you when it is completed."
            note("BOOKING_CANCELLED", f"Booking {b.code} cancelled", body, b.cancelled_at)
            updated = b.cancelled_at
            if b.refund_eligible:
                pid = uid("payment", b.id)
                r_req = b.cancelled_at + timedelta(seconds=3)
                r_done = r_req + timedelta(seconds=rnd.randint(5, 90))
                p = next(x for x in pay if x["id"] == pid)
                p["status"], p["updated_at"] = "REFUNDED", r_done
                reason = "Booking cancelled within the free-cancellation period"
                pay_hist.append({"id": uid("phist", pid, 2), "payment_id": pid, "from_status": "SUCCEEDED",
                                 "to_status": "REFUND_PENDING", "reason": reason, "created_at": r_req})
                pay_hist.append({"id": uid("phist", pid, 3), "payment_id": pid, "from_status": "REFUND_PENDING",
                                 "to_status": "REFUNDED", "reason": "Refund confirmed by provider",
                                 "created_at": r_done})
                rid = uid("refund", b.id)
                revt = "evt_" + hexid(24, "revt", b.id)
                refunds.append({"id": rid, "payment_id": pid, "amount": total, "reason": reason,
                                "status": "SUCCEEDED", "provider_reference": revt, "attempts": 1,
                                "last_attempt_at": r_req, "created_at": r_req, "completed_at": r_done})
                sref.append({"idempotency_key": rid, "session_id": p["provider_reference"], "amount": total,
                             "outcome": "SUCCEEDED", "webhook_event_id": revt, "webhook_delivered": True,
                             "created_at": r_req})
                webhooks.append({"provider": "SANDBOX", "event_id": revt, "event_type": "refund.succeeded",
                                 "received_at": r_done})
                note("REFUND_COMPLETED", f"Refund for {b.code} completed",
                     hi + f"{vnd(total)} has been refunded to your payment method.", r_done)
                refund_status = "REFUNDED"
                updated = r_done + timedelta(seconds=1)

        b_rows.append({"id": b.id, "booking_code": b.code, "user_id": u["id"], "hotel_id": h["id"],
                       "hotel_name": h["name"], "hotel_time_zone": h["time_zone"],
                       "check_in_time": h["check_in_time"], "check_in": b.check_in, "check_out": check_out,
                       "nights": b.nights, "adults": b.adults, "children": b.children, "status": b.status,
                       "subtotal_amount": subtotal, "service_fee_percent": h["service_fee_percent"],
                       "fee_amount": fee, "tax_rate_percent": h["tax_rate_percent"], "tax_amount": tax,
                       "discount_amount": 0, "total_amount": total, "currency_code": "VND",
                       "contact_name": u["full_name"], "contact_email": u["email"], "contact_phone": u["phone"],
                       "special_requests": rnd.choice(REQUESTS), "expires_at": expires,
                       "confirmed_at": confirmed_at, "cancelled_at": b.cancelled_at,
                       "cancel_reason": b.cancel_reason, "refund_eligible": b.refund_eligible,
                       "idempotency_key": uid("idem", b.id),
                       "request_hash": hashlib.sha256(("req:" + b.id).encode()).hexdigest(),
                       "created_at": b.created_at, "updated_at": updated, "payment_id": payment_id,
                       "refund_status": refund_status})
        for rt, qty, line in priced:
            br_rows.append({"id": uid("bline", b.id, rt["id"]), "booking_id": b.id, "room_type_id": rt["id"],
                            "room_type_name": rt["name"], "quantity": qty, "unit_price": rt["base_price"],
                            "nights": b.nights, "line_subtotal": line})

    # hide a couple of reviews (moderation demo)
    for r in reviews[3:5]:
        r["status"], r["hidden_reason"], r["comment"] = "HIDDEN", HIDE[0], HIDE[1]
        r["updated_at"] = r["created_at"] + timedelta(hours=6)
    stats = []
    for h in hotels:
        vis = [r["rating"] for r in reviews if r["hotel_id"] == h["id"] and r["status"] == "VISIBLE"]
        if not [r for r in reviews if r["hotel_id"] == h["id"]]:
            continue
        avg = (Decimal(sum(vis)) / len(vis)).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP) if vis else None
        h["avg_rating"], h["review_count"], h["rating_version"] = avg, len(vis), 1
        stats.append({"hotel_id": h["id"], "avg_rating": avg, "review_count": len(vis), "version": 1,
                      "updated_at": now})

    # ------------------------------------------------------------------------------------------------ SQL out ---
    OUT.mkdir(exist_ok=True)
    head = "-- Generated by scripts/demo-data/generate.py on {} — demo data only, safe to re-run.\nBEGIN;\n\n"
    user_ids, hotel_ids = [x["id"] for x in users], [h["id"] for h in hotels]
    booking_ids, room_ids = [b["id"] for b in b_rows], [r["id"] for r in rooms]
    files = {}

    files["auth_db"] = (
        f"DELETE FROM users WHERE id IN {in_list(user_ids)} OR email IN {in_list([x['email'] for x in users])};\n\n"
        + insert("users", ["id", "email", "password_hash", "full_name", "phone", "status", "created_at",
                           "updated_at"], users)
        + insert("user_roles", ["user_id", "role"], [{"user_id": x["id"], "role": r} for x in users for r in x["roles"]]))

    am = "(SELECT id FROM amenities WHERE code = {})"
    files["hotel_db"] = (
        f"DELETE FROM hotels WHERE id IN {in_list(hotel_ids)};\n\n"
        + insert("hotels", ["id", "name", "name_key", "description", "address", "city", "city_key", "country_code",
                            "star_rating", "time_zone", "check_in_time", "check_out_time", "cancellation_policy",
                            "status", "approved_at", "avg_rating", "review_count", "rating_version",
                            "tax_rate_percent", "service_fee_percent", "created_at", "updated_at"], hotels)
        + insert("hotel_images", ["id", "hotel_id", "url", "caption", "sort_order", "created_at"], images)
        + insert("room_types", ["id", "hotel_id", "name", "description", "capacity", "bed_description",
                                "base_price", "total_units", "status", "created_at", "updated_at"], rooms)
        + "INSERT INTO hotel_amenities (hotel_id, amenity_id) VALUES\n"
        + ",\n".join(f"  ({q(a['hotel_id'])}, {am.format(q(a['code']))})" for a in h_amen) + ";\n\n"
        + "INSERT INTO room_type_amenities (room_type_id, amenity_id) VALUES\n"
        + ",\n".join(f"  ({q(a['room_type_id'])}, {am.format(q(a['code']))})" for a in r_amen) + ";\n\n"
        + insert("hotel_managers", ["hotel_id", "user_id", "assigned_by", "assigned_at"], h_mgrs))

    inv_rows = [{"room_type_id": k[0], "stay_date": k[1], "reserved_units": v} for k, v in sorted(inventory.items())]
    files["booking_db"] = (
        "-- Give back the units held by a previous run of this script, then replace its bookings.\n"
        "UPDATE room_type_inventory i SET reserved_units = greatest(i.reserved_units - x.units, 0)\n"
        "FROM (SELECT br.room_type_id, d::date AS stay_date, sum(br.quantity) AS units\n"
        "      FROM bookings b JOIN booking_rooms br ON br.booking_id = b.id,\n"
        "           generate_series(b.check_in, b.check_out - 1, interval '1 day') d\n"
        f"      WHERE b.id IN {in_list(booking_ids)} AND b.status IN ('CONFIRMED', 'COMPLETED')\n"
        "      GROUP BY 1, 2) x\n"
        "WHERE i.room_type_id = x.room_type_id AND i.stay_date = x.stay_date;\n"
        f"DELETE FROM bookings WHERE id IN {in_list(booking_ids)} OR booking_code IN {in_list([b['booking_code'] for b in b_rows])};\n\n"
        + insert("bookings", list(b_rows[0].keys()), b_rows)
        + insert("booking_rooms", list(br_rows[0].keys()), br_rows)
        + insert("booking_status_history", ["id", "booking_id", "from_status", "to_status", "reason", "actor",
                                            "created_at"], sorted(hist, key=lambda x: x["created_at"]))
        + insert("room_type_inventory", ["room_type_id", "stay_date", "reserved_units"], inv_rows).rstrip(";\n")
        + "\nON CONFLICT (room_type_id, stay_date)\n"
          "DO UPDATE SET reserved_units = room_type_inventory.reserved_units + EXCLUDED.reserved_units;\n\n")

    pay_ids = [p["id"] for p in pay]
    files["payment_db"] = (
        f"DELETE FROM sandbox_refunds WHERE session_id IN {in_list([s['id'] for s in sess])};\n"
        f"DELETE FROM sandbox_sessions WHERE id IN {in_list([s['id'] for s in sess])};\n"
        f"DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE booking_id IN {in_list(booking_ids)});\n"
        f"DELETE FROM payments WHERE booking_id IN {in_list(booking_ids)} OR id IN {in_list(pay_ids)};\n"
        f"DELETE FROM webhook_events WHERE provider = 'SANDBOX' AND event_id IN {in_list([w['event_id'] for w in webhooks])};\n\n"
        + insert("payments", list(pay[0].keys()), pay)
        + insert("payment_status_history", list(pay_hist[0].keys()), pay_hist)
        + insert("refunds", list(refunds[0].keys()), refunds)
        + insert("sandbox_sessions", list(sess[0].keys()), sess)
        + insert("sandbox_refunds", list(sref[0].keys()), sref)
        + insert("webhook_events", list(webhooks[0].keys()), webhooks))

    files["review_db"] = (
        f"DELETE FROM reviews WHERE booking_id IN {in_list(booking_ids)};\n"
        f"DELETE FROM eligible_stays WHERE booking_id IN {in_list(booking_ids)};\n"
        f"DELETE FROM hotel_rating_stats WHERE hotel_id IN {in_list(hotel_ids)};\n\n"
        + insert("eligible_stays", list(stays[0].keys()), stays)
        + insert("reviews", list(reviews[0].keys()), reviews)
        + insert("hotel_rating_stats", list(stats[0].keys()), stats))

    files["notification_db"] = (
        f"DELETE FROM notifications WHERE booking_id IN {in_list(booking_ids)};\n"
        f"DELETE FROM booking_contacts WHERE booking_id IN {in_list(booking_ids)};\n\n"
        + insert("booking_contacts", list(contacts[0].keys()), contacts)
        + insert("notifications", list(notes[0].keys()), notes))

    for i, (db, body) in enumerate(files.items(), 1):
        (OUT / f"{i:02d}_{db}.sql").write_text(head.format(now.isoformat()) + body + "COMMIT;\n", encoding="utf-8")

    by = {}
    for b in b_rows:
        by[b["status"]] = by.get(b["status"], 0) + 1
    print(f"Wrote {len(files)} files to {OUT}")
    print(f"  users {len(users)}  hotels {len(hotels)}  room types {len(rooms)}  images {len(images)}")
    print(f"  bookings {len(b_rows)} {by}  payments {len(pay)}  refunds {len(refunds)}")
    print(f"  reviews {len(reviews)}  notifications {len(notes)}")

    if args.apply:
        for f in sorted(OUT.glob("*.sql")):
            db = f.stem.split("_", 1)[1]
            res = subprocess.run(["docker", "exec", "-i", args.container, "psql", "-U", "postgres", "-d", db,
                                  "-v", "ON_ERROR_STOP=1", "-q"], input=f.read_bytes(), capture_output=True)
            if res.returncode:
                sys.exit(f"{f.name} failed:\n{res.stderr.decode(errors='replace')}")
            print(f"loaded {f.name}")
        print(f"\nLogin with any demo account, password {PASSWORD}:")
        print("  admin.demo@stayhotel.local (ADMIN)   manager.north|south|central|islands@stayhotel.local")
        print(f"  customers, e.g. {customers[0]['email']}, {customers[1]['email']}")


if __name__ == "__main__":
    main()
