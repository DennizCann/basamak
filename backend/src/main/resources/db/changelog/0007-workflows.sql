--liquibase formatted sql

--changeset basamak:0007-workflows
-- A member's workflow. Each save keeps the previous definition in workflow_version; runs record the version used.
CREATE TABLE workflow (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id           UUID         NOT NULL REFERENCES users(id),
    name               VARCHAR(120) NOT NULL,
    description        VARCHAR(500) NOT NULL DEFAULT '',
    definition         JSONB        NOT NULL,
    version_no         INT          NOT NULL DEFAULT 1,
    visibility         VARCHAR(10)  NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
    forked_from        UUID REFERENCES workflow(id),
    fork_count         INT          NOT NULL DEFAULT 0,
    webhook_token_hash VARCHAR(64) UNIQUE,
    cron               VARCHAR(100),
    cron_input         JSONB,
    next_run_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ
);
CREATE INDEX ix_workflow_owner ON workflow (owner_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_workflow_public ON workflow (visibility, fork_count DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_workflow_cron_due ON workflow (next_run_at) WHERE cron IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE workflow_version (
    workflow_id UUID        NOT NULL REFERENCES workflow(id),
    version_no  INT         NOT NULL,
    definition  JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workflow_id, version_no)
);

CREATE TABLE workflow_run (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id         UUID          NOT NULL REFERENCES workflow(id),
    workflow_version_no INT           NOT NULL,
    user_id             UUID          NOT NULL REFERENCES users(id),
    parent_run_id       UUID REFERENCES workflow_run(id),
    parent_step_id      VARCHAR(30),
    trigger             VARCHAR(10)   NOT NULL CHECK (trigger IN ('MANUAL', 'CRON', 'WEBHOOK', 'SUBFLOW')),
    input               JSONB         NOT NULL,
    status              VARCHAR(10)   NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    output              JSONB,
    error               TEXT,
    failed_step_id      VARCHAR(30),
    total_tokens        INT           NOT NULL DEFAULT 0,
    total_cost          NUMERIC(14,6) NOT NULL DEFAULT 0,
    resume_count        INT           NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ
);
CREATE INDEX ix_workflow_run_user ON workflow_run (user_id, created_at DESC);
CREATE INDEX ix_workflow_run_wf ON workflow_run (workflow_id, created_at DESC);

-- One execution of one step. A resumed run adds new rows for the steps it re-executes (history is kept).
CREATE TABLE workflow_step_run (
    id          BIGSERIAL PRIMARY KEY,
    run_id      UUID          NOT NULL REFERENCES workflow_run(id),
    step_id     VARCHAR(30)   NOT NULL,
    step_type   VARCHAR(12)   NOT NULL,
    status      VARCHAR(10)   NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    input       JSONB,
    output      JSONB,
    error       TEXT,
    attempts    INT           NOT NULL DEFAULT 1,
    duration_ms BIGINT,
    tokens      INT           NOT NULL DEFAULT 0,
    cost        NUMERIC(14,6) NOT NULL DEFAULT 0,
    child_run_id UUID,
    started_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
CREATE INDEX ix_workflow_step_run ON workflow_step_run (run_id, id);

-- weekly leaderboard reads the ledger by time
CREATE INDEX ix_point_ledger_created ON point_ledger (created_at, user_id);
--rollback DROP INDEX ix_point_ledger_created; DROP TABLE workflow_step_run; DROP TABLE workflow_run; DROP TABLE workflow_version; DROP TABLE workflow;
