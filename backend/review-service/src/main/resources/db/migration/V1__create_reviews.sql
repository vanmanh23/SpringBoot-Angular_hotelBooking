-- Design ref: hotel-stayed-report.docx 4.4.10 (reviews), 5.4.5; DECISIONS D-027, D-028.
-- hotel_id / booking_id / user_id are logical references to other services.

-- Completed stays reported by booking-service (BookingCompleted): who may review what.
CREATE TABLE eligible_stays (
    booking_id     UUID        PRIMARY KEY,
    user_id        UUID        NOT NULL,
    hotel_id       UUID        NOT NULL,
    check_out_date DATE        NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_eligible_stays_user ON eligible_stays (user_id);

CREATE TABLE reviews (
    id         UUID          PRIMARY KEY,
    booking_id UUID          NOT NULL,
    user_id    UUID          NOT NULL,
    hotel_id   UUID          NOT NULL,
    rating     SMALLINT      NOT NULL,
    comment    VARCHAR(2000),
    status     VARCHAR(10)   NOT NULL,
    hidden_reason VARCHAR(300),
    created_at TIMESTAMPTZ   NOT NULL,
    updated_at TIMESTAMPTZ   NOT NULL,
    version    BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_reviews_booking UNIQUE (booking_id),           -- one review per stay
    CONSTRAINT fk_reviews_stay FOREIGN KEY (booking_id) REFERENCES eligible_stays (booking_id),
    CONSTRAINT chk_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT chk_reviews_status CHECK (status IN ('VISIBLE', 'HIDDEN'))
);
-- Public list per hotel, newest first (4.7).
CREATE INDEX idx_reviews_hotel_visible ON reviews (hotel_id, created_at DESC) WHERE status = 'VISIBLE';
CREATE INDEX idx_reviews_user ON reviews (user_id, created_at DESC);

-- Monotonic version per hotel so hotel-service can ignore stale/out-of-order rating events.
CREATE TABLE hotel_rating_stats (
    hotel_id     UUID          PRIMARY KEY,
    avg_rating   NUMERIC(2, 1),
    review_count INTEGER       NOT NULL,
    version      BIGINT        NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL
);

CREATE TABLE outbox_events (
    id           UUID         PRIMARY KEY,
    seq          BIGINT       GENERATED ALWAYS AS IDENTITY,
    topic        VARCHAR(100) NOT NULL,
    message_key  VARCHAR(100) NOT NULL,
    event_type   VARCHAR(60)  NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ,
    attempts     INTEGER      NOT NULL DEFAULT 0,
    last_error   VARCHAR(500)
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (seq) WHERE published_at IS NULL;

CREATE TABLE processed_messages (
    event_id     UUID        NOT NULL,
    consumer     VARCHAR(60) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, consumer)
);
