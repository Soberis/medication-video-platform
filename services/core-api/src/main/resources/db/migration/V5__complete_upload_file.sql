ALTER TABLE upload_file
    ADD COLUMN completion_attempt_id UUID,
    ADD COLUMN completion_started_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN completion_lease_expires_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN completion_finished_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN verified_sha256 VARCHAR(64),
    ADD COLUMN completed_storage_etag VARCHAR(2048),
    ADD COLUMN completion_error_code VARCHAR(64);

ALTER TABLE upload_file
    DROP CONSTRAINT ck_upload_file_status,
    DROP CONSTRAINT ck_upload_file_multipart_fields,
    ADD CONSTRAINT ck_upload_file_status
        CHECK (status IN ('REGISTERED', 'SESSION_CREATED', 'UPLOADING', 'COMPLETING', 'UPLOADED', 'FAILED')),
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
        ),
    ADD CONSTRAINT ck_upload_file_completion_fields
        CHECK (
            (status IN ('REGISTERED', 'SESSION_CREATED', 'UPLOADING')
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
