--liquibase formatted sql

--changeset basamak:0012-workflow-events
-- Platform-event trigger: run the member's workflow when the member's own learning event happens
-- (e.g. TRAINING_COMPLETED). Only learning events are allowed, so a workflow cannot trigger itself in a loop.
ALTER TABLE workflow ADD COLUMN event_trigger VARCHAR(40);
CREATE INDEX ix_workflow_event_trigger ON workflow (owner_id, event_trigger) WHERE event_trigger IS NOT NULL AND deleted_at IS NULL;
--rollback DROP INDEX ix_workflow_event_trigger; ALTER TABLE workflow DROP COLUMN event_trigger;

--changeset basamak:0012-run-trigger-event
ALTER TABLE workflow_run DROP CONSTRAINT IF EXISTS workflow_run_trigger_check;
ALTER TABLE workflow_run ADD CONSTRAINT workflow_run_trigger_check CHECK (trigger IN ('MANUAL', 'CRON', 'WEBHOOK', 'SUBFLOW', 'EVENT'));
--rollback ALTER TABLE workflow_run DROP CONSTRAINT workflow_run_trigger_check; ALTER TABLE workflow_run ADD CONSTRAINT workflow_run_trigger_check CHECK (trigger IN ('MANUAL', 'CRON', 'WEBHOOK', 'SUBFLOW'));
