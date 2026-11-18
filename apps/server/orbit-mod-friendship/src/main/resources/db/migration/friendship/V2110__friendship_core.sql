CREATE TABLE friend_requests (
    id VARCHAR(64) PRIMARY KEY,
    requester_user_id VARCHAR(64) NOT NULL,
    recipient_user_id VARCHAR(64) NOT NULL,
    pair_low_id VARCHAR(64) NOT NULL,
    pair_high_id VARCHAR(64) NOT NULL,
    status ENUM('PENDING','ACCEPTED','REJECTED','CANCELLED') NOT NULL DEFAULT 'PENDING',
    pending_pair_key VARCHAR(132) GENERATED ALWAYS AS (
        CASE WHEN status = 'PENDING' THEN CONCAT(pair_low_id, ':', pair_high_id) ELSE NULL END
    ) STORED,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_friend_req_distinct CHECK (requester_user_id <> recipient_user_id),
    UNIQUE KEY uk_friend_req_pending_pair (pending_pair_key),
    INDEX idx_friend_req_recipient (recipient_user_id, status, updated_at, id),
    INDEX idx_friend_req_requester (requester_user_id, status, updated_at, id)
) ENGINE=InnoDB;

CREATE TABLE friendships (
    user_low_id VARCHAR(64) NOT NULL,
    user_high_id VARCHAR(64) NOT NULL,
    became_friends_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    source_request_id VARCHAR(64) NULL,
    PRIMARY KEY (user_low_id, user_high_id),
    CONSTRAINT chk_friendship_distinct CHECK (user_low_id <> user_high_id),
    INDEX idx_friendships_high (user_high_id, user_low_id),
    FOREIGN KEY (source_request_id) REFERENCES friend_requests(id)
) ENGINE=InnoDB;

CREATE TABLE friendship_idempotency_records (
    operation VARCHAR(64) NOT NULL,
    actor_user_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_status INT NOT NULL,
    response_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (operation, actor_user_id, idempotency_key)
) ENGINE=InnoDB;
