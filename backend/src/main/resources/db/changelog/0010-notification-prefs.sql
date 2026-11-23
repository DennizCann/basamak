--liquibase formatted sql

--changeset basamak:0010-notification-prefs
-- Per member, per event type: which channels deliver it. A missing row means the type's defaults (NotificationService.TYPES).
CREATE TABLE notification_pref (
    user_id    UUID        NOT NULL REFERENCES users(id),
    event_type VARCHAR(30) NOT NULL,
    in_app     BOOLEAN     NOT NULL,
    email      BOOLEAN     NOT NULL,
    push       BOOLEAN     NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, event_type)
);
--rollback DROP TABLE notification_pref;
