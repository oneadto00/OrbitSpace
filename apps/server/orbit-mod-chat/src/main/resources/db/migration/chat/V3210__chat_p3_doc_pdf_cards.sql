ALTER TABLE chat_messages
    MODIFY COLUMN kind ENUM('TEXT','COMMIT_REF','WIP_REF','FILE_REF','CODE_COMMENT_REF','SYSTEM','DOC_REF','PDF_REF') NOT NULL,
    ADD INDEX idx_chat_messages_doc_pdf_ref (room_id, kind, ref_target_id, seq);

ALTER TABLE discussion_threads
    MODIFY COLUMN source_kind ENUM('CHAT_MESSAGE','CODE_COMMENT','DOC_COMMENT','PDF_COMMENT') NOT NULL DEFAULT 'CHAT_MESSAGE',
    ADD INDEX idx_discussion_threads_anchor (room_id, source_kind, source_anchor_id);

CREATE TABLE chat_doc_pdf_cards (
    message_id VARCHAR(64) PRIMARY KEY,
    room_id VARCHAR(64) NOT NULL,
    kind ENUM('DOC_REF','PDF_REF') NOT NULL,
    comment_id VARCHAR(64) NOT NULL,
    discussion_thread_id VARCHAR(64) NOT NULL,
    document_id VARCHAR(64) NULL,
    persisted_version_id VARCHAR(64) NULL,
    relative_start VARCHAR(1024) CHARACTER SET ascii COLLATE ascii_bin NULL,
    relative_end VARCHAR(1024) CHARACTER SET ascii COLLATE ascii_bin NULL,
    file_id VARCHAR(64) NULL,
    file_version_id VARCHAR(64) NULL,
    page_number INT NULL,
    rectangles_json JSON NULL,
    quoted_text VARCHAR(4096) NULL,
    context_before VARCHAR(512) NULL,
    context_after VARCHAR(512) NULL,
    lifecycle_state ENUM('ACTIVE','EXPIRED_OR_DELETED','FORCE_PURGED') NOT NULL DEFAULT 'ACTIVE',
    lifecycle_version BIGINT NOT NULL DEFAULT 1,
    invalidated_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_chat_doc_pdf_cards_room_comment (room_id, kind, comment_id),
    INDEX idx_chat_doc_pdf_cards_thread (discussion_thread_id),
    INDEX idx_chat_doc_pdf_cards_doc (room_id, document_id, persisted_version_id),
    INDEX idx_chat_doc_pdf_cards_file (room_id, file_id, file_version_id),
    FOREIGN KEY (message_id) REFERENCES chat_messages(id),
    FOREIGN KEY (room_id) REFERENCES chat_rooms(id),
    FOREIGN KEY (discussion_thread_id) REFERENCES discussion_threads(root_msg_id)
) ENGINE=InnoDB;
