-- Retire the empty legacy single-video persistence left after the V1.27 runtime
-- cutover. Historical rows must be reviewed before any object is removed.
-- This migration deliberately fails closed and never cascades through unknown
-- database dependencies.
DO $$
DECLARE
    legacy_row_count BIGINT;
BEGIN
    SELECT count(*) INTO legacy_row_count FROM cleaning_run;
    IF legacy_row_count > 0 THEN
        RAISE EXCEPTION 'V72 blocked: cleaning_run contains % historical rows', legacy_row_count;
    END IF;

    SELECT count(*) INTO legacy_row_count FROM cleaning_result;
    IF legacy_row_count > 0 THEN
        RAISE EXCEPTION 'V72 blocked: cleaning_result contains % historical rows', legacy_row_count;
    END IF;

    SELECT count(*) INTO legacy_row_count
    FROM annotation_task
    WHERE capture_group_id IS NULL
       OR clip_asset_id IS NOT NULL;
    IF legacy_row_count > 0 THEN
        RAISE EXCEPTION 'V72 blocked: annotation_task contains % legacy single-video rows', legacy_row_count;
    END IF;
END;
$$;

-- Drop the dependent view first. No CASCADE is intentional: an unrecognised
-- database dependency must stop the migration instead of being removed.
DROP VIEW training_data_candidate_observations;

DROP VIEW training_data_eligibility;

DROP TABLE cleaning_result;

DROP TABLE cleaning_run;

ALTER TABLE annotation_task
    DROP CONSTRAINT uk_annotation_task_project_task_clip,
    DROP CONSTRAINT uk_annotation_task_clip_asset,
    DROP CONSTRAINT fk_annotation_task_clip_asset,
    DROP CONSTRAINT ck_annotation_task_owner,
    DROP COLUMN clip_asset_id;

ALTER TABLE annotation_task
    ADD CONSTRAINT ck_annotation_task_owner
        CHECK (
            capture_group_id IS NOT NULL
            AND capture_group_quality_review_id IS NOT NULL
            AND group_quality_revision IS NOT NULL
            AND confirmed_view_set IS NOT NULL
            AND group_input_version IS NOT NULL);

ALTER TABLE annotation_revision
    DROP CONSTRAINT ck_annotation_revision_owner;

ALTER TABLE annotation_revision
    ADD CONSTRAINT ck_annotation_revision_owner
        CHECK (
            capture_group_id IS NOT NULL
            AND primary_clip_asset_id IS NOT NULL
            AND primary_camera_number BETWEEN 1 AND 64
            AND confirmed_view_set IS NOT NULL
            AND input_version IS NOT NULL);

ALTER TABLE annotation_review
    DROP CONSTRAINT ck_annotation_review_group_context;

ALTER TABLE annotation_review
    ADD CONSTRAINT ck_annotation_review_group_context
        CHECK (
            capture_group_id IS NOT NULL
            AND annotation_revision_number > 0
            AND primary_clip_asset_id IS NOT NULL
            AND primary_camera_number BETWEEN 1 AND 64
            AND jsonb_typeof(confirmed_view_set) = 'object'
            AND input_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            AND jsonb_typeof(qualified_views) = 'array'
            AND jsonb_typeof(rejected_missing_views) = 'array'
            AND jsonb_typeof(candidate_evidence) = 'array');

CREATE OR REPLACE FUNCTION validate_annotation_revision_owner()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    task_capture_group_id UUID;
BEGIN
    SELECT capture_group_id
      INTO task_capture_group_id
      FROM annotation_task
     WHERE project_id = NEW.project_id
       AND annotation_task_id = NEW.annotation_task_id;

    IF task_capture_group_id IS NULL
       OR NEW.capture_group_id IS DISTINCT FROM task_capture_group_id
       OR NEW.primary_clip_asset_id IS DISTINCT FROM NEW.clip_asset_id THEN
        RAISE EXCEPTION 'Capture-group annotation revision must retain its group and primary view.'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;
