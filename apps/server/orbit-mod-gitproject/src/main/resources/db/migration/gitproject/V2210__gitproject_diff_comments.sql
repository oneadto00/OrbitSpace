CREATE TABLE git_backfill_sessions (
    backfill_id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    space_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    membership_epoch BIGINT NOT NULL,
    target_kind ENUM('COMMIT','WIP') NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_declaration_json JSON NOT NULL,
    manifest_json JSON NOT NULL,
    status ENUM('OPEN','COMPLETED','EXPIRED') NOT NULL DEFAULT 'OPEN',
    accepted_count INT UNSIGNED NOT NULL DEFAULT 0,
    expires_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_backfill_sessions_proj_status (project_id, status, expires_at),
    INDEX idx_backfill_sessions_target (project_id, target_kind, target_id, status),
    FOREIGN KEY (project_id) REFERENCES git_projects(id)
) ENGINE=InnoDB;

CREATE TABLE git_backfill_staged_uploads (
    backfill_id VARCHAR(64) NOT NULL,
    path_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    temp_object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    sha256_hex CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (backfill_id, path_sha256),
    FOREIGN KEY (backfill_id) REFERENCES git_backfill_sessions(backfill_id)
) ENGINE=InnoDB;

CREATE TABLE git_backfill_objects (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    target_kind ENUM('COMMIT','WIP') NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    path_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_mode VARCHAR(16) NOT NULL,
    blob_oid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    sha256_hex CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    backfill_id VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_backfill_obj_target_path (project_id, target_kind, target_id, path_sha256),
    INDEX idx_backfill_obj_object_key (object_key),
    FOREIGN KEY (project_id) REFERENCES git_projects(id),
    FOREIGN KEY (backfill_id) REFERENCES git_backfill_sessions(backfill_id)
) ENGINE=InnoDB;

CREATE TABLE two_point_diffs (
    id VARCHAR(64) PRIMARY KEY,
    space_id VARCHAR(64) NOT NULL,
    project_id VARCHAR(64) NOT NULL,
    actor_user_id VARCHAR(64) NOT NULL,
    base_kind ENUM('COMMIT','WIP') NOT NULL,
    base_id VARCHAR(128) NOT NULL,
    target_kind ENUM('COMMIT','WIP') NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    status ENUM('PENDING','READY','FAILED') NOT NULL DEFAULT 'READY',
    enrichment_job_id VARCHAR(64) NULL,
    enrichment_input_object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NULL,
    enrichment_result_object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NULL,
    summary_json JSON NOT NULL,
    files_json JSON NOT NULL,
    lifecycle_state ENUM('ACTIVE','EXPIRED_OR_DELETED','FORCE_PURGED') NOT NULL DEFAULT 'ACTIVE',
    lifecycle_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_two_point_diffs_proj_endpoints (project_id, base_kind, base_id, target_kind, target_id),
    INDEX idx_two_point_diffs_base (project_id, base_kind, base_id, lifecycle_state),
    INDEX idx_two_point_diffs_target (project_id, target_kind, target_id, lifecycle_state),
    FOREIGN KEY (project_id) REFERENCES git_projects(id)
) ENGINE=InnoDB;

CREATE TABLE code_comments (
    id VARCHAR(64) PRIMARY KEY,
    space_id VARCHAR(64) NOT NULL,
    project_id VARCHAR(64) NOT NULL,
    diff_id VARCHAR(64) NULL,
    base_kind ENUM('COMMIT','WIP') NOT NULL,
    base_id VARCHAR(128) NOT NULL,
    target_kind ENUM('COMMIT','WIP') NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    side ENUM('OLD','NEW') NOT NULL,
    line_number INT UNSIGNED NOT NULL,
    code_context_excerpt VARCHAR(2000) NULL,
    author_user_id VARCHAR(64) NOT NULL,
    body_text VARCHAR(4000) NOT NULL,
    discussion_thread_id VARCHAR(64) NULL,
    discussion_status ENUM('OPEN','RESOLVED') NOT NULL DEFAULT 'OPEN',
    discussion_version BIGINT NOT NULL DEFAULT 1,
    lifecycle_state ENUM('ACTIVE','EXPIRED_OR_DELETED','FORCE_PURGED') NOT NULL DEFAULT 'ACTIVE',
    lifecycle_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_code_comments_proj_diff (project_id, diff_id, lifecycle_state, created_at),
    INDEX idx_code_comments_base (project_id, base_kind, base_id, lifecycle_state),
    INDEX idx_code_comments_target (project_id, target_kind, target_id, lifecycle_state),
    INDEX idx_code_comments_thread (discussion_thread_id),
    FOREIGN KEY (project_id) REFERENCES git_projects(id),
    FOREIGN KEY (diff_id) REFERENCES two_point_diffs(id)
) ENGINE=InnoDB;

CREATE TABLE code_comment_replies (
    id VARCHAR(64) PRIMARY KEY,
    comment_id VARCHAR(64) NOT NULL,
    sender_id VARCHAR(64) NOT NULL,
    body_text VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_code_comment_replies_comment (comment_id, created_at, id),
    FOREIGN KEY (comment_id) REFERENCES code_comments(id)
) ENGINE=InnoDB;
