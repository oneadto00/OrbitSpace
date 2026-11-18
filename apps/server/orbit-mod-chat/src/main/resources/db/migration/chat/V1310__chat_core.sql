CREATE TABLE chat_rooms (
    id VARCHAR(64) PRIMARY KEY,
    space_id VARCHAR(64) NOT NULL,
    max_msg_seq BIGINT NOT NULL DEFAULT 0,
    max_event_seq BIGINT NOT NULL DEFAULT 0,
    min_retained_event_seq BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_chat_rooms_space (space_id),
    FOREIGN KEY (space_id) REFERENCES spaces(id)
) ENGINE=InnoDB;

CREATE TABLE chat_messages (
    id VARCHAR(64) PRIMARY KEY,
    room_id VARCHAR(64) NOT NULL,
    seq BIGINT NOT NULL,
    sender_id VARCHAR(64) NULL,
    client_msg_id VARCHAR(64) NULL,
    kind ENUM('TEXT','COMMIT_REF','WIP_REF','SYSTEM') NOT NULL,
    body_text VARCHAR(8000) NULL,
    ref_project_id VARCHAR(64) NULL,
    ref_target_id VARCHAR(128) NULL,
    ref_purpose VARCHAR(4000) NULL,
    reply_to_msg_id VARCHAR(64) NULL,
    payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    discussion_version BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_chat_messages_room_seq (room_id, seq),
    UNIQUE KEY uk_chat_messages_idemp (room_id, sender_id, client_msg_id),
    INDEX idx_chat_messages_room_seq (room_id, seq),
    INDEX idx_chat_messages_resource (room_id, ref_project_id, kind, ref_target_id),
    FOREIGN KEY (room_id) REFERENCES chat_rooms(id)
) ENGINE=InnoDB;

CREATE TABLE chat_events (
    id VARCHAR(64) PRIMARY KEY,
    room_id VARCHAR(64) NOT NULL,
    event_seq BIGINT NOT NULL,
    event_type ENUM('MESSAGE_CREATED','DISCUSSION_STATUS_CHANGED','RESOURCE_CARD_STATE_CHANGED') NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    target_version BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_chat_events_room_seq (room_id, event_seq),
    INDEX idx_chat_events_target (room_id, event_type, target_id),
    FOREIGN KEY (room_id) REFERENCES chat_rooms(id)
) ENGINE=InnoDB;

CREATE TABLE chat_read_states (
    room_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    membership_epoch BIGINT NOT NULL,
    joined_baseline_seq BIGINT NOT NULL DEFAULT 0,
    last_read_seq BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (room_id, user_id, membership_epoch),
    FOREIGN KEY (room_id) REFERENCES chat_rooms(id)
) ENGINE=InnoDB;

CREATE TABLE discussion_threads (
    root_msg_id VARCHAR(64) PRIMARY KEY,
    room_id VARCHAR(64) NOT NULL,
    status ENUM('OPEN','RESOLVED') NOT NULL DEFAULT 'OPEN',
    conclusion_msg_id VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_by_user_id VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (root_msg_id) REFERENCES chat_messages(id),
    FOREIGN KEY (room_id) REFERENCES chat_rooms(id)
) ENGINE=InnoDB;

CREATE TABLE chat_idempotency_records (
    operation VARCHAR(64) NOT NULL,
    room_id VARCHAR(64) NOT NULL,
    actor_user_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (operation, room_id, actor_user_id, idempotency_key)
) ENGINE=InnoDB;
