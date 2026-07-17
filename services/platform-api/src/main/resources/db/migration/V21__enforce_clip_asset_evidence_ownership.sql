ALTER TABLE clip_asset
    ADD CONSTRAINT uk_clip_asset_project_identity
        UNIQUE (project_id, clip_asset_id);

ALTER TABLE privacy_assessment
    DROP CONSTRAINT fk_privacy_assessment_clip_asset,
    ADD CONSTRAINT fk_privacy_assessment_clip_asset_project
        FOREIGN KEY (project_id, clip_asset_id)
        REFERENCES clip_asset (project_id, clip_asset_id) ON DELETE RESTRICT,
    ADD CONSTRAINT uk_privacy_assessment_quarantine_source
        UNIQUE (
            project_id,
            clip_asset_id,
            input_digest,
            privacy_assessment_id,
            assessment_version);

ALTER TABLE quarantine_record
    DROP CONSTRAINT fk_quarantine_record_clip_asset,
    ADD CONSTRAINT fk_quarantine_record_clip_asset_project
        FOREIGN KEY (project_id, clip_asset_id)
        REFERENCES clip_asset (project_id, clip_asset_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_quarantine_record_privacy_assessment_source
        FOREIGN KEY (
            project_id,
            clip_asset_id,
            input_digest,
            source_id,
            source_version)
        REFERENCES privacy_assessment (
            project_id,
            clip_asset_id,
            input_digest,
            privacy_assessment_id,
            assessment_version)
        ON DELETE RESTRICT;
