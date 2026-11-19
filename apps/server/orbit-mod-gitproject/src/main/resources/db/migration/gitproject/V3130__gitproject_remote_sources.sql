CREATE TABLE git_sources (
    id VARCHAR(64) PRIMARY KEY,
    space_id VARCHAR(64) NOT NULL,
    project_id VARCHAR(64) NOT NULL,
    https_url VARCHAR(2048) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    owner_epoch BIGINT NOT NULL,
    status ENUM('ACTIVE','REVOKED') NOT NULL DEFAULT 'ACTIVE',
    lifecycle_version BIGINT NOT NULL DEFAULT 1,
    key_version INT NULL,
    token_ciphertext VARBINARY(16412) NULL,
    active_job_id VARCHAR(128) NULL,
    active_actor_epoch BIGINT NULL,
    last_synced_job_id VARCHAR(128) NULL,
    last_synced_at TIMESTAMP(6) NULL,
    last_history_complete BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_git_source_owner (space_id,owner_user_id,owner_epoch,status),
    INDEX idx_git_source_project (project_id,status,id),
    FOREIGN KEY (project_id) REFERENCES git_projects(id),
    FOREIGN KEY (space_id,owner_user_id) REFERENCES space_members(space_id,user_id),
    CHECK ((key_version IS NULL) = (token_ciphertext IS NULL))
) ENGINE=InnoDB;

CREATE TABLE git_remote_refs (
    source_id VARCHAR(64) NOT NULL,
    ref_name VARCHAR(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    target_oid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    synced_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (source_id,ref_name),
    FOREIGN KEY (source_id) REFERENCES git_sources(id)
) ENGINE=InnoDB;

ALTER TABLE git_object_observations
    MODIFY device_id VARCHAR(64) NULL,
    ADD COLUMN remote_source_id VARCHAR(64) NULL,
    ADD COLUMN last_reachable_job_id VARCHAR(128) NULL,
    ADD UNIQUE KEY uk_git_obs_remote (remote_source_id,object_format,oid),
    ADD FOREIGN KEY (remote_source_id) REFERENCES git_sources(id),
    ADD CHECK ((device_id IS NULL) <> (remote_source_id IS NULL));
