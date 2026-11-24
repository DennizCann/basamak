--liquibase formatted sql

--changeset basamak:0014-job-request-id
-- The X-Request-Id of the request that queued a job: workers put it in their log context, so one id follows the
-- action from the HTTP request through the queue to the background worker.
ALTER TABLE job ADD COLUMN request_id VARCHAR(64);
--rollback ALTER TABLE job DROP COLUMN request_id;

--changeset basamak:0014-quiz-extension
-- Accessibility (WCAG 2.2.1): a member may ask once per attempt for 50 % more time on a timed quiz.
ALTER TABLE quiz_attempt ADD COLUMN extended_seconds INT NOT NULL DEFAULT 0;
--rollback ALTER TABLE quiz_attempt DROP COLUMN extended_seconds;
