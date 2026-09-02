-- Retire empty legacy single-video persistence after the V1.27 runtime cutover.
-- When historical rows still exist, preserve the legacy objects unchanged and
-- continue the forward migration. No historical row is deleted or rewritten.
DO $migration$
DECLARE
    cleaning_run_count BIGINT;
    cleaning_result_count BIGINT;
    legacy_task_count BIGINT;
BEGIN
    SELECT count(*) INTO cleaning_run_count FROM cleaning_run;
    SELECT count(*) INTO cleaning_result_count FROM cleaning_result;
    SELECT count(*) INTO legacy_task_count
    FROM annotation_task
    WHERE capture_group_id IS NULL
       OR clip_asset_id IS NOT NULL;

    IF cleaning_run_count > 0
       OR cleaning_result_count > 0
       OR legacy_task_count > 0 THEN
        RAISE NOTICE 'V72 preserved legacy single-video persistence: cleaning_run=%, cleaning_result=%, annotation_task=%',
            cleaning_run_count, cleaning_result_count, legacy_task_count;
        RETURN;
    END IF;

    -- No CASCADE is intentional: an unrecognised dependency must still stop
    -- the migration instead of being removed.
    EXECUTE 'DROP VIEW training_data_candidate_observations';
    EXECUTE 'DROP VIEW training_data_eligibility';
    EXECUTE 'DROP TABLE cleaning_result';
    EXECUTE 'DROP TABLE cleaning_run';

    EXECUTE $ddl$
        ALTER TABLE annotation_task
            DROP CONSTRAINT uk_annotation_task_project_task_clip,
            DROP CONSTRAINT uk_annotation_task_clip_asset,
            DROP CONSTRAINT fk_annotation_task_clip_asset,
            DROP CONSTRAINT ck_annotation_task_owner,
            DROP COLUMN clip_asset_id
    $ddl$;

    EXECUTE $ddl$
        ALTER TABLE annotation_task
            ADD CONSTRAINT ck_annotation_task_owner
                CHECK (
                    capture_group_id IS NOT NULL
                    AND capture_group_quality_review_id IS NOT NULL
                    AND group_quality_revision IS NOT NULL
                    AND confirmed_view_set IS NOT NULL
                    AND group_input_version IS NOT NULL)
    $ddl$;

    EXECUTE 'ALTER TABLE annotation_revision DROP CONSTRAINT ck_annotation_revision_owner';
    EXECUTE $ddl$
        ALTER TABLE annotation_revision
            ADD CONSTRAINT ck_annotation_revision_owner
                CHECK (
                    capture_group_id IS NOT NULL
                    AND primary_clip_asset_id IS NOT NULL
                    AND primary_camera_number BETWEEN 1 AND 64
                    AND confirmed_view_set IS NOT NULL
                    AND input_version IS NOT NULL)
    $ddl$;

    EXECUTE 'ALTER TABLE annotation_review DROP CONSTRAINT ck_annotation_review_group_context';
    EXECUTE $ddl$
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
                    AND jsonb_typeof(candidate_evidence) = 'array')
    $ddl$;

    EXECUTE $ddl$
        CREATE OR REPLACE FUNCTION validate_annotation_revision_owner()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $function$
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
        $function$
    $ddl$;
END;
$migration$;
