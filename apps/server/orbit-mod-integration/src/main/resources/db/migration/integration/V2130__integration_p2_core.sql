CREATE TABLE integration_callback_nonces (
    nonce VARCHAR(128) PRIMARY KEY,
    method VARCHAR(16) CHARACTER SET ascii NOT NULL,
    request_path VARCHAR(255) NOT NULL,
    timestamp_epoch BIGINT NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_integration_nonces_ts (timestamp_epoch, created_at)
) ENGINE=InnoDB;
