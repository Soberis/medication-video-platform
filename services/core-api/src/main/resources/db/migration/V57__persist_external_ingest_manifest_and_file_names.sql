CREATE TABLE external_video_ingest_manifest (
    manifest_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    source_system VARCHAR(128) NOT NULL,
    source_device_id VARCHAR(128) NOT NULL,
    source_batch_id VARCHAR(128),
    capture_group_key VARCHAR(128) NOT NULL,
    key_source VARCHAR(64) NOT NULL,
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    expected_camera_numbers JSONB NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_external_manifest_group UNIQUE (project_id, capture_group_key, key_source),
    CONSTRAINT ck_external_manifest_schema CHECK (schema_version = 'external-video-ingest-v1'),
    CONSTRAINT ck_external_manifest_source_system CHECK (source_system ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_external_manifest_source_device CHECK (source_device_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_external_manifest_source_batch CHECK (
        source_batch_id IS NULL OR source_batch_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_external_manifest_group_key CHECK (capture_group_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_external_manifest_key_source CHECK (key_source IN ('EXTERNAL_CAPTURE_SESSION_ID', 'UPSTREAM_MANIFEST_KEY')),
    CONSTRAINT ck_external_manifest_timezone CHECK (timezone ~ '^[A-Za-z0-9][A-Za-z0-9_+./:-]{0,63}$'),
    CONSTRAINT ck_external_manifest_cameras CHECK (
        jsonb_typeof(expected_camera_numbers) = 'array'
        AND jsonb_array_length(expected_camera_numbers) BETWEEN 1 AND 64),
    CONSTRAINT ck_external_manifest_status CHECK (status IN ('ACCEPTED', 'BOUND'))
);

CREATE INDEX ix_external_manifest_project_captured
    ON external_video_ingest_manifest (project_id, captured_at DESC, manifest_id DESC);

CREATE TABLE external_video_ingest_manifest_video (
    manifest_video_id UUID PRIMARY KEY,
    manifest_id UUID NOT NULL,
    client_file_id UUID NOT NULL,
    camera_number INTEGER NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    declared_size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    record_start_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_external_manifest_video_manifest
        FOREIGN KEY (manifest_id) REFERENCES external_video_ingest_manifest (manifest_id) ON DELETE CASCADE,
    CONSTRAINT uk_external_manifest_video_file UNIQUE (manifest_id, client_file_id),
    CONSTRAINT uk_external_manifest_video_camera UNIQUE (manifest_id, camera_number),
    CONSTRAINT ck_external_manifest_video_camera CHECK (camera_number BETWEEN 1 AND 64),
    CONSTRAINT ck_external_manifest_video_file_name CHECK (length(file_name) BETWEEN 1 AND 255),
    CONSTRAINT ck_external_manifest_video_size CHECK (declared_size_bytes > 0),
    CONSTRAINT ck_external_manifest_video_sha256 CHECK (sha256 ~ '^[a-f0-9]{64}$')
);

CREATE INDEX ix_external_manifest_video_file
    ON external_video_ingest_manifest_video (client_file_id);

ALTER TABLE upload_batch
    ADD COLUMN external_manifest_id UUID,
    ADD CONSTRAINT fk_upload_batch_external_manifest
        FOREIGN KEY (external_manifest_id) REFERENCES external_video_ingest_manifest (manifest_id) ON DELETE RESTRICT;

CREATE INDEX ix_upload_batch_external_manifest
    ON upload_batch (project_id, external_manifest_id);

CREATE UNIQUE INDEX uk_upload_batch_external_manifest
    ON upload_batch (external_manifest_id)
    WHERE external_manifest_id IS NOT NULL;

ALTER TABLE upload_file
    ADD COLUMN file_name VARCHAR(255),
    ADD CONSTRAINT ck_upload_file_file_name CHECK (file_name IS NULL OR length(file_name) BETWEEN 1 AND 255);
