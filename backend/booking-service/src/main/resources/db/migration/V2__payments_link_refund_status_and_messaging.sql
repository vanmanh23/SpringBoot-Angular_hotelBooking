-- M4: link to the payment that confirmed the booking, refund tracking, outbox and consumer dedup (D-024, D-025).
ALTER TABLE bookings
    ADD COLUMN payment_id    UUID,
    ADD COLUMN refund_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
    ADD CONSTRAINT chk_bookings_refund_status CHECK (refund_status IN ('NONE', 'PENDING', 'REFUNDED', 'FAILED'));

CREATE TABLE outbox_events (
    id           UUID         PRIMARY KEY,
    seq          BIGINT       GENERATED ALWAYS AS IDENTITY,  -- deterministic publish order within one transaction
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
