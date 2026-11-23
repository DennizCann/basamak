--liquibase formatted sql

--changeset basamak:0006-tools-jobs
-- Secrets of a tool (CMS tool = content item, member tool = user_tool). Kept OUT of the versioned JSONB snapshot so
-- they never appear in history, diffs or API responses. AES-256-GCM ciphertext (CryptoService).
CREATE TABLE tool_secret (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope       VARCHAR(10)  NOT NULL CHECK (scope IN ('CONTENT', 'USER')),
    tool_id     UUID         NOT NULL,
    name        VARCHAR(60)  NOT NULL,
    ciphertext  TEXT         NOT NULL,
    updated_by  UUID         NOT NULL REFERENCES users(id),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (scope, tool_id, name)
);

-- A member's own tool. Each save bumps version_no and keeps the previous definition in user_tool_version.
CREATE TABLE user_tool (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    UUID         NOT NULL REFERENCES users(id),
    name        VARCHAR(120) NOT NULL,
    definition  JSONB        NOT NULL,
    def_hash    VARCHAR(64)  NOT NULL,
    version_no  INT          NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);
CREATE INDEX ix_user_tool_owner ON user_tool (owner_id) WHERE deleted_at IS NULL;
-- the same member cannot farm "tool created" points with copies of one definition
CREATE UNIQUE INDEX ux_user_tool_owner_hash ON user_tool (owner_id, def_hash) WHERE deleted_at IS NULL;

CREATE TABLE user_tool_version (
    tool_id     UUID         NOT NULL REFERENCES user_tool(id),
    version_no  INT          NOT NULL,
    definition  JSONB        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tool_id, version_no)
);

-- Generic durable job queue (ADR-2): claimed with FOR UPDATE SKIP LOCKED, lease in locked_until.
CREATE TABLE job (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind            VARCHAR(30)  NOT NULL,
    ref_id          UUID         NOT NULL,
    status          VARCHAR(12)  NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'DEAD')),
    attempts        INT          NOT NULL DEFAULT 0,
    max_attempts    INT          NOT NULL DEFAULT 1,
    run_after       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_until    TIMESTAMPTZ,
    locked_by       VARCHAR(80),
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_job_due ON job (run_after) WHERE status = 'QUEUED';
CREATE INDEX ix_job_lease ON job (locked_until) WHERE status = 'RUNNING';

-- One execution of a tool as the member sees it (status timeline, output, timing).
CREATE TABLE tool_run (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL REFERENCES users(id),
    scope            VARCHAR(10)  NOT NULL CHECK (scope IN ('CONTENT', 'USER')),
    tool_id          UUID         NOT NULL,
    tool_version_no  INT          NOT NULL,
    tool_name        VARCHAR(200) NOT NULL,
    training_id      UUID,
    item_key         VARCHAR(60),
    input            JSONB        NOT NULL,
    status           VARCHAR(12)  NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'RETRYING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')),
    attempts         INT          NOT NULL DEFAULT 0,
    max_attempts     INT          NOT NULL,
    cancel_requested BOOLEAN      NOT NULL DEFAULT false,
    http_status      INT,
    output           JSONB,
    error            TEXT,
    duration_ms      BIGINT,
    request_id       VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ
);
CREATE INDEX ix_tool_run_user ON tool_run (user_id, created_at DESC);

-- Status events of a run; the SSE stream replays from here after Last-Event-ID (missed events are not lost).
CREATE TABLE tool_run_event (
    id          BIGSERIAL PRIMARY KEY,
    run_id      UUID         NOT NULL REFERENCES tool_run(id),
    type        VARCHAR(20)  NOT NULL,
    data        JSONB        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_tool_run_event_run ON tool_run_event (run_id, id);
--rollback DROP TABLE tool_run_event; DROP TABLE tool_run; DROP TABLE job; DROP TABLE user_tool_version; DROP TABLE user_tool; DROP TABLE tool_secret;
