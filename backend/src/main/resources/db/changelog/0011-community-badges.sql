--liquibase formatted sql

--changeset basamak:0011-community
-- Public profile choices (absent row = private profile; the member opts in).
CREATE TABLE profile_settings (
    user_id       UUID PRIMARY KEY REFERENCES users(id),
    is_public     BOOLEAN NOT NULL DEFAULT FALSE,
    show_level    BOOLEAN NOT NULL DEFAULT TRUE,
    show_badges   BOOLEAN NOT NULL DEFAULT TRUE,
    show_journeys BOOLEAN NOT NULL DEFAULT TRUE,
    show_shared   BOOLEAN NOT NULL DEFAULT TRUE,
    bio           VARCHAR(280) NOT NULL DEFAULT '',
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE follow (
    follower_id UUID NOT NULL REFERENCES users(id),
    followee_id UUID NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, followee_id),
    CHECK (follower_id <> followee_id)
);
CREATE INDEX ix_follow_followee ON follow (followee_id);

-- Discussion on a training (or a shared workflow). One level of replies (parent_id).
CREATE TABLE comment (
    id          BIGSERIAL PRIMARY KEY,
    target_type VARCHAR(12)   NOT NULL CHECK (target_type IN ('TRAINING','WORKFLOW')),
    target_id   UUID          NOT NULL,
    parent_id   BIGINT REFERENCES comment(id),
    author_id   UUID          NOT NULL REFERENCES users(id),
    body        VARCHAR(2000) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    hidden_at   TIMESTAMPTZ,
    hidden_by   UUID REFERENCES users(id),
    hidden_reason VARCHAR(300)
);
CREATE INDEX ix_comment_target ON comment (target_type, target_id, id);
CREATE INDEX ix_comment_author ON comment (author_id, created_at DESC);

CREATE TABLE comment_report (
    id          BIGSERIAL PRIMARY KEY,
    comment_id  BIGINT       NOT NULL REFERENCES comment(id),
    reporter_id UUID         NOT NULL REFERENCES users(id),
    reason      VARCHAR(300) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    resolved_by UUID REFERENCES users(id),
    resolution  VARCHAR(20),
    UNIQUE (comment_id, reporter_id)
);
CREATE INDEX ix_comment_report_open ON comment_report (created_at) WHERE resolved_at IS NULL;

-- 1-5 stars on a shared workflow, one per member (changeable).
CREATE TABLE rating (
    target_type VARCHAR(12) NOT NULL CHECK (target_type IN ('WORKFLOW','TOOL')),
    target_id   UUID        NOT NULL,
    user_id     UUID        NOT NULL REFERENCES users(id),
    stars       SMALLINT    NOT NULL CHECK (stars BETWEEN 1 AND 5),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (target_type, target_id, user_id)
);

-- Moderation: a restricted member cannot comment until this time.
ALTER TABLE users ADD COLUMN restricted_until TIMESTAMPTZ;

CREATE TABLE user_badge (
    user_id    UUID        NOT NULL REFERENCES users(id),
    badge_key  VARCHAR(40) NOT NULL,
    awarded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, badge_key)
);

-- Daily streak by Europe/Istanbul calendar day of the SERVER receive time (device clock cannot extend it).
CREATE TABLE user_streak (
    user_id  UUID PRIMARY KEY REFERENCES users(id),
    current  INT  NOT NULL DEFAULT 0,
    longest  INT  NOT NULL DEFAULT 0,
    last_day DATE NOT NULL
);
--rollback DROP TABLE user_streak; DROP TABLE user_badge; ALTER TABLE users DROP COLUMN restricted_until; DROP TABLE rating; DROP TABLE comment_report; DROP TABLE comment; DROP TABLE follow; DROP TABLE profile_settings;
