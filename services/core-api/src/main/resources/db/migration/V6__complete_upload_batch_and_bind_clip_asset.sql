ALTER TABLE upload_batch
    ADD COLUMN verified_manifest_sha256 VARCHAR(64),
    ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT ck_upload_batch_completion_fields
        CHECK (
            (status = 'COMPLETED'
                AND verified_manifest_sha256 ~ '^[a-f0-9]{64}$'
                AND completed_at IS NOT NULL)
            OR
            (status <> 'COMPLETED'
                AND verified_manifest_sha256 IS NULL
                AND completed_at IS NULL)
        );

CREATE TABLE clip_asset (
    clip_asset_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    source_upload_file_id UUID NOT NULL,
    client_file_id UUID NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    storage_bucket VARCHAR(255) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    storage_etag VARCHAR(2048) NOT NULL,
    asset_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_clip_asset_batch
        FOREIGN KEY (batch_id) REFERENCES upload_batch (batch_id) ON DELETE RESTRICT,
    CONSTRAINT fk_clip_asset_source_upload_file
        FOREIGN KEY (source_upload_file_id) REFERENCES upload_file (upload_file_id) ON DELETE RESTRICT,
    CONSTRAINT uk_clip_asset_source_upload_file UNIQUE (source_upload_file_id),
    CONSTRAINT ck_clip_asset_content_type
        CHECK (content_type ~ '^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*$'),
    CONSTRAINT ck_clip_asset_size CHECK (size_bytes > 0),
    CONSTRAINT ck_clip_asset_sha256 CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_clip_asset_storage_bucket CHECK (length(storage_bucket) > 0),
    CONSTRAINT ck_clip_asset_object_key CHECK (length(object_key) > 0),
    CONSTRAINT ck_clip_asset_storage_etag CHECK (length(storage_etag) > 0),
    CONSTRAINT ck_clip_asset_status
        CHECK (asset_status IN ('UPLOADED', 'INGESTING', 'INGESTED', 'INGEST_FAILED'))
);

CREATE INDEX ix_clip_asset_project_status_created
    ON clip_asset (project_id, asset_status, created_at DESC);

CREATE INDEX ix_clip_asset_batch
    ON clip_asset (batch_id, client_file_id);
