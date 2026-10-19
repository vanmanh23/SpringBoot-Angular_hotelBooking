-- Design ref: hotel-stayed-report.docx 4.4.11 (notifications), 5.4.6; DECISIONS D-029.

-- Contact data received once from BookingCreated; the only personal data this service stores.
CREATE TABLE booking_contacts (
    booking_id    UUID          PRIMARY KEY,
    user_id       UUID          NOT NULL,
    contact_name  VARCHAR(150)  NOT NULL,
    contact_email VARCHAR(255)  NOT NULL,
    booking_code  VARCHAR(30)   NOT NULL,
    hotel_name    VARCHAR(200)  NOT NULL,
    check_in      DATE          NOT NULL,
    check_out     DATE          NOT NULL,
    total_amount  NUMERIC(14, 2) NOT NULL,
    currency_code CHAR(3)       NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL
);

CREATE TABLE notifications (
    id              UUID          PRIMARY KEY,
    event_id        UUID          NOT NULL,          -- source event: one notification per event
    user_id         UUID          NOT NULL,
    booking_id      UUID,
    type            VARCHAR(40)   NOT NULL,
    title           VARCHAR(200)  NOT NULL,
    body            VARCHAR(2000) NOT NULL,
    email_to        VARCHAR(255),
    email_status    VARCHAR(10)   NOT NULL,
    email_attempts  INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error      VARCHAR(300),
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_notifications_event UNIQUE (event_id),
    CONSTRAINT chk_notifications_email_status CHECK (email_status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED'))
);
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;
CREATE INDEX idx_notifications_email_due ON notifications (next_attempt_at) WHERE email_status = 'PENDING';

CREATE TABLE processed_messages (
    event_id     UUID        NOT NULL,
    consumer     VARCHAR(60) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, consumer)
);
