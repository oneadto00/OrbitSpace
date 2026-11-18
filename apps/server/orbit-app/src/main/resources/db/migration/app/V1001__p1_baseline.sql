CREATE TABLE IF NOT EXISTS orbit_schema_meta (
    migration_version INT UNSIGNED PRIMARY KEY,
    database_charset VARCHAR(64) CHARACTER SET ascii NOT NULL,
    applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_orbit_database_utf8mb4 CHECK (database_charset = 'utf8mb4')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Persist the actual database default so this insert also enforces the charset precondition.
INSERT INTO orbit_schema_meta (migration_version, database_charset)
VALUES (1001, (SELECT default_character_set_name
               FROM information_schema.schemata
               WHERE schema_name = DATABASE()));
