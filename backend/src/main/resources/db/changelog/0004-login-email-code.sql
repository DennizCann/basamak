--liquibase formatted sql

--changeset basamak:0004-login-challenge
-- Second step of the member login: a 6-digit code sent to the account's e-mail address.
-- Only an HMAC of the code is stored (never the code itself); a new code replaces the previous hash.
CREATE TABLE login_challenge (
    id           UUID PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users(id),
    audience     VARCHAR(8)   NOT NULL CHECK (audience IN ('web','mobile')),
    code_hash    VARCHAR(64)  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    send_count   INT          NOT NULL DEFAULT 1,
    last_sent_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    consumed_at  TIMESTAMPTZ,
    user_agent   VARCHAR(300),
    ip           VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_login_challenge_user ON login_challenge (user_id, created_at DESC);
--rollback DROP TABLE login_challenge;
