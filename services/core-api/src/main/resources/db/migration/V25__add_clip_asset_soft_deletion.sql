ALTER TABLE clip_asset
    ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN deleted_by VARCHAR(128),
    ADD COLUMN delete_reason VARCHAR(500),
    ADD CONSTRAINT ck_clip_asset_deletion_state
        CHECK (
            (deleted_at IS NULL AND deleted_by IS NULL AND delete_reason IS NULL)
            OR
            (deleted_at IS NOT NULL
                AND deleted_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'
                AND delete_reason = btrim(delete_reason)
                AND char_length(delete_reason) BETWEEN 1 AND 500
                AND delete_reason !~ '[[:cntrl:]]')
        );

ALTER TABLE camera_stream
    DROP CONSTRAINT ck_camera_stream_status,
    ADD CONSTRAINT ck_camera_stream_status
        CHECK (stream_status IN ('BOUND', 'INGESTED', 'INGEST_FAILED', 'UNAVAILABLE'));

CREATE INDEX ix_clip_asset_project_active_created
    ON clip_asset (project_id, created_at DESC, clip_asset_id DESC)
    WHERE deleted_at IS NULL;
