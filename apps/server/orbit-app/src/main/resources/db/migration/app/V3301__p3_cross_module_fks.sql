-- Cross-module foreign key constraints and collation alignment enforced after all P3 module schemas exist.

ALTER TABLE documents
    MODIFY COLUMN snapshot_object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT fk_documents_snapshot_object
        FOREIGN KEY (snapshot_object_key) REFERENCES storage_objects(object_key);

ALTER TABLE document_sessions
    ADD CONSTRAINT fk_document_sessions_space
        FOREIGN KEY (space_id) REFERENCES spaces(id),
    ADD CONSTRAINT fk_document_sessions_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    ADD CONSTRAINT fk_document_sessions_auth_session
        FOREIGN KEY (auth_session_id) REFERENCES auth_sessions(id);

ALTER TABLE document_update_batches
    MODIFY COLUMN object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT fk_doc_update_batches_object
        FOREIGN KEY (object_key) REFERENCES storage_objects(object_key);

ALTER TABLE document_versions
    MODIFY COLUMN snapshot_object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT fk_doc_versions_snapshot_object
        FOREIGN KEY (snapshot_object_key) REFERENCES storage_objects(object_key);

ALTER TABLE document_comments
    ADD CONSTRAINT fk_document_comments_space
        FOREIGN KEY (space_id) REFERENCES spaces(id),
    ADD CONSTRAINT fk_document_comments_file
        FOREIGN KEY (file_id) REFERENCES files(id);

ALTER TABLE document_restore_previews
    ADD CONSTRAINT fk_doc_restore_previews_user
        FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE document_ai_summaries
    MODIFY COLUMN job_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY COLUMN result_object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT fk_doc_ai_summaries_job
        FOREIGN KEY (job_id) REFERENCES async_jobs(job_id),
    ADD CONSTRAINT fk_doc_ai_summaries_space
        FOREIGN KEY (space_id) REFERENCES spaces(id),
    ADD CONSTRAINT fk_doc_ai_summaries_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    ADD CONSTRAINT fk_doc_ai_summaries_result_object
        FOREIGN KEY (result_object_key) REFERENCES storage_objects(object_key);

-- Align job_id collation with async_jobs.job_id for JOIN compatibility.
-- (DocumentPdfService inserts document_pdf_indexes before enqueuing async_jobs, so job_id is not FK-bound.)
ALTER TABLE document_pdf_indexes
    MODIFY COLUMN job_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY COLUMN result_object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT fk_doc_pdf_indexes_file
        FOREIGN KEY (file_id) REFERENCES files(id),
    ADD CONSTRAINT fk_doc_pdf_indexes_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    ADD CONSTRAINT fk_doc_pdf_indexes_result_object
        FOREIGN KEY (result_object_key) REFERENCES storage_objects(object_key);

ALTER TABLE git_sources
    MODIFY COLUMN active_job_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    MODIFY COLUMN last_synced_job_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT fk_git_sources_active_job
        FOREIGN KEY (active_job_id) REFERENCES async_jobs(job_id),
    ADD CONSTRAINT fk_git_sources_last_synced_job
        FOREIGN KEY (last_synced_job_id) REFERENCES async_jobs(job_id);

ALTER TABLE git_object_observations
    MODIFY COLUMN last_reachable_job_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT fk_git_obj_obs_reachable_job
        FOREIGN KEY (last_reachable_job_id) REFERENCES async_jobs(job_id);

ALTER TABLE chat_doc_pdf_cards
    ADD CONSTRAINT fk_chat_doc_pdf_cards_comment
        FOREIGN KEY (comment_id) REFERENCES document_comments(id),
    ADD CONSTRAINT fk_chat_doc_pdf_cards_document
        FOREIGN KEY (document_id) REFERENCES documents(id),
    ADD CONSTRAINT fk_chat_doc_pdf_cards_version
        FOREIGN KEY (persisted_version_id) REFERENCES document_versions(id),
    ADD CONSTRAINT fk_chat_doc_pdf_cards_file
        FOREIGN KEY (file_id) REFERENCES files(id);

INSERT INTO orbit_runtime_settings (setting_key, setting_value, description) VALUES
    ('p3.document.snapshot-interval-seconds', '300', 'Periodic full CRDT snapshot solidification interval in seconds (5 minutes).'),
    ('p3.document.batch-retention-days', '7', 'Retention window in days for historical CRDT update batches covered by a solidified snapshot.'),
    ('p3.document.auto-version-retention-days', '30', 'Retention window in days for unreferenced AUTO document versions superseded by newer snapshots.'),
    ('p3.backup.retention-days', '14', 'Backup retention window in days coordinated with object GC grace and revocation journal replay.')
AS new_setting
ON DUPLICATE KEY UPDATE
    setting_value = new_setting.setting_value,
    description = new_setting.description;

INSERT INTO orbit_schema_meta (migration_version, database_charset)
VALUES (3301, (SELECT default_character_set_name
               FROM information_schema.schemata
               WHERE schema_name = DATABASE()));
