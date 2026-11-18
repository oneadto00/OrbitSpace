CREATE TABLE users (
    id VARCHAR(64) PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

CREATE TABLE auth_challenges (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    pending_password_hash VARCHAR(100),
    purpose ENUM('VERIFY','RESET') NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    INDEX (user_id, purpose),
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

-- A session is one independent refresh family. Keep rotated hashes for its lifetime.
CREATE TABLE auth_sessions (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    refresh_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    device_label VARCHAR(160) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_used_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    INDEX (user_id, revoked_at, expires_at),
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE auth_refresh_history (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    FOREIGN KEY (session_id) REFERENCES auth_sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE devices (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    label VARCHAR(160) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE (id, user_id),
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE device_authorization_requests (
    device_code_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_code CHAR(9) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    device_label VARCHAR(160) NOT NULL,
    requested_scopes VARCHAR(80) NOT NULL,
    status ENUM('PENDING','APPROVED','DENIED','CONSUMED') NOT NULL DEFAULT 'PENDING',
    interval_seconds INT UNSIGNED NOT NULL,
    next_poll_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    user_id VARCHAR(64),
    space_id VARCHAR(64),
    project_id VARCHAR(64),
    membership_epoch BIGINT,
    granted_scopes VARCHAR(80),
    FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX (space_id, user_id, membership_epoch),
    INDEX (project_id),
    INDEX (expires_at)
) ENGINE=InnoDB;

CREATE TABLE device_credentials (
    id VARCHAR(64) PRIMARY KEY,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    device_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    space_id VARCHAR(64) NOT NULL,
    project_id VARCHAR(64) NOT NULL,
    membership_epoch BIGINT NOT NULL CHECK (membership_epoch > 0),
    scopes VARCHAR(80) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    status ENUM('ACTIVE','REVOKED') NOT NULL DEFAULT 'ACTIVE',
    revoked_at TIMESTAMP(6) NULL,
    INDEX (space_id, user_id, membership_epoch, status),
    INDEX (project_id),
    INDEX (user_id, status),
    FOREIGN KEY (device_id, user_id) REFERENCES devices(id, user_id)
) ENGINE=InnoDB;

-- Serialize grant creation with revocation, including approved requests still awaiting a poll.
CREATE TABLE device_epoch_fences (
    space_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    ended_epoch BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (space_id, user_id),
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;
