CREATE TABLE capture_event_log (
    capture_event_log_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    source_upload_file_id UUID NOT NULL,
    client_file_id UUID NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    storage_bucket VARCHAR(255) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    storage_etag VARCHAR(2048) NOT NULL,
    indexed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_capture_event_log_batch
        FOREIGN KEY (batch_id) REFERENCES upload_batch (batch_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_event_log_source_upload_file
        FOREIGN KEY (source_upload_file_id) REFERENCES upload_file (upload_file_id) ON DELETE RESTRICT,
    CONSTRAINT uk_capture_event_log_source_upload_file UNIQUE (source_upload_file_id),
    CONSTRAINT uk_capture_event_log_batch_client_file UNIQUE (batch_id, client_file_id),
    CONSTRAINT ck_capture_event_log_device_id
        CHECK (device_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_capture_event_log_content_type
        CHECK (content_type ~ '^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*$'),
    CONSTRAINT ck_capture_event_log_size CHECK (size_bytes > 0),
    CONSTRAINT ck_capture_event_log_sha256 CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_capture_event_log_storage_bucket CHECK (length(storage_bucket) > 0),
    CONSTRAINT ck_capture_event_log_object_key CHECK (length(object_key) > 0),
    CONSTRAINT ck_capture_event_log_storage_etag CHECK (length(storage_etag) > 0)
);

CREATE INDEX ix_capture_event_log_project_indexed
    ON capture_event_log (project_id, indexed_at DESC, capture_event_log_id DESC);

CREATE INDEX ix_capture_event_log_project_batch_indexed
    ON capture_event_log (project_id, batch_id, indexed_at DESC, capture_event_log_id DESC);

CREATE INDEX ix_capture_event_log_project_device_indexed
    ON capture_event_log (project_id, device_id, indexed_at DESC, capture_event_log_id DESC);

-- Preserve visibility for already-completed batches when this migration is applied to an
-- existing installation. PostgreSQL 18 provides gen_random_uuid() as a built-in function.
INSERT INTO capture_event_log (
    capture_event_log_id, project_id, batch_id, source_upload_file_id, client_file_id,
    device_id, content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
    indexed_at)
SELECT gen_random_uuid(), batch.project_id, batch.batch_id, file.upload_file_id, file.client_file_id,
       batch.device_id, file.content_type, file.size_bytes, file.verified_sha256,
       file.storage_bucket, file.object_key, file.completed_storage_etag,
       COALESCE(batch.completed_at, file.completion_finished_at, file.updated_at)
FROM upload_file file
JOIN upload_batch batch ON batch.batch_id = file.batch_id
WHERE file.file_kind = 'EVENT_LOG'
  AND file.status = 'UPLOADED'
  AND batch.status = 'COMPLETED';
