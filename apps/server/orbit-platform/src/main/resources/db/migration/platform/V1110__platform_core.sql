CREATE TABLE storage_objects (
    object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    bucket VARCHAR(63) CHARACTER SET ascii NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    sha256_hex CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status ENUM('TEMP_UPLOADED','IMMUTABLE','PENDING_GC','DELETED') NOT NULL,
    reference_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    unreferenced_since TIMESTAMP(6) NULL DEFAULT CURRENT_TIMESTAMP(6),
    retain_until TIMESTAMP(6) NULL,
    INDEX idx_storage_gc (unreferenced_since, object_key)
) ENGINE=InnoDB;

-- Insert/delete a row together with the business reference, in the same transaction.
-- A reference may be moved by deleting and inserting in that transaction.
CREATE TABLE storage_object_references (
    owner_type VARCHAR(64) NOT NULL,
    owner_id VARCHAR(192) NOT NULL,
    object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (owner_type, owner_id, object_key),
    FOREIGN KEY (object_key) REFERENCES storage_objects(object_key)
) ENGINE=InnoDB DEFAULT COLLATE=utf8mb4_bin;

DELIMITER //
CREATE TRIGGER storage_ref_attach AFTER INSERT ON storage_object_references FOR EACH ROW
BEGIN
    UPDATE storage_objects SET reference_count=reference_count+1, unreferenced_since=NULL
    WHERE object_key=NEW.object_key AND status='IMMUTABLE';
    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Object is unavailable for reference';
    END IF;
END//
CREATE TRIGGER storage_ref_immutable BEFORE UPDATE ON storage_object_references FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Replace object references by delete and insert';
END//
CREATE TRIGGER storage_ref_detach AFTER DELETE ON storage_object_references FOR EACH ROW
BEGIN
    UPDATE storage_objects SET unreferenced_since=IF(reference_count=1,CURRENT_TIMESTAMP(6),NULL),
                               reference_count=reference_count-1 WHERE object_key=OLD.object_key;
END//
DELIMITER ;

CREATE TABLE outbox_events (
    event_id VARCHAR(96) PRIMARY KEY, aggregate_type VARCHAR(96) NOT NULL,
    aggregate_id VARCHAR(192) NOT NULL, event_type VARCHAR(96) NOT NULL, payload JSON NOT NULL,
    input_digest CHAR(64) CHARACTER SET ascii NOT NULL,
    status ENUM('PENDING','PUBLISHED') NOT NULL DEFAULT 'PENDING',
    retries INT UNSIGNED NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_outbox_due (status,next_retry_at,event_id)
) ENGINE=InnoDB DEFAULT COLLATE=utf8mb4_bin;

CREATE TABLE async_jobs (
    job_id VARCHAR(128) PRIMARY KEY, job_type VARCHAR(96) NOT NULL, resource_type VARCHAR(96) NOT NULL,
    space_id VARCHAR(128) NOT NULL, resource_id VARCHAR(192) NOT NULL, actor_id VARCHAR(128) NOT NULL,
    input_object_key VARCHAR(192) NOT NULL, input_version BIGINT NOT NULL,
    processor_version VARCHAR(96) NOT NULL, expected_resource_lifecycle_version BIGINT NOT NULL,
    status ENUM('PENDING','RUNNING','SUCCEEDED','RETRY_WAIT','FAILED','CANCELLED') NOT NULL DEFAULT 'PENDING',
    lease_holder VARCHAR(64), lease_until TIMESTAMP(6) NULL, retries INT UNSIGNED NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), error_summary VARCHAR(64),
    result_object_key VARCHAR(192), created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_job_due (status,next_retry_at), INDEX idx_job_lease (status,lease_until)
) ENGINE=InnoDB DEFAULT COLLATE=utf8mb4_bin;

CREATE TABLE audit_logs (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, request_id VARCHAR(128) NOT NULL,
    actor_id VARCHAR(128), space_id VARCHAR(128), resource_id VARCHAR(192),
    action VARCHAR(96) NOT NULL, outcome VARCHAR(64) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
    INDEX idx_audit_actor_time (actor_id,created_at)
) ENGINE=InnoDB DEFAULT COLLATE=utf8mb4_bin;

-- SQL projection. The authoritative journal lives outside business snapshots.
CREATE TABLE revocation_ledger_state (
    id TINYINT PRIMARY KEY, ledger_id CHAR(36) NOT NULL, initialized BOOLEAN NOT NULL DEFAULT FALSE,
    applied_sequence BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_ledger_singleton CHECK (id=1)
) ENGINE=InnoDB;
INSERT INTO revocation_ledger_state(id,ledger_id) VALUES (1,UUID());

CREATE TABLE revocation_purge_records (
    sequence_no BIGINT PRIMARY KEY, event_id VARCHAR(96) NOT NULL UNIQUE,
    target_type ENUM('MEMBERSHIP_EPOCH','DEVICE_CREDENTIAL','RESOURCE_FORCE_PURGE') NOT NULL,
    space_id VARCHAR(128) NOT NULL, target_id VARCHAR(192) NOT NULL, target_version BIGINT NOT NULL,
    effective_at TIMESTAMP(6) NOT NULL, entry_digest CHAR(64) NOT NULL,
    INDEX idx_revocation_target (target_type,space_id,target_id,target_version)
) ENGINE=InnoDB DEFAULT COLLATE=utf8mb4_bin;
