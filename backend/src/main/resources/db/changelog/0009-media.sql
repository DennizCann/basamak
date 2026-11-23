--liquibase formatted sql

--changeset basamak:0009-media
-- Uploaded lesson media (images, video, attachments). Files are immutable and content-addressed (sha256):
-- a new version of a lesson references new media ids, old versions keep theirs, identical uploads are stored once.
CREATE TABLE media_asset (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id      UUID         NOT NULL REFERENCES users(id),
    kind          VARCHAR(10)  NOT NULL CHECK (kind IN ('IMAGE','VIDEO','FILE')),
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    sha256        CHAR(64)     NOT NULL,
    original_name VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_media_asset_sha ON media_asset (sha256);
CREATE INDEX ix_media_asset_created ON media_asset (created_at DESC);
--rollback DROP TABLE media_asset;
