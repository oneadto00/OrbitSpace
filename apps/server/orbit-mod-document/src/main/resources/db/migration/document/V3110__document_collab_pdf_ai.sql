CREATE TABLE documents (
    id VARCHAR(64) PRIMARY KEY,
    space_id VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    persisted_batch_seq BIGINT NOT NULL DEFAULT 0,
    snapshot_seq BIGINT NOT NULL DEFAULT 0,
    snapshot_object_key VARCHAR(192),
    lifecycle_version BIGINT NOT NULL DEFAULT 1,
    lifecycle_state ENUM('ACTIVE','DELETED','FORCE_PURGED') NOT NULL DEFAULT 'ACTIVE',
    INDEX document_space (space_id,lifecycle_state,id),
    FOREIGN KEY (space_id) REFERENCES spaces(id)
) ENGINE=InnoDB;

CREATE TABLE document_sessions (
    id VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL,
    space_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    auth_session_id VARCHAR(64) NOT NULL,
    membership_epoch BIGINT NOT NULL,
    ticket_hash CHAR(64) NOT NULL UNIQUE,
    ticket_expires TIMESTAMP(6) NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX document_sessions_member (space_id,user_id,membership_epoch),
    FOREIGN KEY (document_id) REFERENCES documents(id)
) ENGINE=InnoDB;

CREATE TABLE document_update_batches (
    document_id VARCHAR(64) NOT NULL,
    batch_seq BIGINT NOT NULL,
    batch_id VARCHAR(160) NOT NULL,
    writer_id VARCHAR(64) NOT NULL,
    object_key VARCHAR(192) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    PRIMARY KEY (document_id,batch_seq),
    UNIQUE (document_id,batch_id),
    FOREIGN KEY (document_id) REFERENCES documents(id)
) ENGINE=InnoDB;

CREATE TABLE document_versions (
    id VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL,
    persisted_batch_seq BIGINT NOT NULL,
    snapshot_object_key VARCHAR(192) NOT NULL,
    snapshot_sha256 CHAR(64) NOT NULL,
    kind ENUM('AUTO','MANUAL','QUOTE','RESTORE_BACKUP','RESTORE_RESULT') NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX document_versions_boundary (document_id,persisted_batch_seq),
    INDEX document_versions_order (document_id,created_at,id),
    FOREIGN KEY (document_id) REFERENCES documents(id)
) ENGINE=InnoDB;

CREATE TABLE document_comments (
    id VARCHAR(64) PRIMARY KEY,
    space_id VARCHAR(64) NOT NULL,
    kind ENUM('DOC_REF','PDF_REF') NOT NULL,
    document_id VARCHAR(64),
    version_id VARCHAR(64),
    file_id VARCHAR(64),
    file_version_id VARCHAR(64),
    anchor_json JSON NOT NULL,
    body VARCHAR(8000) NOT NULL,
    discussion_thread_id VARCHAR(64) NOT NULL,
    FOREIGN KEY (version_id) REFERENCES document_versions(id),
    FOREIGN KEY (document_id) REFERENCES documents(id),
    FOREIGN KEY (discussion_thread_id) REFERENCES discussion_threads(root_msg_id),
    INDEX document_comments_file (file_id,file_version_id),
    INDEX document_comments_doc (document_id)
) ENGINE=InnoDB;

CREATE TABLE document_requests (
    scope_hash CHAR(64) PRIMARY KEY,
    request_digest CHAR(64) NOT NULL,
    result_json JSON
) ENGINE=InnoDB;

CREATE TABLE document_restore_previews (
    id VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    membership_epoch BIGINT NOT NULL,
    base_seq BIGINT NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    FOREIGN KEY (document_id) REFERENCES documents(id),
    FOREIGN KEY (version_id) REFERENCES document_versions(id)
) ENGINE=InnoDB;

CREATE TABLE space_ai_configs (
    space_id VARCHAR(64) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    allowed_kinds JSON NOT NULL,
    policy_version BIGINT NOT NULL DEFAULT 1,
    FOREIGN KEY (space_id) REFERENCES spaces(id)
) ENGINE=InnoDB;

CREATE TABLE document_ai_summaries (
    job_id VARCHAR(128) PRIMARY KEY,
    space_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    membership_epoch BIGINT NOT NULL,
    policy_version BIGINT NOT NULL,
    sources_json JSON NOT NULL,
    result_version_id VARCHAR(64),
    result_object_key VARCHAR(192),
    previewed BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB;

CREATE TABLE document_pdf_indexes (
    job_id VARCHAR(128) PRIMARY KEY,
    file_id VARCHAR(64) NOT NULL,
    file_version_id CHAR(64) NOT NULL,
    membership_epoch BIGINT NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    result_object_key VARCHAR(192),
    INDEX document_pdf_file (file_id,file_version_id)
) ENGINE=InnoDB;
