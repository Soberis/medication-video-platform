ALTER TABLE upload_batch
    ADD COLUMN upload_source_type VARCHAR(32) NOT NULL DEFAULT 'HOSPITAL_REMOTE',
    ADD COLUMN asset_folder_id UUID;

ALTER TABLE upload_batch
    ADD CONSTRAINT ck_upload_batch_source_type
        CHECK (upload_source_type IN ('HOSPITAL_REMOTE', 'PLATFORM_LOCAL')),
    ADD CONSTRAINT fk_upload_batch_asset_folder_project
        FOREIGN KEY (asset_folder_id, project_id)
        REFERENCES asset_folder (folder_id, project_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_upload_batch_source_folder
        CHECK (upload_source_type = 'PLATFORM_LOCAL' OR asset_folder_id IS NULL);

ALTER TABLE capture_session
    ALTER COLUMN metadata_upload_file_id DROP NOT NULL,
    ALTER COLUMN metadata_schema_version DROP NOT NULL,
    ALTER COLUMN metadata_sha256 DROP NOT NULL,
    ALTER COLUMN sync_group_id DROP NOT NULL,
    ALTER COLUMN device_time_utc DROP NOT NULL,
    ALTER COLUMN device_timezone DROP NOT NULL,
    DROP CONSTRAINT ck_capture_session_metadata_schema,
    DROP CONSTRAINT ck_capture_session_metadata_sha256,
    DROP CONSTRAINT ck_capture_session_sync_group,
    DROP CONSTRAINT ck_capture_session_timezone;

ALTER TABLE capture_session
    ADD CONSTRAINT ck_capture_session_metadata_schema
        CHECK (metadata_schema_version IS NULL OR metadata_schema_version IN ('capture-session-metadata-v1', 'platform-local-upload-v1')),
    ADD CONSTRAINT ck_capture_session_metadata_sha256
        CHECK (metadata_sha256 IS NULL OR metadata_sha256 ~ '^[a-f0-9]{64}$'),
    ADD CONSTRAINT ck_capture_session_local_metadata
        CHECK ((metadata_schema_version = 'capture-session-metadata-v1'
                AND metadata_upload_file_id IS NOT NULL
                AND metadata_sha256 IS NOT NULL)
            OR (metadata_schema_version = 'platform-local-upload-v1'
                AND metadata_upload_file_id IS NULL
                AND metadata_sha256 IS NULL)),
    ADD CONSTRAINT ck_capture_session_sync_group
        CHECK (sync_group_id IS NULL OR sync_group_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    ADD CONSTRAINT ck_capture_session_timezone
        CHECK (device_timezone IS NULL OR device_timezone ~ '^[A-Za-z0-9][A-Za-z0-9_+./:-]{0,63}$');

ALTER TABLE camera_stream
    ALTER COLUMN record_start_utc DROP NOT NULL;
