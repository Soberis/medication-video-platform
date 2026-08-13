ALTER TABLE capture_session
    ADD COLUMN current_sync_revision_id UUID;

ALTER TABLE capture_session
    ADD CONSTRAINT fk_capture_session_current_sync_revision
        FOREIGN KEY (current_sync_revision_id)
        REFERENCES capture_sync_revision (capture_sync_revision_id) ON DELETE RESTRICT;

CREATE INDEX ix_capture_session_current_sync_revision
    ON capture_session (project_id, current_sync_revision_id)
    WHERE current_sync_revision_id IS NOT NULL;

CREATE TABLE capture_sync_artifact (
    capture_sync_artifact_id UUID PRIMARY KEY,
    capture_sync_revision_id UUID NOT NULL,
    artifact_role VARCHAR(64) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    logical_content_sha256 VARCHAR(64) NOT NULL,
    storage_bucket VARCHAR(255) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    artifact_manifest JSONB NOT NULL,
    artifact_manifest_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_capture_sync_artifact_revision
        FOREIGN KEY (capture_sync_revision_id)
        REFERENCES capture_sync_revision (capture_sync_revision_id) ON DELETE RESTRICT,
    CONSTRAINT uk_capture_sync_artifact_revision_role
        UNIQUE (capture_sync_revision_id, artifact_role),
    CONSTRAINT uk_capture_sync_artifact_storage UNIQUE (storage_bucket, object_key),
    CONSTRAINT ck_capture_sync_artifact_role CHECK (artifact_role = 'SYNCHRONIZED_FRAME_PARQUET'),
    CONSTRAINT ck_capture_sync_artifact_schema CHECK (schema_version = 'synchronized-frame.v1'),
    CONSTRAINT ck_capture_sync_artifact_file_name CHECK (file_name = 'synchronized-frames.parquet'),
    CONSTRAINT ck_capture_sync_artifact_media_type
        CHECK (media_type = 'application/vnd.apache.parquet'),
    CONSTRAINT ck_capture_sync_artifact_size CHECK (size_bytes > 0),
    CONSTRAINT ck_capture_sync_artifact_sha256 CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_capture_sync_artifact_logical_digest
        CHECK (logical_content_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_capture_sync_artifact_manifest_object
        CHECK (jsonb_typeof(artifact_manifest) = 'object'),
    CONSTRAINT ck_capture_sync_artifact_manifest_digest
        CHECK (artifact_manifest_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE INDEX ix_capture_sync_artifact_revision
    ON capture_sync_artifact (capture_sync_revision_id, created_at DESC);

ALTER TABLE inbox_message
    DROP CONSTRAINT ck_inbox_message_contract,
    ADD CONSTRAINT ck_inbox_message_contract CHECK (
        (consumer_name = 'media-probe-result-v1'
            AND message_type = 'MediaProbeResultV1')
        OR (consumer_name = 'dataset-export-result-v1'
            AND message_type = 'DatasetExportResultV1')
        OR (consumer_name = 'capture-sync-result-v1'
            AND message_type = 'CaptureSyncResultV1')
    );

CREATE TRIGGER trg_capture_sync_artifact_immutable
BEFORE UPDATE OR DELETE ON capture_sync_artifact
FOR EACH ROW
EXECUTE FUNCTION reject_capture_sync_revision_mutation();
