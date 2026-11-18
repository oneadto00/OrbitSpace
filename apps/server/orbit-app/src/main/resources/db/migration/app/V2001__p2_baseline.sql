CREATE TABLE IF NOT EXISTS orbit_runtime_settings (
    setting_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    setting_value VARCHAR(512) NOT NULL,
    description VARCHAR(512) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO orbit_runtime_settings (setting_key, setting_value, description) VALUES
    ('p2.quota.space-default-bytes', '1073741824', 'Default storage quota per space in bytes (1 GiB).'),
    ('p2.quota.user-airdrop-bytes', '536870912', 'Default personal storage quota for independent airdrops in bytes (512 MiB).'),
    ('p2.upload.default-part-bytes', '5242880', 'Default chunked upload part size in bytes (5 MiB).'),
    ('p2.upload.max-part-bytes', '33554432', 'Maximum allowed chunked upload part size in bytes (32 MiB).'),
    ('p2.upload.max-file-bytes', '536870912', 'Maximum allowed single file upload size in bytes (512 MiB).'),
    ('p2.upload.session-ttl-seconds', '86400', 'Upload session validity window in seconds (24 hours).'),
    ('p2.download.session-ttl-seconds', '3600', 'Download session validity window in seconds (1 hour).')
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    description = VALUES(description);

INSERT INTO orbit_schema_meta (migration_version, database_charset)
VALUES (2001, (SELECT default_character_set_name
               FROM information_schema.schemata
               WHERE schema_name = DATABASE()));
