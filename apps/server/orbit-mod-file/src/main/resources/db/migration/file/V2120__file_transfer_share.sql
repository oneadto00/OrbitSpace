CREATE TABLE user_storage_quotas (
    user_id VARCHAR(64) PRIMARY KEY,
    quota_bytes BIGINT UNSIGNED NOT NULL DEFAULT 1073741824,
    used_bytes BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

CREATE TABLE quota_reservations (
    reservation_id VARCHAR(64) PRIMARY KEY,
    grant_type ENUM('SPACE_FILE','AIRDROP_SHARE') NOT NULL,
    owner_scope_id VARCHAR(64) NOT NULL,
    actor_user_id VARCHAR(64) NOT NULL,
    reserved_bytes BIGINT UNSIGNED NOT NULL,
    status ENUM('ACTIVE','CONSUMED','RELEASED','EXPIRED') NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_quota_res_scope (grant_type, owner_scope_id, status, expires_at)
) ENGINE=InnoDB;

CREATE TABLE files (
    id VARCHAR(64) PRIMARY KEY,
    scope ENUM('SPACE_FILE','AIRDROP_SHARE') NOT NULL,
    space_id VARCHAR(64) NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    sha256_hex CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lifecycle_state ENUM('ACTIVE','DELETED','FORCE_PURGED') NOT NULL DEFAULT 'ACTIVE',
    lifecycle_version BIGINT NOT NULL DEFAULT 1,
    thumbnail_job_id VARCHAR(64) NULL,
    thumbnail_object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NULL,
    thumbnail_ready BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_files_space_active (space_id, lifecycle_state, created_at, id),
    INDEX idx_files_owner_sha (owner_user_id, sha256_hex, lifecycle_state),
    INDEX idx_files_object (object_key)
) ENGINE=InnoDB;

CREATE TABLE upload_sessions (
    upload_id VARCHAR(64) PRIMARY KEY,
    actor_user_id VARCHAR(64) NOT NULL,
    target_scope ENUM('SPACE_FILE','AIRDROP_SHARE') NOT NULL,
    space_id VARCHAR(64) NULL,
    membership_epoch BIGINT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(160) NOT NULL,
    expected_size_bytes BIGINT UNSIGNED NOT NULL,
    expected_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    part_size_bytes BIGINT UNSIGNED NOT NULL,
    total_parts INT UNSIGNED NOT NULL,
    status ENUM('INITIATED','UPLOADING','COMPLETED','EXPIRED','CANCELLED') NOT NULL DEFAULT 'INITIATED',
    matched_file_id VARCHAR(64) NULL,
    completed_file_id VARCHAR(64) NULL,
    quota_reservation_id VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_upload_sessions_actor (actor_user_id, status, expires_at),
    INDEX idx_upload_sessions_expires (status, expires_at),
    FOREIGN KEY (quota_reservation_id) REFERENCES quota_reservations(reservation_id)
) ENGINE=InnoDB;

CREATE TABLE upload_parts (
    upload_id VARCHAR(64) NOT NULL,
    part_number INT UNSIGNED NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    sha256_hex CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (upload_id, part_number),
    UNIQUE KEY uk_upload_parts_num (upload_id, part_number),
    FOREIGN KEY (upload_id) REFERENCES upload_sessions(upload_id)
) ENGINE=InnoDB;

CREATE TABLE file_shares (
    share_id VARCHAR(64) PRIMARY KEY,
    sender_user_id VARCHAR(64) NOT NULL,
    source_space_id VARCHAR(64) NULL,
    title VARCHAR(255) NOT NULL,
    note VARCHAR(1000) NULL,
    expiry_preset ENUM('H24','D7','PERMANENT') NOT NULL,
    expires_at TIMESTAMP(6) NULL,
    max_downloads_per_recipient INT UNSIGNED NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_file_shares_sender (sender_user_id, created_at, share_id),
    INDEX idx_file_shares_expiry (revoked, expires_at)
) ENGINE=InnoDB;

CREATE TABLE file_share_files (
    share_id VARCHAR(64) NOT NULL,
    file_id VARCHAR(64) NOT NULL,
    sort_order INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (share_id, file_id),
    FOREIGN KEY (share_id) REFERENCES file_shares(share_id),
    FOREIGN KEY (file_id) REFERENCES files(id)
) ENGINE=InnoDB;

CREATE TABLE file_share_recipients (
    share_id VARCHAR(64) NOT NULL,
    recipient_user_id VARCHAR(64) NOT NULL,
    delivery_state ENUM('DELIVERED','VIEWED','DOWNLOAD_STARTED') NOT NULL DEFAULT 'DELIVERED',
    download_count INT UNSIGNED NOT NULL DEFAULT 0,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (share_id, recipient_user_id),
    INDEX idx_share_recipient_user (recipient_user_id, revoked, updated_at),
    FOREIGN KEY (share_id) REFERENCES file_shares(share_id)
) ENGINE=InnoDB;

CREATE TABLE download_sessions (
    download_session_id VARCHAR(64) PRIMARY KEY,
    actor_user_id VARCHAR(64) NOT NULL,
    grant_type ENUM('SPACE_FILE','AIRDROP_SHARE') NOT NULL,
    grant_id VARCHAR(64) NOT NULL,
    file_id VARCHAR(64) NOT NULL,
    space_membership_epoch BIGINT NULL,
    bytes_sent BIGINT UNSIGNED NOT NULL DEFAULT 0,
    revoked_at TIMESTAMP(6) NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_download_sessions_grant (grant_type, grant_id, actor_user_id),
    INDEX idx_download_sessions_file (file_id, revoked_at),
    FOREIGN KEY (file_id) REFERENCES files(id)
) ENGINE=InnoDB;

CREATE TABLE file_idempotency_records (
    operation VARCHAR(64) NOT NULL,
    actor_user_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_status INT NOT NULL,
    response_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (operation, actor_user_id, idempotency_key)
) ENGINE=InnoDB;
