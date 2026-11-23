--liquibase formatted sql

--changeset basamak:0001-baseline
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE TABLE app_meta (
    key   VARCHAR(64) PRIMARY KEY,
    value VARCHAR(255) NOT NULL
);
INSERT INTO app_meta(key, value) VALUES ('schema', 'basamak');
--rollback DROP TABLE app_meta;
