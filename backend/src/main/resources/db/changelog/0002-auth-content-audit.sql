--liquibase formatted sql

--changeset basamak:0002-immutable-fn splitStatements:false
-- Shared guard for append-only tables (audit_log, later point_ledger, activity_event).
CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'table % is append-only (% not allowed)', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;
--rollback DROP FUNCTION forbid_mutation();

--changeset basamak:0002-users
CREATE TABLE users (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email              VARCHAR(254) NOT NULL,
    password_hash      VARCHAR(100) NOT NULL,
    display_name       VARCHAR(80)  NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DELETED')),
    email_verified_at  TIMESTAMPTZ,
    failed_login_count INT          NOT NULL DEFAULT 0,
    locked_until       TIMESTAMPTZ,
    totp_secret_enc    VARCHAR(200),
    totp_enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    consent_version    VARCHAR(20),
    consent_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ,
    version            BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

CREATE TABLE user_roles (
    user_id UUID        NOT NULL REFERENCES users(id),
    role    VARCHAR(16) NOT NULL CHECK (role IN ('MEMBER','AUTHOR','EDITOR','PUBLISHER','ADMIN')),
    PRIMARY KEY (user_id, role)
);

CREATE TABLE refresh_sessions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL REFERENCES users(id),
    token_hash   VARCHAR(64)  NOT NULL UNIQUE,
    audience     VARCHAR(8)   NOT NULL CHECK (audience IN ('web','mobile','cms')),
    user_agent   VARCHAR(300),
    ip           VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ
);
CREATE INDEX ix_refresh_sessions_user ON refresh_sessions (user_id);

CREATE TABLE email_tokens (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    purpose    VARCHAR(16) NOT NULL CHECK (purpose IN ('VERIFY','RESET')),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ
);
--rollback DROP TABLE email_tokens; DROP TABLE refresh_sessions; DROP TABLE user_roles; DROP TABLE users;

--changeset basamak:0002-content
CREATE TABLE content_item (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type                 VARCHAR(24) NOT NULL CHECK (type IN ('JOURNEY','TRAINING','TOOL','WORKFLOW_TEMPLATE','POINT_RULE')),
    owner_id             UUID        NOT NULL REFERENCES users(id),
    published_version_id UUID,
    lock_version         BIGINT      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_content_item_type ON content_item (type);

CREATE TABLE content_version (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id       UUID         NOT NULL REFERENCES content_item(id),
    version_no    INT          NOT NULL,
    status        VARCHAR(20)  NOT NULL CHECK (status IN ('DRAFT','IN_REVIEW','CHANGES_REQUESTED','APPROVED','SCHEDULED','PUBLISHED','ARCHIVED','SUPERSEDED')),
    title         VARCHAR(200) NOT NULL,
    body          JSONB        NOT NULL,
    based_on_no   INT,
    created_by    UUID         NOT NULL REFERENCES users(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reviewed_by   UUID REFERENCES users(id),
    reviewed_at   TIMESTAMPTZ,
    review_note   VARCHAR(2000),
    published_by  UUID REFERENCES users(id),
    published_at  TIMESTAMPTZ,
    scheduled_at  TIMESTAMPTZ,
    UNIQUE (item_id, version_no)
);
CREATE INDEX ix_content_version_scheduled ON content_version (scheduled_at) WHERE status = 'SCHEDULED';
ALTER TABLE content_item ADD CONSTRAINT fk_content_item_published FOREIGN KEY (published_version_id) REFERENCES content_version(id);
--rollback ALTER TABLE content_item DROP CONSTRAINT fk_content_item_published; DROP TABLE content_version; DROP TABLE content_item;

--changeset basamak:0002-audit
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    UUID,
    action      VARCHAR(64)  NOT NULL,
    entity_type VARCHAR(32)  NOT NULL,
    entity_id   VARCHAR(64),
    details     JSONB,
    request_id  VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_entity ON audit_log (entity_type, entity_id);
CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
--rollback DROP TABLE audit_log;
