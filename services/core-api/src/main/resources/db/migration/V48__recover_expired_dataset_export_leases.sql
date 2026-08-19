ALTER TABLE dataset_export_attempt
    ADD CONSTRAINT ck_dataset_export_attempt_lost_error CHECK (
        status <> 'LOST'
        OR (error_class = 'RETRYABLE_RESOURCE'
            AND error_code = 'WORKER_LEASE_LOST'
            AND error_retryable = TRUE)
    );

CREATE INDEX ix_dataset_export_attempt_expired_lease
    ON dataset_export_attempt (lease_until, dataset_export_id, attempt)
    WHERE status = 'RUNNING';
