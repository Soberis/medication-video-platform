CREATE TABLE capture_group_quality_review (
    capture_group_quality_review_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    capture_group_id UUID NOT NULL,
    result VARCHAR(32) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    reviewer_actor_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    confirmed_view_set JSONB NOT NULL,
    input_version VARCHAR(128) NOT NULL,
    confirmed_view_set_sha256 VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_capture_group_quality_revision
        UNIQUE (project_id, capture_group_id, revision),
    CONSTRAINT uk_capture_group_quality_idempotency
        UNIQUE (project_id, capture_group_id, reviewer_actor_id, idempotency_key),
    CONSTRAINT fk_capture_group_quality_group
        FOREIGN KEY (capture_group_id) REFERENCES capture_session (capture_session_id) ON DELETE RESTRICT,
    CONSTRAINT ck_capture_group_quality_result
        CHECK (result IN ('QUALIFIED', 'QUALIFIED_WITH_EXCLUSIONS', 'NEEDS_REVIEW', 'REJECTED')),
    CONSTRAINT ck_capture_group_quality_reason
        CHECK (length(btrim(reason)) > 0 AND reason !~ '[[:cntrl:]]'),
    CONSTRAINT ck_capture_group_quality_actor
        CHECK (reviewer_actor_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_capture_group_quality_revision
        CHECK (revision > 0),
    CONSTRAINT ck_capture_group_quality_view_set
        CHECK (jsonb_typeof(confirmed_view_set) = 'object'),
    CONSTRAINT ck_capture_group_quality_input_version
        CHECK (input_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_capture_group_quality_digest
        CHECK (confirmed_view_set_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_capture_group_quality_idempotency
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$')
);

CREATE INDEX ix_capture_group_quality_current
    ON capture_group_quality_review (project_id, capture_group_id, revision DESC);

ALTER TABLE annotation_task
    ALTER COLUMN clip_asset_id DROP NOT NULL,
    ADD COLUMN capture_group_id UUID,
    ADD COLUMN primary_clip_asset_id UUID,
    ADD COLUMN primary_camera_number INTEGER,
    ADD COLUMN capture_group_quality_review_id UUID,
    ADD COLUMN group_quality_revision BIGINT,
    ADD COLUMN confirmed_view_set JSONB,
    ADD COLUMN group_input_version VARCHAR(128);

ALTER TABLE annotation_task
    ADD CONSTRAINT fk_annotation_task_capture_group
        FOREIGN KEY (capture_group_id) REFERENCES capture_session (capture_session_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_annotation_task_primary_clip_asset
        FOREIGN KEY (primary_clip_asset_id) REFERENCES clip_asset (clip_asset_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_annotation_task_group_quality_review
        FOREIGN KEY (capture_group_quality_review_id)
        REFERENCES capture_group_quality_review (capture_group_quality_review_id) ON DELETE RESTRICT,
    ADD CONSTRAINT uk_annotation_task_capture_group
        UNIQUE (project_id, capture_group_id),
    ADD CONSTRAINT ck_annotation_task_owner
        CHECK (
            (capture_group_id IS NULL
                AND clip_asset_id IS NOT NULL
                AND primary_clip_asset_id IS NULL
                AND primary_camera_number IS NULL
                AND capture_group_quality_review_id IS NULL
                AND group_quality_revision IS NULL
                AND confirmed_view_set IS NULL
                AND group_input_version IS NULL)
            OR
            (capture_group_id IS NOT NULL
                AND clip_asset_id IS NULL
                AND capture_group_quality_review_id IS NOT NULL
                AND group_quality_revision IS NOT NULL
                AND confirmed_view_set IS NOT NULL
                AND group_input_version IS NOT NULL)),
    ADD CONSTRAINT ck_annotation_task_primary_view
        CHECK (
            (primary_clip_asset_id IS NULL AND primary_camera_number IS NULL)
            OR (primary_clip_asset_id IS NOT NULL AND primary_camera_number BETWEEN 1 AND 64)),
    ADD CONSTRAINT ck_annotation_task_group_quality_revision
        CHECK (group_quality_revision IS NULL OR group_quality_revision > 0),
    ADD CONSTRAINT ck_annotation_task_group_view_set
        CHECK (confirmed_view_set IS NULL OR jsonb_typeof(confirmed_view_set) = 'object'),
    ADD CONSTRAINT ck_annotation_task_group_input_version
        CHECK (group_input_version IS NULL OR group_input_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$');

ALTER TABLE annotation_revision
    ADD COLUMN capture_group_id UUID,
    ADD COLUMN primary_clip_asset_id UUID,
    ADD COLUMN primary_camera_number INTEGER,
    ADD COLUMN confirmed_view_set JSONB,
    ADD COLUMN input_version VARCHAR(128);

ALTER TABLE annotation_revision
    DROP CONSTRAINT fk_annotation_revision_task_clip;

ALTER TABLE annotation_revision
    ADD CONSTRAINT fk_annotation_revision_capture_group
        FOREIGN KEY (capture_group_id) REFERENCES capture_session (capture_session_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_annotation_revision_primary_clip_asset
        FOREIGN KEY (primary_clip_asset_id) REFERENCES clip_asset (clip_asset_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_annotation_revision_owner
        CHECK (
            (capture_group_id IS NULL
                AND primary_clip_asset_id IS NULL
                AND primary_camera_number IS NULL
                AND confirmed_view_set IS NULL
                AND input_version IS NULL)
            OR
            (capture_group_id IS NOT NULL
                AND primary_clip_asset_id IS NOT NULL
                AND primary_camera_number BETWEEN 1 AND 64
                AND confirmed_view_set IS NOT NULL
                AND input_version IS NOT NULL)),
    ADD CONSTRAINT ck_annotation_revision_view_set
        CHECK (confirmed_view_set IS NULL OR jsonb_typeof(confirmed_view_set) = 'object'),
    ADD CONSTRAINT ck_annotation_revision_input_version
        CHECK (input_version IS NULL OR input_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$');

CREATE OR REPLACE FUNCTION validate_annotation_revision_owner()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    task_capture_group_id UUID;
    task_clip_asset_id UUID;
BEGIN
    SELECT capture_group_id, clip_asset_id
      INTO task_capture_group_id, task_clip_asset_id
      FROM annotation_task
     WHERE project_id = NEW.project_id
       AND annotation_task_id = NEW.annotation_task_id;

    IF task_capture_group_id IS NULL
       AND (task_clip_asset_id IS DISTINCT FROM NEW.clip_asset_id
            OR NEW.capture_group_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Legacy annotation revision must retain its task clip asset.'
            USING ERRCODE = '23514';
    END IF;

    IF task_capture_group_id IS NOT NULL
       AND (NEW.capture_group_id IS DISTINCT FROM task_capture_group_id
            OR NEW.primary_clip_asset_id IS DISTINCT FROM NEW.clip_asset_id) THEN
        RAISE EXCEPTION 'Capture-group annotation revision must retain its group and primary view.'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_annotation_revision_owner
    BEFORE INSERT ON annotation_revision
    FOR EACH ROW
    EXECUTE FUNCTION validate_annotation_revision_owner();
