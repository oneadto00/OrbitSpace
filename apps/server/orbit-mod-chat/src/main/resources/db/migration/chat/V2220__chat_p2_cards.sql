ALTER TABLE chat_messages
    MODIFY COLUMN kind ENUM('TEXT','COMMIT_REF','WIP_REF','FILE_REF','CODE_COMMENT_REF','SYSTEM') NOT NULL,
    ADD COLUMN ref_grant_type ENUM('SPACE_FILE','AIRDROP_SHARE') NULL AFTER ref_target_id,
    ADD INDEX idx_chat_messages_file_ref (kind, ref_grant_type, ref_target_id, room_id);

ALTER TABLE discussion_threads
    ADD COLUMN source_kind ENUM('CHAT_MESSAGE','CODE_COMMENT') NOT NULL DEFAULT 'CHAT_MESSAGE' AFTER room_id,
    ADD COLUMN source_project_id VARCHAR(64) NULL AFTER source_kind,
    ADD COLUMN source_anchor_id VARCHAR(128) NULL AFTER source_project_id,
    ADD INDEX idx_discussion_threads_source (room_id, source_kind, source_project_id, source_anchor_id);
