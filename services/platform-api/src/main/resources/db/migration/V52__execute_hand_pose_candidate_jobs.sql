ALTER TABLE ai_job_attempt
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    ADD COLUMN worker_id VARCHAR(128),
    ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT ck_ai_job_attempt_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'));

CREATE INDEX ix_ai_job_attempt_active_lease
    ON ai_job_attempt (status, lease_until)
    WHERE status = 'RUNNING';
