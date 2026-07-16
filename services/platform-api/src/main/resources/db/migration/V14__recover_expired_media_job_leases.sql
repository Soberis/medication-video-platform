ALTER TABLE media_job_attempt
    DROP CONSTRAINT ck_media_job_attempt_status,
    DROP CONSTRAINT ck_media_job_attempt_error_fields;

ALTER TABLE media_job_attempt
    ADD CONSTRAINT ck_media_job_attempt_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'LOST', 'CANCELLED')),
    ADD CONSTRAINT ck_media_job_attempt_error_fields CHECK (
        (status NOT IN ('FAILED', 'LOST')
            AND error_class IS NULL AND error_code IS NULL
            AND safe_error_message IS NULL AND error_retryable IS NULL)
        OR (status IN ('FAILED', 'LOST')
            AND error_class IS NOT NULL AND error_code IS NOT NULL
            AND safe_error_message IS NOT NULL AND error_retryable IS NOT NULL)
    ),
    ADD CONSTRAINT ck_media_job_attempt_lost_error CHECK (
        status <> 'LOST'
        OR (error_class = 'RETRYABLE_RESOURCE'
            AND error_code = 'WORKER_LEASE_LOST'
            AND error_retryable = TRUE)
    );

CREATE INDEX ix_media_job_attempt_expired_lease
    ON media_job_attempt (lease_until, media_job_id, attempt)
    WHERE status = 'RUNNING';
