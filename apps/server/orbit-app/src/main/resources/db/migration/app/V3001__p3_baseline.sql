-- Record the P3 baseline before the document, integration and remote-source migrations.
INSERT INTO orbit_schema_meta (migration_version, database_charset)
VALUES (3001, (SELECT default_character_set_name
               FROM information_schema.schemata
               WHERE schema_name = DATABASE()));
