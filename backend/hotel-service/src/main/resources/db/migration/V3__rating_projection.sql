-- Hotel score projection fed by review-service (D-028): only newer versions are applied.
ALTER TABLE hotels ADD COLUMN rating_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE processed_messages (
    event_id     UUID        NOT NULL,
    consumer     VARCHAR(60) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, consumer)
);
