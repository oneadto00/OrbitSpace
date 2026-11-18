CREATE TABLE git_projects (
    id VARCHAR(64) PRIMARY KEY,
    space_id VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    display_config JSON NULL,
    lifecycle_version BIGINT NOT NULL DEFAULT 1,
    status ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_git_projects_space (space_id, status, created_at, id),
    FOREIGN KEY (space_id) REFERENCES spaces(id)
) ENGINE=InnoDB;

CREATE TABLE git_commits (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    object_format ENUM('sha1','sha256') NOT NULL,
    oid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trust_state ENUM('DECLARED','VERIFIED','CONFLICT') NOT NULL DEFAULT 'DECLARED',
    first_seen_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_git_commits_proj_fmt_oid (project_id, object_format, oid),
    INDEX idx_git_commits_proj_oid (project_id, oid),
    INDEX idx_git_commits_proj_id (project_id, id),
    FOREIGN KEY (project_id) REFERENCES git_projects(id)
) ENGINE=InnoDB;

CREATE TABLE git_commit_parents (
    commit_id BIGINT UNSIGNED NOT NULL,
    parent_order INT UNSIGNED NOT NULL,
    parent_oid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (commit_id, parent_order),
    FOREIGN KEY (commit_id) REFERENCES git_commits(id)
) ENGINE=InnoDB;

CREATE TABLE git_object_observations (
    id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    object_format ENUM('sha1','sha256') NOT NULL,
    oid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    observed_at TIMESTAMP(6) NOT NULL,
    trust_state ENUM('DECLARED','VERIFIED','CONFLICT') NOT NULL,
    subject VARCHAR(1000) NOT NULL,
    author_name VARCHAR(200) NOT NULL,
    author_email VARCHAR(254) NULL,
    authored_at TIMESTAMP(6) NOT NULL,
    parents_json JSON NOT NULL,
    UNIQUE KEY uk_commit_obs_source (project_id, object_format, oid, device_id, user_id),
    INDEX idx_commit_obs_proj_oid (project_id, oid, observed_at, id),
    FOREIGN KEY (project_id) REFERENCES git_projects(id)
) ENGINE=InnoDB;

CREATE TABLE git_refs (
    project_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    ref_name VARCHAR(300) NOT NULL,
    target_oid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    observed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (project_id, device_id, user_id, ref_name),
    INDEX idx_git_refs_proj_observed (project_id, observed_at, ref_name),
    FOREIGN KEY (project_id) REFERENCES git_projects(id)
) ENGINE=InnoDB;

CREATE TABLE worktree_states (
    id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    local_instance_id VARCHAR(128) NOT NULL,
    worktree_epoch BIGINT NOT NULL DEFAULT 1,
    current_capture_seq BIGINT NOT NULL DEFAULT 0,
    active_wip_snapshot_id VARCHAR(64) NULL,
    last_reported_at TIMESTAMP(6) NULL,
    stale_after_seconds BIGINT NOT NULL DEFAULT 86400,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_worktree_instance (project_id, user_id, device_id, local_instance_id),
    INDEX idx_worktree_active (project_id, active_wip_snapshot_id),
    FOREIGN KEY (project_id) REFERENCES git_projects(id)
) ENGINE=InnoDB;

CREATE TABLE wip_report_sessions (
    report_id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    space_id VARCHAR(64) NOT NULL,
    worktree_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    membership_epoch BIGINT NOT NULL,
    worktree_epoch BIGINT NOT NULL,
    capture_seq BIGINT NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_json JSON NOT NULL,
    patch_required BOOLEAN NOT NULL,
    expected_patch_size BIGINT NOT NULL DEFAULT 0,
    expected_patch_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    temp_object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status ENUM('OPEN','COMPLETED','EXPIRED') NOT NULL DEFAULT 'OPEN',
    snapshot_id VARCHAR(64) NULL,
    receipt_json JSON NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_report_idemp (worktree_id, user_id, device_id, idempotency_key),
    INDEX idx_report_worktree_epoch (worktree_id, worktree_epoch, status),
    FOREIGN KEY (worktree_id) REFERENCES worktree_states(id)
) ENGINE=InnoDB;

CREATE TABLE wip_snapshots (
    id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    space_id VARCHAR(64) NOT NULL,
    worktree_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    worktree_epoch BIGINT NOT NULL,
    capture_seq BIGINT NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    captured_at TIMESTAMP(6) NOT NULL,
    object_format ENUM('sha1','sha256') NOT NULL,
    base_kind ENUM('COMMIT','EMPTY_TREE') NOT NULL,
    base_commit_oid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    branch_name VARCHAR(300) NULL,
    capture_state ENUM('CHANGES','NO_SHARED_CHANGES') NOT NULL,
    data_level ENUM('METADATA_ONLY','PATCH_ONLY','TREE_AVAILABLE') NOT NULL,
    patch_object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NULL,
    patch_size_bytes BIGINT NOT NULL DEFAULT 0,
    patch_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    staging_summary_json JSON NOT NULL,
    scope_declaration_json JSON NOT NULL,
    note VARCHAR(4000) NULL,
    parse_state ENUM('NOT_REQUESTED','PENDING','RUNNING','SUCCEEDED','RETRY_WAIT','FAILED','CANCELLED') NOT NULL,
    parse_job_id VARCHAR(64) NULL,
    parse_result_object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lifecycle_state ENUM('ACTIVE','EXPIRED_OR_DELETED','FORCE_PURGED') NOT NULL DEFAULT 'ACTIVE',
    lifecycle_version BIGINT NOT NULL DEFAULT 1,
    freshness ENUM('ACTIVE','STALE') NOT NULL DEFAULT 'ACTIVE',
    reference_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_wip_worktree_epoch_seq (worktree_id, worktree_epoch, capture_seq),
    UNIQUE KEY uk_wip_idemp (worktree_id, user_id, device_id, idempotency_key),
    INDEX idx_wip_project_lifecycle (project_id, lifecycle_state, created_at),
    INDEX idx_wip_lifecycle_created (lifecycle_state, reference_count, created_at),
    INDEX idx_wip_worktree_freshness (worktree_id, freshness, lifecycle_state),
    FOREIGN KEY (project_id) REFERENCES git_projects(id),
    FOREIGN KEY (worktree_id) REFERENCES worktree_states(id)
) ENGINE=InnoDB;

CREATE TABLE content_manifests (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    snapshot_id VARCHAR(64) NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    status ENUM('AVAILABLE','ABSENT_CONFIRMED','NOT_SHARED','MISSING','UNSUPPORTED') NOT NULL,
    sha256_hex CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    INDEX idx_content_manifests_snapshot (snapshot_id, id),
    FOREIGN KEY (snapshot_id) REFERENCES wip_snapshots(id)
) ENGINE=InnoDB;

CREATE TABLE gitproject_idempotency_records (
    operation VARCHAR(64) NOT NULL,
    scope_id VARCHAR(64) NOT NULL,
    actor_user_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (operation, scope_id, actor_user_id, idempotency_key)
) ENGINE=InnoDB;
