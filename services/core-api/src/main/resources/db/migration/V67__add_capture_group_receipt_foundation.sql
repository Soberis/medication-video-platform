ALTER TABLE external_video_ingest_manifest
    ADD COLUMN upload_set_finalized BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN upload_set_finalized_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE external_video_ingest_manifest
    DROP CONSTRAINT ck_external_manifest_schema,
    ADD CONSTRAINT ck_external_manifest_schema
        CHECK (schema_version IN ('external-video-ingest-v1', 'external-video-ingest-v2')),
    ADD CONSTRAINT ck_external_manifest_finalization
        CHECK ((schema_version = 'external-video-ingest-v1' AND upload_set_finalized = FALSE)
            OR schema_version = 'external-video-ingest-v2');

ALTER TABLE capture_session
    ADD COLUMN capture_group_key VARCHAR(128),
    ADD COLUMN capture_group_key_source VARCHAR(64),
    ADD COLUMN capture_group_manifest_id UUID,
    ADD COLUMN receipt_state VARCHAR(32) NOT NULL DEFAULT 'RECEIVING',
    ADD COLUMN receipt_finalized_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE capture_session
    ADD CONSTRAINT fk_capture_session_group_manifest
        FOREIGN KEY (capture_group_manifest_id)
        REFERENCES external_video_ingest_manifest (manifest_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_capture_session_group_key
        CHECK (capture_group_key IS NULL OR capture_group_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    ADD CONSTRAINT ck_capture_session_group_key_source
        CHECK (capture_group_key_source IS NULL OR capture_group_key_source IN
            ('EXTERNAL_CAPTURE_SESSION_ID', 'UPSTREAM_MANIFEST_KEY')),
    ADD CONSTRAINT ck_capture_session_receipt_state
        CHECK (receipt_state IN ('RECEIVING', 'COMPLETE', 'INCOMPLETE')),
    ADD CONSTRAINT ck_capture_session_receipt_finalization
        CHECK ((receipt_state = 'RECEIVING' AND receipt_finalized_at IS NULL)
            OR (receipt_state IN ('COMPLETE', 'INCOMPLETE') AND receipt_finalized_at IS NOT NULL));

CREATE INDEX ix_capture_session_project_receipt
    ON capture_session (project_id, receipt_state, created_at DESC, capture_session_id);
