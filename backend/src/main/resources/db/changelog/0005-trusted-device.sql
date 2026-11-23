--liquibase formatted sql

--changeset basamak:0005-trusted-device
-- "Remember this device" for the member login code: after a successful code the browser/app receives a random
-- token (stored here only as SHA-256). With it, the next logins on that device need only the password.
CREATE TABLE trusted_device (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64)  NOT NULL UNIQUE,
    user_agent VARCHAR(300),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX ix_trusted_device_user ON trusted_device (user_id);
--rollback DROP TABLE trusted_device;
