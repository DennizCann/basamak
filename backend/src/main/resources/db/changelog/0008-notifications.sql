--liquibase formatted sql

--changeset basamak:0008-notifications
-- In-app notifications. The mobile app turns new ones into local notifications whose tap opens `link`.
-- dedup_key makes "notify" idempotent (a retried job never notifies twice).
CREATE TABLE notification (
    id         BIGSERIAL PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES users(id),
    type       VARCHAR(30)  NOT NULL,
    title      VARCHAR(200) NOT NULL,
    body       VARCHAR(500) NOT NULL DEFAULT '',
    link       VARCHAR(300),
    dedup_key  VARCHAR(200) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at    TIMESTAMPTZ
);
CREATE INDEX ix_notification_user ON notification (user_id, id DESC);
--rollback DROP TABLE notification;
