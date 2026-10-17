-- Design ref: hotel-stayed-report.docx 4.4.2 (hotels), 4.4.3 (room_types), 4.4.11 (amenities, images), 4.7 (indexes)
-- Physical rooms (4.4.4) are intentionally not modelled: inventory is per room type (DECISIONS P-01 default / D-015).

CREATE TABLE hotels (
    id                  UUID          PRIMARY KEY,
    name                VARCHAR(200)  NOT NULL,
    name_key            VARCHAR(200)  NOT NULL,  -- lower-case, diacritics removed (search)
    description         TEXT,
    address             VARCHAR(500)  NOT NULL,
    city                VARCHAR(100)  NOT NULL,
    city_key            VARCHAR(100)  NOT NULL,  -- lower-case, diacritics removed (search)
    country_code        CHAR(2)       NOT NULL,
    star_rating         SMALLINT,
    time_zone           VARCHAR(50)   NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    check_in_time       TIME          NOT NULL DEFAULT '14:00',
    check_out_time      TIME          NOT NULL DEFAULT '12:00',
    cancellation_policy VARCHAR(2000),
    status              VARCHAR(20)   NOT NULL,
    approved_at         TIMESTAMPTZ,
    avg_rating          NUMERIC(2, 1),
    review_count        INTEGER       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_hotels_status CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'SUSPENDED')),
    CONSTRAINT chk_hotels_star_rating CHECK (star_rating BETWEEN 1 AND 5),
    CONSTRAINT chk_hotels_country_code CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT chk_hotels_avg_rating CHECK (avg_rating BETWEEN 1 AND 5),
    CONSTRAINT chk_hotels_review_count CHECK (review_count >= 0),
    CONSTRAINT chk_hotels_active_is_approved CHECK (status <> 'ACTIVE' OR approved_at IS NOT NULL)
);
-- Public search always filters on status = ACTIVE; city is the main predicate.
CREATE INDEX idx_hotels_active_city ON hotels (city_key) WHERE status = 'ACTIVE';
CREATE INDEX idx_hotels_status_created ON hotels (status, created_at DESC);

CREATE TABLE hotel_images (
    id         UUID          PRIMARY KEY,
    hotel_id   UUID          NOT NULL REFERENCES hotels (id) ON DELETE CASCADE,
    url        VARCHAR(1000) NOT NULL,
    caption    VARCHAR(200),
    sort_order INTEGER       NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL,
    CONSTRAINT chk_hotel_images_https CHECK (url LIKE 'https://%')
);
CREATE INDEX idx_hotel_images_hotel ON hotel_images (hotel_id, sort_order);

CREATE TABLE amenities (
    id       UUID         PRIMARY KEY,
    code     VARCHAR(40)  NOT NULL,
    name     VARCHAR(100) NOT NULL,
    category VARCHAR(10)  NOT NULL,
    CONSTRAINT uq_amenities_code UNIQUE (code),
    CONSTRAINT chk_amenities_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT chk_amenities_category CHECK (category IN ('HOTEL', 'ROOM'))
);

CREATE TABLE hotel_amenities (
    hotel_id   UUID NOT NULL REFERENCES hotels (id) ON DELETE CASCADE,
    amenity_id UUID NOT NULL REFERENCES amenities (id),
    PRIMARY KEY (hotel_id, amenity_id)
);
-- Amenity filter looks up hotels by amenity.
CREATE INDEX idx_hotel_amenities_amenity ON hotel_amenities (amenity_id, hotel_id);

CREATE TABLE room_types (
    id              UUID          PRIMARY KEY,
    hotel_id        UUID          NOT NULL REFERENCES hotels (id) ON DELETE CASCADE,
    name            VARCHAR(150)  NOT NULL,
    description     VARCHAR(2000),
    capacity        SMALLINT      NOT NULL,
    bed_description VARCHAR(200),
    base_price      NUMERIC(12, 2) NOT NULL,
    currency_code   CHAR(3)       NOT NULL DEFAULT 'VND',
    total_units     INTEGER       NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_room_types_hotel_name UNIQUE (hotel_id, name),
    CONSTRAINT chk_room_types_capacity CHECK (capacity BETWEEN 1 AND 20),
    CONSTRAINT chk_room_types_price CHECK (base_price > 0),
    -- VND only, whole numbers (DECISIONS P-03 default). Relax with a new migration when multi-currency arrives.
    CONSTRAINT chk_room_types_currency CHECK (currency_code = 'VND' AND base_price = trunc(base_price)),
    CONSTRAINT chk_room_types_units CHECK (total_units >= 0),
    CONSTRAINT chk_room_types_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE INDEX idx_room_types_hotel_status ON room_types (hotel_id, status);

CREATE TABLE room_type_amenities (
    room_type_id UUID NOT NULL REFERENCES room_types (id) ON DELETE CASCADE,
    amenity_id   UUID NOT NULL REFERENCES amenities (id),
    PRIMARY KEY (room_type_id, amenity_id)
);

-- Which HOTEL_MANAGER users may manage which hotel (DECISIONS D-013). user_id references auth-service logically.
CREATE TABLE hotel_managers (
    hotel_id    UUID        NOT NULL REFERENCES hotels (id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL,
    assigned_by UUID        NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (hotel_id, user_id)
);
CREATE INDEX idx_hotel_managers_user ON hotel_managers (user_id);

INSERT INTO amenities (id, code, name, category) VALUES
    ('00000000-0000-0000-0000-000000000101', 'WIFI', 'Free Wi-Fi', 'HOTEL'),
    ('00000000-0000-0000-0000-000000000102', 'PARKING', 'Parking', 'HOTEL'),
    ('00000000-0000-0000-0000-000000000103', 'POOL', 'Swimming pool', 'HOTEL'),
    ('00000000-0000-0000-0000-000000000104', 'GYM', 'Fitness center', 'HOTEL'),
    ('00000000-0000-0000-0000-000000000105', 'SPA', 'Spa', 'HOTEL'),
    ('00000000-0000-0000-0000-000000000106', 'RESTAURANT', 'Restaurant', 'HOTEL'),
    ('00000000-0000-0000-0000-000000000107', 'BREAKFAST', 'Breakfast available', 'HOTEL'),
    ('00000000-0000-0000-0000-000000000108', 'AIRPORT_SHUTTLE', 'Airport shuttle', 'HOTEL'),
    ('00000000-0000-0000-0000-000000000109', 'PET_FRIENDLY', 'Pets allowed', 'HOTEL'),
    ('00000000-0000-0000-0000-000000000110', 'FRONT_DESK_24H', '24-hour front desk', 'HOTEL'),
    ('00000000-0000-0000-0000-000000000201', 'AIR_CONDITIONING', 'Air conditioning', 'ROOM'),
    ('00000000-0000-0000-0000-000000000202', 'PRIVATE_BATHROOM', 'Private bathroom', 'ROOM'),
    ('00000000-0000-0000-0000-000000000203', 'TV', 'Flat-screen TV', 'ROOM'),
    ('00000000-0000-0000-0000-000000000204', 'MINIBAR', 'Minibar', 'ROOM'),
    ('00000000-0000-0000-0000-000000000205', 'BALCONY', 'Balcony', 'ROOM'),
    ('00000000-0000-0000-0000-000000000206', 'SEA_VIEW', 'Sea view', 'ROOM'),
    ('00000000-0000-0000-0000-000000000207', 'KITCHENETTE', 'Kitchenette', 'ROOM'),
    ('00000000-0000-0000-0000-000000000208', 'BATHTUB', 'Bathtub', 'ROOM');
