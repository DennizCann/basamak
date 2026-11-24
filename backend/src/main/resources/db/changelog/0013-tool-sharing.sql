--liquibase formatted sql

--changeset basamak:0013-tool-sharing
-- Members can share a tool definition with the community. Others fork it into their own tools: the definition is
-- copied, secrets never are (each member uses their own key and their own daily quota).
ALTER TABLE user_tool ADD COLUMN visibility VARCHAR(10) NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','PUBLIC'));
ALTER TABLE user_tool ADD COLUMN forked_from UUID REFERENCES user_tool(id);
ALTER TABLE user_tool ADD COLUMN fork_count INT NOT NULL DEFAULT 0;
CREATE INDEX ix_user_tool_public ON user_tool (updated_at DESC) WHERE visibility = 'PUBLIC' AND deleted_at IS NULL;
--rollback DROP INDEX ix_user_tool_public; ALTER TABLE user_tool DROP COLUMN fork_count; ALTER TABLE user_tool DROP COLUMN forked_from; ALTER TABLE user_tool DROP COLUMN visibility;
