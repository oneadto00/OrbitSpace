CREATE TABLE spaces (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    owner_user_id VARCHAR(64) NOT NULL,
    quota_bytes BIGINT UNSIGNED NOT NULL DEFAULT 5368709120,
    used_bytes BIGINT UNSIGNED NOT NULL DEFAULT 0,
    status ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (owner_user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE space_members (
    space_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    status ENUM('ACTIVE','REMOVED','LEFT') NOT NULL DEFAULT 'ACTIVE',
    role ENUM('OWNER','ADMIN','MEMBER','VIEWER') NOT NULL,
    membership_epoch BIGINT NOT NULL DEFAULT 1,
    perm_version BIGINT NOT NULL DEFAULT 1,
    joined_baseline_seq BIGINT NOT NULL DEFAULT 0,
    recently_visited_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (space_id, user_id),
    UNIQUE KEY uk_space_members_pair (space_id, user_id),
    INDEX idx_space_members_user_status (user_id, status),
    FOREIGN KEY (space_id) REFERENCES spaces(id),
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE space_invites (
    id VARCHAR(64) PRIMARY KEY,
    space_id VARCHAR(64) NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    target_role ENUM('ADMIN','MEMBER','VIEWER') NOT NULL,
    max_uses BIGINT UNSIGNED NOT NULL,
    used_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_by_user_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_space_invites_space (space_id, created_at, id),
    FOREIGN KEY (space_id) REFERENCES spaces(id),
    FOREIGN KEY (created_by_user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE space_idempotency_records (
    operation VARCHAR(64) NOT NULL,
    actor_user_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_status INT NOT NULL,
    response_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (operation, actor_user_id, idempotency_key)
) ENGINE=InnoDB;
