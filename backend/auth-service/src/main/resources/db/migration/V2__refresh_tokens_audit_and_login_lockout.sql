-- Temporary lockout after repeated failed logins (brute-force protection, M1-1)
ALTER TABLE users
    ADD COLUMN failed_login_attempts INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN lockout_until         TIMESTAMPTZ,
    ADD CONSTRAINT chk_users_failed_login_attempts CHECK (failed_login_attempts >= 0);

-- Refresh tokens: only a SHA-256 hash is stored. Tokens of one login session share a family_id so that
-- reuse of a rotated token can revoke the whole session (DECISIONS P-07).
CREATE TABLE refresh_tokens (
    id            UUID        PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id     UUID        NOT NULL,
    token_hash    VARCHAR(64) NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ,
    revoke_reason VARCHAR(30),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT chk_refresh_tokens_revocation CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL)),
    CONSTRAINT chk_refresh_tokens_reason CHECK (revoke_reason IN
        ('ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'PASSWORD_CHANGED', 'ACCOUNT_DISABLED'))
);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user_active ON refresh_tokens (user_id) WHERE revoked_at IS NULL;

-- Audit trail for sensitive admin actions (BR-32). Append-only by convention.
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
