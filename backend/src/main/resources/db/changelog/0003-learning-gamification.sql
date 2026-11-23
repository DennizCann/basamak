--liquibase formatted sql

--changeset basamak:0003-activity
-- Every member action (web, mobile, background) as one event. event_key is the idempotency key:
-- the same event arriving twice (retry, network error, mobile sync) is stored once.
CREATE TABLE activity_event (
    id             BIGSERIAL PRIMARY KEY,
    user_id        UUID         NOT NULL,
    event_key      VARCHAR(200) NOT NULL UNIQUE,
    type           VARCHAR(40)  NOT NULL,
    object_type    VARCHAR(24)  NOT NULL,
    object_id      VARCHAR(80)  NOT NULL,
    object_version INT,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    received_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    source         VARCHAR(10)  NOT NULL DEFAULT 'web',
    payload        JSONB
);
CREATE INDEX ix_activity_user_time ON activity_event (user_id, received_at DESC);
CREATE TRIGGER activity_event_append_only BEFORE UPDATE OR DELETE ON activity_event
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
--rollback DROP TABLE activity_event;

--changeset basamak:0003-ledger
-- Point ledger: append-only. Corrections are reversing rows (reverses_id), never updates.
-- idempotency_key encodes the rule's repeat policy (once per object, once per day, every event).
CREATE TABLE point_ledger (
    id               BIGSERIAL PRIMARY KEY,
    user_id          UUID         NOT NULL,
    points           INT          NOT NULL,
    rule_item_id     UUID,
    rule_version_no  INT,
    action           VARCHAR(40)  NOT NULL,
    object_type      VARCHAR(24)  NOT NULL,
    object_id        VARCHAR(80)  NOT NULL,
    event_key        VARCHAR(200) NOT NULL,
    idempotency_key  VARCHAR(300) NOT NULL UNIQUE,
    reverses_id      BIGINT REFERENCES point_ledger(id),
    reason           VARCHAR(200),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_ledger_user_time ON point_ledger (user_id, created_at DESC);
CREATE INDEX ix_ledger_time ON point_ledger (created_at);
CREATE TRIGGER point_ledger_append_only BEFORE UPDATE OR DELETE ON point_ledger
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- Materialised total per member, updated in the same transaction as the ledger insert.
CREATE TABLE user_score (
    user_id    UUID PRIMARY KEY,
    total      INT         NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_user_score_total ON user_score (total DESC);
--rollback DROP TABLE user_score; DROP TABLE point_ledger;

--changeset basamak:0003-learning
CREATE TABLE enrollment (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID        NOT NULL,
    training_id         UUID        NOT NULL REFERENCES content_item(id),
    training_version_no INT         NOT NULL,
    status              VARCHAR(16) NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    current_item_key    VARCHAR(60),
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, training_id)
);

CREATE TABLE item_progress (
    enrollment_id UUID        NOT NULL REFERENCES enrollment(id),
    item_key      VARCHAR(60) NOT NULL,
    completed_at  TIMESTAMPTZ,
    watched_seconds INT       NOT NULL DEFAULT 0,
    last_beat_at  TIMESTAMPTZ,
    PRIMARY KEY (enrollment_id, item_key)
);

CREATE TABLE quiz_attempt (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID        NOT NULL,
    enrollment_id       UUID        NOT NULL REFERENCES enrollment(id),
    training_id         UUID        NOT NULL,
    training_version_no INT         NOT NULL,
    item_key            VARCHAR(60) NOT NULL,
    attempt_no          INT         NOT NULL,
    questions           JSONB       NOT NULL,
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deadline_at         TIMESTAMPTZ,
    submitted_at        TIMESTAMPTZ,
    answers             JSONB,
    score               INT,
    max_score           INT,
    passed              BOOLEAN,
    UNIQUE (enrollment_id, item_key, attempt_no)
);

-- Anonymous surveys: answers carry no user id; a separate marker prevents double submission.
CREATE TABLE survey_response (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    training_id         UUID        NOT NULL,
    training_version_no INT         NOT NULL,
    item_key            VARCHAR(60) NOT NULL,
    user_id             UUID,
    answers             JSONB       NOT NULL,
    submitted_on        DATE        NOT NULL DEFAULT CURRENT_DATE
);
CREATE TABLE survey_submission (
    user_id     UUID        NOT NULL,
    training_id UUID        NOT NULL,
    item_key    VARCHAR(60) NOT NULL,
    PRIMARY KEY (user_id, training_id, item_key)
);
--rollback DROP TABLE survey_submission; DROP TABLE survey_response; DROP TABLE quiz_attempt; DROP TABLE item_progress; DROP TABLE enrollment;

--changeset basamak:0003-certificate
CREATE TABLE certificate (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    serial             VARCHAR(40)  NOT NULL UNIQUE,
    user_id            UUID         NOT NULL,
    journey_id         UUID         NOT NULL,
    journey_version_no INT          NOT NULL,
    holder_name        VARCHAR(80)  NOT NULL,
    journey_title      VARCHAR(200) NOT NULL,
    issued_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    signature          VARCHAR(64)  NOT NULL,
    revoked_at         TIMESTAMPTZ,
    UNIQUE (user_id, journey_id)
);
--rollback DROP TABLE certificate;
