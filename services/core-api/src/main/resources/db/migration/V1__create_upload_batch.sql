CREATE TABLE upload_batch (
    batch_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    file_count INTEGER NOT NULL,
    total_size_bytes BIGINT NOT NULL,
    manifest_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_upload_batch_project_idempotency UNIQUE (project_id, idempotency_key),
    CONSTRAINT ck_upload_batch_idempotency_key
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_upload_batch_request_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_upload_batch_device_id
        CHECK (device_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CONSTRAINT ck_upload_batch_file_count CHECK (file_count > 0),
    CONSTRAINT ck_upload_batch_total_size CHECK (total_size_bytes > 0),
    CONSTRAINT ck_upload_batch_manifest_sha256
        CHECK (manifest_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_upload_batch_status
        CHECK (status IN ('INITIATED', 'UPLOADING', 'COMPLETING', 'COMPLETED', 'FAILED', 'ABORTED', 'EXPIRED')),
    CONSTRAINT ck_upload_batch_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_upload_batch_project_status_created
    ON upload_batch (project_id, status, created_at DESC);

CREATE INDEX ix_upload_batch_active_expiry
    ON upload_batch (expires_at)
    WHERE status IN ('INITIATED', 'UPLOADING');
