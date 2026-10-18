-- Design ref: hotel-stayed-report.docx 4.4.8 (payments), 4.4.9 (refunds), 4.9.2, 5.5.3; DECISIONS D-023..D-025.
-- booking_id / user_id are logical references (no cross-database FKs).

CREATE TABLE payments (
    id                 UUID          PRIMARY KEY,
    booking_id         UUID          NOT NULL,
    user_id            UUID          NOT NULL,
    provider           VARCHAR(50)   NOT NULL,
    provider_reference VARCHAR(150),                  -- provider checkout session id
    idempotency_key    VARCHAR(100)  NOT NULL,
    amount             NUMERIC(14, 2) NOT NULL,
    currency_code      CHAR(3)       NOT NULL,
    status             VARCHAR(30)   NOT NULL,
    checkout_url       VARCHAR(1000),
    failure_reason     VARCHAR(300),
    needs_review       BOOLEAN       NOT NULL DEFAULT FALSE,
    succeeded_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_payments_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT uq_payments_provider_ref UNIQUE (provider, provider_reference),
    CONSTRAINT chk_payments_amount CHECK (amount > 0),
    CONSTRAINT chk_payments_currency CHECK (currency_code = 'VND'),
    CONSTRAINT chk_payments_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'REFUND_PENDING', 'REFUNDED'))
);
-- No double charge: one active (pending or captured) payment per booking.
CREATE UNIQUE INDEX uq_payments_active_per_booking ON payments (booking_id)
    WHERE status IN ('PENDING', 'SUCCEEDED', 'REFUND_PENDING', 'REFUNDED');
CREATE INDEX idx_payments_booking ON payments (booking_id, created_at DESC);
CREATE INDEX idx_payments_review ON payments (created_at) WHERE needs_review;

CREATE TABLE payment_status_history (
    id          UUID         PRIMARY KEY,
    payment_id  UUID         NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    from_status VARCHAR(30),
    to_status   VARCHAR(30)  NOT NULL,
    reason      VARCHAR(300),
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_payment_history_payment ON payment_status_history (payment_id, created_at);

CREATE TABLE refunds (
    id                 UUID          PRIMARY KEY,
    payment_id         UUID          NOT NULL REFERENCES payments (id),
    amount             NUMERIC(14, 2) NOT NULL,
    reason             VARCHAR(300)  NOT NULL,
    status             VARCHAR(30)   NOT NULL,
    provider_reference VARCHAR(150),
    attempts           INTEGER       NOT NULL DEFAULT 0,
    last_attempt_at    TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL,
    completed_at       TIMESTAMPTZ,
    CONSTRAINT chk_refunds_amount CHECK (amount > 0),
    CONSTRAINT chk_refunds_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'))
);
-- Full refunds only: at most one pending/successful refund per payment (no double refund).
CREATE UNIQUE INDEX uq_refunds_active_per_payment ON refunds (payment_id) WHERE status IN ('PENDING', 'SUCCEEDED');
CREATE INDEX idx_refunds_pending ON refunds (last_attempt_at) WHERE status = 'PENDING';

-- Provider events already applied (webhook replay protection, BR-24).
CREATE TABLE webhook_events (
    provider    VARCHAR(50)  NOT NULL,
    event_id    VARCHAR(150) NOT NULL,
    event_type  VARCHAR(60)  NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (provider, event_id)
);

-- Transactional outbox and consumer deduplication (common-lib messaging).
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

-- ---- Sandbox gateway (simulated external provider, D-023). Owned by the sandbox module only. ----
CREATE TABLE sandbox_sessions (
    id                 VARCHAR(40)   PRIMARY KEY,
    merchant_reference UUID          NOT NULL,          -- our payment id
    amount             NUMERIC(14, 2) NOT NULL,
    currency_code      CHAR(3)       NOT NULL,
    return_url         VARCHAR(1000) NOT NULL,
    status             VARCHAR(20)   NOT NULL,           -- OPEN, PAID, DECLINED
    webhook_event_id   VARCHAR(60),
    webhook_delivered  BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ   NOT NULL,
    completed_at       TIMESTAMPTZ,
    CONSTRAINT chk_sandbox_sessions_status CHECK (status IN ('OPEN', 'PAID', 'DECLINED'))
);
CREATE INDEX idx_sandbox_sessions_undelivered ON sandbox_sessions (completed_at) WHERE status <> 'OPEN' AND NOT webhook_delivered;

CREATE TABLE sandbox_refunds (
    idempotency_key   VARCHAR(100)  PRIMARY KEY,         -- our refund id: repeated requests never refund twice
    session_id        VARCHAR(40)   NOT NULL REFERENCES sandbox_sessions (id),
    amount            NUMERIC(14, 2) NOT NULL,
    outcome           VARCHAR(10)   NOT NULL,           -- SUCCEEDED / FAILED (session not paid or amount too high)
    webhook_event_id  VARCHAR(60)   NOT NULL,
    webhook_delivered BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ   NOT NULL
);
