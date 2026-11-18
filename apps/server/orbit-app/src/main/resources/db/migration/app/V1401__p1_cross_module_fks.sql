-- Cross-module foreign key constraints enforced after all P1 module schemas exist.

ALTER TABLE device_authorization_requests
    ADD CONSTRAINT fk_dev_auth_req_space
        FOREIGN KEY (space_id) REFERENCES spaces(id),
    ADD CONSTRAINT fk_dev_auth_req_project
        FOREIGN KEY (project_id) REFERENCES git_projects(id);

ALTER TABLE device_credentials
    ADD CONSTRAINT fk_dev_cred_space
        FOREIGN KEY (space_id) REFERENCES spaces(id),
    ADD CONSTRAINT fk_dev_cred_project
        FOREIGN KEY (project_id) REFERENCES git_projects(id);

ALTER TABLE device_epoch_fences
    ADD CONSTRAINT fk_dev_epoch_fences_space
        FOREIGN KEY (space_id) REFERENCES spaces(id);

ALTER TABLE chat_messages
    ADD CONSTRAINT fk_chat_messages_sender
        FOREIGN KEY (sender_id) REFERENCES users(id);

ALTER TABLE discussion_threads
    ADD CONSTRAINT fk_discussion_threads_updater
        FOREIGN KEY (updated_by_user_id) REFERENCES users(id);

ALTER TABLE git_object_observations
    ADD CONSTRAINT fk_git_obj_obs_user
        FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE git_refs
    ADD CONSTRAINT fk_git_refs_user
        FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE worktree_states
    ADD CONSTRAINT fk_worktree_states_user
        FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE wip_snapshots
    ADD CONSTRAINT fk_wip_snapshots_space
        FOREIGN KEY (space_id) REFERENCES spaces(id),
    ADD CONSTRAINT fk_wip_snapshots_user
        FOREIGN KEY (user_id) REFERENCES users(id);

INSERT INTO orbit_schema_meta (migration_version, database_charset)
VALUES (1401, (SELECT default_character_set_name
               FROM information_schema.schemata
               WHERE schema_name = DATABASE()));
