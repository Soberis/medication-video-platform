ALTER TABLE capture_session
    ADD COLUMN capture_group_workflow BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE capture_session session
SET capture_group_workflow = TRUE
WHERE EXISTS (
    SELECT 1
    FROM external_video_ingest_manifest manifest
    WHERE manifest.manifest_id = session.capture_group_manifest_id
      AND manifest.schema_version = 'external-video-ingest-v2'
);

CREATE INDEX ix_capture_session_project_group_workflow
    ON capture_session (project_id, capture_group_workflow, created_at DESC, capture_session_id);
