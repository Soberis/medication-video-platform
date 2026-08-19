-- Keep manifest bindings project-scoped at the database boundary. The original
-- V57 foreign key checked only manifest_id, which allowed a cross-project row
-- to be inserted by a direct SQL client even though the application validates
-- project ownership.
ALTER TABLE external_video_ingest_manifest
    ADD CONSTRAINT uk_external_manifest_project_manifest
        UNIQUE (project_id, manifest_id);

ALTER TABLE upload_batch
    DROP CONSTRAINT fk_upload_batch_external_manifest;

ALTER TABLE upload_batch
    ADD CONSTRAINT fk_upload_batch_external_manifest_project
        FOREIGN KEY (project_id, external_manifest_id)
        REFERENCES external_video_ingest_manifest (project_id, manifest_id)
        ON DELETE RESTRICT;

ALTER TABLE upload_batch
    ADD CONSTRAINT ck_upload_batch_external_manifest_source
        CHECK (external_manifest_id IS NULL OR upload_source_type = 'HOSPITAL_REMOTE');

-- V57 could leave a BOUND manifest behind when its only batch was aborted or
-- expired. Repair only that unambiguous terminal case; an active or completed
-- batch remains the source of truth and keeps the manifest BOUND.
UPDATE external_video_ingest_manifest manifest
SET status = 'ACCEPTED',
    updated_at = CURRENT_TIMESTAMP
WHERE manifest.status = 'BOUND'
  AND EXISTS (
      SELECT 1
      FROM upload_batch terminated_batch
      WHERE terminated_batch.project_id = manifest.project_id
        AND terminated_batch.external_manifest_id = manifest.manifest_id
        AND terminated_batch.upload_source_type = 'HOSPITAL_REMOTE'
        AND terminated_batch.status IN ('ABORTED', 'EXPIRED'))
  AND NOT EXISTS (
      SELECT 1
      FROM upload_batch active_batch
      WHERE active_batch.project_id = manifest.project_id
        AND active_batch.external_manifest_id = manifest.manifest_id
        AND active_batch.status NOT IN ('ABORTED', 'EXPIRED'));

-- An aborted/expired batch retains its immutable external_manifest_id for audit
-- history, but releases the manifest for one controlled retry. Completed facts
-- remain unique and can never be rebound.
DROP INDEX uk_upload_batch_external_manifest;

CREATE UNIQUE INDEX uk_upload_batch_external_manifest_active
    ON upload_batch (external_manifest_id)
    WHERE external_manifest_id IS NOT NULL
      AND status NOT IN ('ABORTED', 'EXPIRED');
