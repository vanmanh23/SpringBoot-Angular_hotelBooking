-- BR-32: append-only audit of staff actions (same shape as auth_db.audit_logs). Written by common-lib AuditTrail.
CREATE TABLE audit_logs (
    id          UUID         PRIMARY KEY,
    actor_id    UUID         NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    target_type VARCHAR(50)  NOT NULL,
    target_id   VARCHAR(100) NOT NULL,
    details     JSONB,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_audit_logs_target ON audit_logs (target_type, target_id, created_at DESC);
