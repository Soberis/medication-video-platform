CREATE TABLE capture_session (
    capture_session_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    camera_set_id UUID NOT NULL,
    metadata_upload_file_id UUID NOT NULL,
    metadata_schema_version VARCHAR(64) NOT NULL,
    metadata_sha256 VARCHAR(64) NOT NULL,
    expected_camera_count INTEGER NOT NULL,
    received_camera_count INTEGER NOT NULL DEFAULT 0,
    sync_group_id VARCHAR(128) NOT NULL,
    device_time_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    device_timezone VARCHAR(64) NOT NULL,
    server_receive_time TIMESTAMP WITH TIME ZONE NOT NULL,
    assembly_status VARCHAR(32) NOT NULL,
    quality_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_capture_session_batch
        FOREIGN KEY (batch_id) REFERENCES upload_batch (batch_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_session_camera_set
        FOREIGN KEY (camera_set_id) REFERENCES camera_set (camera_set_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_session_metadata_file
        FOREIGN KEY (metadata_upload_file_id) REFERENCES upload_file (upload_file_id) ON DELETE RESTRICT,
    CONSTRAINT uk_capture_session_batch UNIQUE (batch_id),
    CONSTRAINT uk_capture_session_metadata_file UNIQUE (metadata_upload_file_id),
    CONSTRAINT ck_capture_session_device_id
        CHECK (device_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_capture_session_metadata_schema
        CHECK (metadata_schema_version = 'capture-session-metadata-v1'),
    CONSTRAINT ck_capture_session_metadata_sha256
        CHECK (metadata_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_capture_session_expected_count
        CHECK (expected_camera_count BETWEEN 1 AND 64),
    CONSTRAINT ck_capture_session_received_count
        CHECK (received_camera_count BETWEEN 0 AND expected_camera_count),
    CONSTRAINT ck_capture_session_sync_group
        CHECK (sync_group_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_capture_session_timezone
        CHECK (device_timezone ~ '^[A-Za-z0-9][A-Za-z0-9_+./:-]{0,63}$'),
    CONSTRAINT ck_capture_session_assembly_status
        CHECK (assembly_status IN ('OPEN', 'ASSEMBLING', 'COMPLETE', 'INCOMPLETE', 'CLOSED')),
    CONSTRAINT ck_capture_session_quality_status
        CHECK (quality_status IN ('PENDING', 'PASS', 'WARNING', 'REJECT'))
);

CREATE INDEX ix_capture_session_project_status_created
    ON capture_session (project_id, assembly_status, created_at DESC, capture_session_id);

CREATE INDEX ix_capture_session_device_created
    ON capture_session (project_id, device_id, created_at DESC);

CREATE TABLE camera_set_snapshot (
    camera_set_snapshot_id UUID PRIMARY KEY,
    capture_session_id UUID NOT NULL,
    source_camera_set_id UUID NOT NULL,
    camera_set_key VARCHAR(128) NOT NULL,
    configuration_version INTEGER NOT NULL,
    layout_version VARCHAR(128) NOT NULL,
    expected_camera_count INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_camera_set_snapshot_session
        FOREIGN KEY (capture_session_id) REFERENCES capture_session (capture_session_id) ON DELETE RESTRICT,
    CONSTRAINT fk_camera_set_snapshot_source
        FOREIGN KEY (source_camera_set_id) REFERENCES camera_set (camera_set_id) ON DELETE RESTRICT,
    CONSTRAINT uk_camera_set_snapshot_session UNIQUE (capture_session_id),
    CONSTRAINT ck_camera_set_snapshot_configuration_version CHECK (configuration_version > 0),
    CONSTRAINT ck_camera_set_snapshot_expected_count CHECK (expected_camera_count BETWEEN 1 AND 64)
);

CREATE TABLE camera_set_snapshot_member (
    camera_set_snapshot_member_id UUID PRIMARY KEY,
    camera_set_snapshot_id UUID NOT NULL,
    camera_channel_id UUID NOT NULL,
    camera_index INTEGER NOT NULL,
    camera_role VARCHAR(64) NOT NULL,
    mount_position VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_camera_set_snapshot_member_snapshot
        FOREIGN KEY (camera_set_snapshot_id) REFERENCES camera_set_snapshot (camera_set_snapshot_id) ON DELETE RESTRICT,
    CONSTRAINT fk_camera_set_snapshot_member_channel
        FOREIGN KEY (camera_channel_id) REFERENCES camera_channel (camera_channel_id) ON DELETE RESTRICT,
    CONSTRAINT uk_camera_set_snapshot_member_channel
        UNIQUE (camera_set_snapshot_id, camera_channel_id),
    CONSTRAINT uk_camera_set_snapshot_member_index
        UNIQUE (camera_set_snapshot_id, camera_index),
    CONSTRAINT ck_camera_set_snapshot_member_index CHECK (camera_index BETWEEN 0 AND 63),
    CONSTRAINT ck_camera_set_snapshot_member_role
        CHECK (camera_role ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_camera_set_snapshot_member_mount
        CHECK (mount_position ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')
);

CREATE INDEX ix_camera_set_snapshot_member_snapshot_index
    ON camera_set_snapshot_member (camera_set_snapshot_id, camera_index);

CREATE TABLE camera_stream (
    camera_stream_id UUID PRIMARY KEY,
    capture_session_id UUID NOT NULL,
    camera_set_snapshot_member_id UUID NOT NULL,
    camera_channel_id UUID NOT NULL,
    camera_index INTEGER NOT NULL,
    clip_asset_id UUID NOT NULL,
    client_file_id UUID NOT NULL,
    record_start_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    time_offset_ms BIGINT NOT NULL,
    declared_sync_status VARCHAR(32) NOT NULL,
    stream_status VARCHAR(32) NOT NULL,
    quality_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_camera_stream_session
        FOREIGN KEY (capture_session_id) REFERENCES capture_session (capture_session_id) ON DELETE RESTRICT,
    CONSTRAINT fk_camera_stream_snapshot_member
        FOREIGN KEY (camera_set_snapshot_member_id)
        REFERENCES camera_set_snapshot_member (camera_set_snapshot_member_id) ON DELETE RESTRICT,
    CONSTRAINT fk_camera_stream_channel
        FOREIGN KEY (camera_channel_id) REFERENCES camera_channel (camera_channel_id) ON DELETE RESTRICT,
    CONSTRAINT fk_camera_stream_clip_asset
        FOREIGN KEY (clip_asset_id) REFERENCES clip_asset (clip_asset_id) ON DELETE RESTRICT,
    CONSTRAINT uk_camera_stream_session_channel UNIQUE (capture_session_id, camera_channel_id),
    CONSTRAINT uk_camera_stream_session_index UNIQUE (capture_session_id, camera_index),
    CONSTRAINT uk_camera_stream_session_snapshot_member
        UNIQUE (capture_session_id, camera_set_snapshot_member_id),
    CONSTRAINT uk_camera_stream_clip_asset UNIQUE (clip_asset_id),
    CONSTRAINT uk_camera_stream_session_client_file UNIQUE (capture_session_id, client_file_id),
    CONSTRAINT ck_camera_stream_index CHECK (camera_index BETWEEN 0 AND 63),
    CONSTRAINT ck_camera_stream_declared_sync_status
        CHECK (declared_sync_status IN ('UNKNOWN', 'PASS', 'WARNING', 'REJECT')),
    CONSTRAINT ck_camera_stream_status
        CHECK (stream_status IN ('BOUND', 'INGESTED', 'INGEST_FAILED')),
    CONSTRAINT ck_camera_stream_quality_status
        CHECK (quality_status IN ('PENDING', 'PASS', 'WARNING', 'REJECT'))
);

CREATE INDEX ix_camera_stream_session_index
    ON camera_stream (capture_session_id, camera_index);

CREATE INDEX ix_camera_stream_asset
    ON camera_stream (clip_asset_id);
