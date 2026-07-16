ALTER TABLE upload_batch
    ADD COLUMN initiated_by_actor_id VARCHAR(128),
    ADD COLUMN terminated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN termination_reason_code VARCHAR(64);

UPDATE upload_batch
SET initiated_by_actor_id = 'legacy-system'
WHERE initiated_by_actor_id IS NULL;

UPDATE upload_batch
SET terminated_at = updated_at,
    termination_reason_code = CASE status
        WHEN 'ABORTED' THEN 'LEGACY_ABORTED'
        ELSE 'LEGACY_EXPIRED'
    END
WHERE status IN ('ABORTED', 'EXPIRED');

ALTER TABLE upload_batch
    ALTER COLUMN initiated_by_actor_id SET NOT NULL,
    ADD CONSTRAINT ck_upload_batch_initiated_by_actor
        CHECK (initiated_by_actor_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    ADD CONSTRAINT ck_upload_batch_termination
        CHECK (
            (status IN ('ABORTED', 'EXPIRED')
                AND terminated_at IS NOT NULL
                AND termination_reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$')
            OR
            (status NOT IN ('ABORTED', 'EXPIRED')
                AND terminated_at IS NULL
                AND termination_reason_code IS NULL)
        );

ALTER TABLE upload_file
    DROP CONSTRAINT ck_upload_file_status,
    DROP CONSTRAINT ck_upload_file_multipart_fields,
    DROP CONSTRAINT ck_upload_file_completion_fields,
    ADD CONSTRAINT ck_upload_file_status
        CHECK (status IN (
            'REGISTERED', 'SESSION_CREATED', 'UPLOADING', 'COMPLETING',
            'UPLOADED', 'FAILED', 'ABORTED', 'EXPIRED')),
    ADD CONSTRAINT ck_upload_file_multipart_fields
        CHECK (
            (status = 'REGISTERED'
                AND storage_bucket IS NULL
                AND object_key IS NULL
                AND storage_upload_id IS NULL
                AND multipart_created_at IS NULL)
            OR
            (status IN ('SESSION_CREATED', 'UPLOADING', 'COMPLETING', 'UPLOADED', 'FAILED')
                AND length(storage_bucket) > 0
                AND length(object_key) > 0
                AND length(storage_upload_id) > 0
                AND multipart_created_at IS NOT NULL)
            OR
            (status IN ('ABORTED', 'EXPIRED')
                AND (
                    (storage_bucket IS NULL
                        AND object_key IS NULL
                        AND storage_upload_id IS NULL
                        AND multipart_created_at IS NULL)
                    OR
                    (length(storage_bucket) > 0
                        AND length(object_key) > 0
                        AND length(storage_upload_id) > 0
                        AND multipart_created_at IS NOT NULL)
                ))
        ),
    ADD CONSTRAINT ck_upload_file_completion_fields
        CHECK (
            (status IN ('REGISTERED', 'SESSION_CREATED', 'UPLOADING', 'ABORTED', 'EXPIRED')
                AND completion_attempt_id IS NULL
                AND completion_started_at IS NULL
                AND completion_lease_expires_at IS NULL
                AND completion_finished_at IS NULL
                AND verified_sha256 IS NULL
                AND completed_storage_etag IS NULL
                AND completion_error_code IS NULL)
            OR
            (status = 'COMPLETING'
                AND completion_attempt_id IS NOT NULL
                AND completion_started_at IS NOT NULL
                AND completion_lease_expires_at >= completion_started_at
                AND completion_finished_at IS NULL
                AND verified_sha256 IS NULL
                AND completed_storage_etag IS NULL
                AND completion_error_code IS NULL)
            OR
            (status = 'UPLOADED'
                AND completion_attempt_id IS NULL
                AND completion_started_at IS NOT NULL
                AND completion_lease_expires_at IS NULL
                AND completion_finished_at IS NOT NULL
                AND verified_sha256 ~ '^[a-f0-9]{64}$'
                AND length(completed_storage_etag) > 0
                AND completion_error_code IS NULL)
            OR
            (status = 'FAILED'
                AND completion_attempt_id IS NULL
                AND completion_started_at IS NOT NULL
                AND completion_lease_expires_at IS NULL
                AND completion_finished_at IS NOT NULL
                AND (verified_sha256 IS NULL OR verified_sha256 ~ '^[a-f0-9]{64}$')
                AND length(completed_storage_etag) > 0
                AND length(completion_error_code) > 0)
        );

CREATE TABLE upload_cleanup_task (
    cleanup_task_id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    upload_file_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claim_token UUID,
    claimed_until TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_upload_cleanup_task_batch
        FOREIGN KEY (batch_id) REFERENCES upload_batch (batch_id) ON DELETE RESTRICT,
    CONSTRAINT fk_upload_cleanup_task_file
        FOREIGN KEY (upload_file_id) REFERENCES upload_file (upload_file_id) ON DELETE RESTRICT,
    CONSTRAINT uk_upload_cleanup_task_file UNIQUE (upload_file_id),
    CONSTRAINT ck_upload_cleanup_task_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_upload_cleanup_task_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_upload_cleanup_task_last_error
        CHECK (last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    CONSTRAINT ck_upload_cleanup_task_state
        CHECK (
            (status = 'PENDING'
                AND claim_token IS NULL
                AND claimed_until IS NULL
                AND completed_at IS NULL)
            OR
            (status = 'RUNNING'
                AND claim_token IS NOT NULL
                AND claimed_until IS NOT NULL
                AND completed_at IS NULL)
            OR
            (status = 'SUCCEEDED'
                AND claim_token IS NULL
                AND claimed_until IS NULL
                AND last_error_code IS NULL
                AND completed_at IS NOT NULL)
            OR
            (status = 'FAILED'
                AND claim_token IS NULL
                AND claimed_until IS NULL
                AND last_error_code IS NOT NULL
                AND completed_at IS NOT NULL)
        )
);

CREATE INDEX ix_upload_cleanup_task_pending
    ON upload_cleanup_task (available_at, created_at, cleanup_task_id)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX ix_upload_cleanup_task_batch_status
    ON upload_cleanup_task (batch_id, status);
