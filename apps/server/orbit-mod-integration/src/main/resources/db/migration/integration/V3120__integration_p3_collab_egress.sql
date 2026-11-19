CREATE TABLE integration_job_grants (
    job_id VARCHAR(128) PRIMARY KEY,
    membership_epoch BIGINT NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    FOREIGN KEY (job_id) REFERENCES async_jobs(job_id)
) ENGINE=InnoDB DEFAULT COLLATE=utf8mb4_bin;

CREATE TABLE integration_ai_sources (
    job_id VARCHAR(128) NOT NULL,
    ordinal_no INT UNSIGNED NOT NULL,
    source_kind VARCHAR(32) NOT NULL,
    resource_id VARCHAR(160) NOT NULL,
    version_id VARCHAR(160) NOT NULL,
    lifecycle_version BIGINT NOT NULL,
    PRIMARY KEY (job_id, ordinal_no),
    FOREIGN KEY (job_id) REFERENCES integration_job_grants(job_id)
) ENGINE=InnoDB DEFAULT COLLATE=utf8mb4_bin;
