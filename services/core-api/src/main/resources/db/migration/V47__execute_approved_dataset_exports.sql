ALTER TABLE dataset_export
    DROP CONSTRAINT ck_dataset_export_status,
    ADD COLUMN approval_decision_id UUID,
    ADD COLUMN approved_by VARCHAR(128),
    ADD COLUMN approval_idempotency_key VARCHAR(128),
    ADD COLUMN approval_request_fingerprint VARCHAR(64),
    ADD COLUMN approved_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN started_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT fk_dataset_export_approval_decision
        FOREIGN KEY (project_id, approval_request_id, approval_decision_id)
        REFERENCES approval_decision (
            project_id,
            approval_request_id,
            approval_decision_id
        ) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_dataset_export_status
        CHECK (status IN (
            'REQUESTED', 'QUEUED', 'RUNNING', 'RETRY_WAIT', 'AVAILABLE', 'FAILED'
        )),
    ADD CONSTRAINT ck_dataset_export_approved_by
        CHECK (approved_by IS NULL
            OR approved_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    ADD CONSTRAINT ck_dataset_export_approval_idempotency
        CHECK (approval_idempotency_key IS NULL
            OR approval_idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    ADD CONSTRAINT ck_dataset_export_approval_fingerprint
        CHECK (approval_request_fingerprint IS NULL
            OR approval_request_fingerprint ~ '^[a-f0-9]{64}$'),
    ADD CONSTRAINT ck_dataset_export_execution_state
        CHECK (
            (status = 'REQUESTED'
                AND approval_decision_id IS NULL
                AND approved_by IS NULL
                AND approval_idempotency_key IS NULL
                AND approval_request_fingerprint IS NULL
                AND approved_at IS NULL
                AND started_at IS NULL
                AND completed_at IS NULL)
            OR
			(status = 'QUEUED'
				AND approval_decision_id IS NOT NULL
				AND approved_by IS NOT NULL
				AND approval_idempotency_key IS NOT NULL
				AND approval_request_fingerprint IS NOT NULL
				AND approved_at IS NOT NULL
				AND started_at IS NULL
				AND completed_at IS NULL)
			OR
			(status = 'RETRY_WAIT'
				AND approval_decision_id IS NOT NULL
				AND approved_by IS NOT NULL
				AND approval_idempotency_key IS NOT NULL
				AND approval_request_fingerprint IS NOT NULL
				AND approved_at IS NOT NULL
				AND started_at IS NOT NULL
				AND completed_at IS NULL)
            OR
            (status = 'RUNNING'
                AND approval_decision_id IS NOT NULL
                AND approved_by IS NOT NULL
                AND approval_idempotency_key IS NOT NULL
                AND approval_request_fingerprint IS NOT NULL
                AND approved_at IS NOT NULL
                AND started_at IS NOT NULL
                AND completed_at IS NULL)
            OR
            (status IN ('AVAILABLE', 'FAILED')
                AND approval_decision_id IS NOT NULL
                AND approved_by IS NOT NULL
                AND approval_idempotency_key IS NOT NULL
                AND approval_request_fingerprint IS NOT NULL
                AND approved_at IS NOT NULL
                AND started_at IS NOT NULL
                AND completed_at IS NOT NULL)
        );

CREATE UNIQUE INDEX uk_dataset_export_approval_idempotency
    ON dataset_export (project_id, approved_by, approval_idempotency_key)
    WHERE approval_idempotency_key IS NOT NULL;

CREATE TABLE dataset_export_attempt (
    dataset_export_attempt_id UUID PRIMARY KEY,
    dataset_export_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    message_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    lease_token_hash VARCHAR(64) NOT NULL,
    worker_id VARCHAR(128),
    lease_started_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE,
    output_bucket VARCHAR(255),
    output_object_key VARCHAR(1024),
    output_upload_id VARCHAR(2048),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    error_class VARCHAR(64),
    error_code VARCHAR(64),
    safe_error_message VARCHAR(512),
    error_retryable BOOLEAN,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_dataset_export_attempt_export
        FOREIGN KEY (dataset_export_id)
        REFERENCES dataset_export (dataset_export_id) ON DELETE RESTRICT,
    CONSTRAINT uk_dataset_export_attempt_number UNIQUE (dataset_export_id, attempt),
    CONSTRAINT uk_dataset_export_attempt_message UNIQUE (message_id),
    CONSTRAINT ck_dataset_export_attempt_number CHECK (attempt > 0),
    CONSTRAINT ck_dataset_export_attempt_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'LOST')),
    CONSTRAINT ck_dataset_export_attempt_lease_hash
        CHECK (lease_token_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_dataset_export_attempt_worker
        CHECK (worker_id IS NULL
            OR worker_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_dataset_export_attempt_lease_times CHECK (
        (lease_started_at IS NULL AND lease_until IS NULL AND last_heartbeat_at IS NULL)
        OR (lease_started_at IS NOT NULL AND lease_until IS NOT NULL
            AND last_heartbeat_at IS NOT NULL AND lease_until > lease_started_at)
    ),
    CONSTRAINT ck_dataset_export_attempt_output CHECK (
        (output_bucket IS NULL AND output_object_key IS NULL AND output_upload_id IS NULL)
        OR (output_bucket IS NOT NULL AND output_object_key IS NOT NULL
            AND output_upload_id IS NOT NULL)
    ),
    CONSTRAINT ck_dataset_export_attempt_error CHECK (
        (status NOT IN ('FAILED', 'LOST')
            AND error_class IS NULL AND error_code IS NULL
            AND safe_error_message IS NULL AND error_retryable IS NULL)
        OR (status IN ('FAILED', 'LOST')
            AND error_class IS NOT NULL AND error_code IS NOT NULL
            AND safe_error_message IS NOT NULL AND error_retryable IS NOT NULL)
    ),
    CONSTRAINT ck_dataset_export_attempt_row_version CHECK (row_version >= 0)
);

CREATE INDEX ix_dataset_export_attempt_export_status
    ON dataset_export_attempt (dataset_export_id, status, attempt DESC);

CREATE TABLE export_artifact (
    export_artifact_id UUID PRIMARY KEY,
    dataset_export_id UUID NOT NULL,
    dataset_export_attempt_id UUID NOT NULL,
    artifact_role VARCHAR(32) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    storage_bucket VARCHAR(255) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    artifact_manifest JSONB NOT NULL,
    artifact_manifest_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_export_artifact_export
        FOREIGN KEY (dataset_export_id)
        REFERENCES dataset_export (dataset_export_id) ON DELETE RESTRICT,
    CONSTRAINT fk_export_artifact_attempt
        FOREIGN KEY (dataset_export_attempt_id)
        REFERENCES dataset_export_attempt (dataset_export_attempt_id) ON DELETE RESTRICT,
    CONSTRAINT uk_export_artifact_export_role UNIQUE (dataset_export_id, artifact_role),
    CONSTRAINT uk_export_artifact_storage UNIQUE (storage_bucket, object_key),
    CONSTRAINT ck_export_artifact_role CHECK (artifact_role = 'DATASET_PACKAGE'),
    CONSTRAINT ck_export_artifact_file_name CHECK (file_name = 'dataset-export.zip'),
    CONSTRAINT ck_export_artifact_media_type CHECK (media_type = 'application/zip'),
    CONSTRAINT ck_export_artifact_size CHECK (size_bytes > 0),
    CONSTRAINT ck_export_artifact_sha256 CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_export_artifact_manifest_object
        CHECK (jsonb_typeof(artifact_manifest) = 'object'),
    CONSTRAINT ck_export_artifact_manifest_digest
        CHECK (artifact_manifest_digest ~ '^[a-f0-9]{64}$')
);

ALTER TABLE inbox_message
    DROP CONSTRAINT ck_inbox_message_consumer,
    DROP CONSTRAINT ck_inbox_message_type,
    ADD CONSTRAINT ck_inbox_message_contract CHECK (
        (consumer_name = 'media-probe-result-v1'
            AND message_type = 'MediaProbeResultV1')
        OR (consumer_name = 'dataset-export-result-v1'
            AND message_type = 'DatasetExportResultV1')
    );

ALTER TABLE outbox_event
    DROP CONSTRAINT ck_outbox_media_capability_redacted,
    ADD CONSTRAINT ck_outbox_capability_redacted CHECK (
        published_at IS NULL
        OR schema_version NOT IN ('media-probe-job.v1', 'dataset-export-job.v1')
        OR NOT jsonb_exists(payload_json, 'lease_token')
    );
