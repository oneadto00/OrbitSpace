-- Cross-module foreign key constraints enforced after all P2 module schemas exist.

ALTER TABLE friend_requests
    ADD CONSTRAINT fk_friend_req_requester
        FOREIGN KEY (requester_user_id) REFERENCES users(id),
    ADD CONSTRAINT fk_friend_req_recipient
        FOREIGN KEY (recipient_user_id) REFERENCES users(id);

ALTER TABLE friendships
    ADD CONSTRAINT fk_friendships_user_low
        FOREIGN KEY (user_low_id) REFERENCES users(id),
    ADD CONSTRAINT fk_friendships_user_high
        FOREIGN KEY (user_high_id) REFERENCES users(id);

ALTER TABLE user_storage_quotas
    ADD CONSTRAINT fk_user_storage_quotas_user
        FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE quota_reservations
    ADD CONSTRAINT fk_quota_reservations_actor
        FOREIGN KEY (actor_user_id) REFERENCES users(id);

ALTER TABLE files
    ADD CONSTRAINT fk_files_space
        FOREIGN KEY (space_id) REFERENCES spaces(id),
    ADD CONSTRAINT fk_files_owner
        FOREIGN KEY (owner_user_id) REFERENCES users(id),
    ADD CONSTRAINT fk_files_object
        FOREIGN KEY (object_key) REFERENCES storage_objects(object_key);

ALTER TABLE upload_sessions
    ADD CONSTRAINT fk_upload_sessions_actor
        FOREIGN KEY (actor_user_id) REFERENCES users(id),
    ADD CONSTRAINT fk_upload_sessions_space
        FOREIGN KEY (space_id) REFERENCES spaces(id),
    ADD CONSTRAINT fk_upload_sessions_matched_file
        FOREIGN KEY (matched_file_id) REFERENCES files(id),
    ADD CONSTRAINT fk_upload_sessions_completed_file
        FOREIGN KEY (completed_file_id) REFERENCES files(id);

ALTER TABLE upload_parts
    ADD CONSTRAINT fk_upload_parts_object
        FOREIGN KEY (object_key) REFERENCES storage_objects(object_key);

ALTER TABLE file_shares
    ADD CONSTRAINT fk_file_shares_sender
        FOREIGN KEY (sender_user_id) REFERENCES users(id),
    ADD CONSTRAINT fk_file_shares_source_space
        FOREIGN KEY (source_space_id) REFERENCES spaces(id);

ALTER TABLE file_share_recipients
    ADD CONSTRAINT fk_file_share_recipients_user
        FOREIGN KEY (recipient_user_id) REFERENCES users(id);

ALTER TABLE download_sessions
    ADD CONSTRAINT fk_download_sessions_actor
        FOREIGN KEY (actor_user_id) REFERENCES users(id);

ALTER TABLE git_backfill_sessions
    ADD CONSTRAINT fk_git_backfill_sessions_space
        FOREIGN KEY (space_id) REFERENCES spaces(id),
    ADD CONSTRAINT fk_git_backfill_sessions_user
        FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE git_backfill_objects
    ADD CONSTRAINT fk_git_backfill_objects_object
        FOREIGN KEY (object_key) REFERENCES storage_objects(object_key);

ALTER TABLE two_point_diffs
    ADD CONSTRAINT fk_two_point_diffs_space
        FOREIGN KEY (space_id) REFERENCES spaces(id),
    ADD CONSTRAINT fk_two_point_diffs_actor
        FOREIGN KEY (actor_user_id) REFERENCES users(id);

ALTER TABLE code_comments
    ADD CONSTRAINT fk_code_comments_space
        FOREIGN KEY (space_id) REFERENCES spaces(id),
    ADD CONSTRAINT fk_code_comments_author
        FOREIGN KEY (author_user_id) REFERENCES users(id),
    ADD CONSTRAINT fk_code_comments_thread
        FOREIGN KEY (discussion_thread_id) REFERENCES discussion_threads(root_msg_id);

ALTER TABLE code_comment_replies
    ADD CONSTRAINT fk_code_comment_replies_sender
        FOREIGN KEY (sender_id) REFERENCES users(id);

ALTER TABLE discussion_threads
    ADD CONSTRAINT fk_discussion_threads_source_project
        FOREIGN KEY (source_project_id) REFERENCES git_projects(id);

INSERT INTO orbit_schema_meta (migration_version, database_charset)
VALUES (2301, (SELECT default_character_set_name
               FROM information_schema.schemata
               WHERE schema_name = DATABASE()));
