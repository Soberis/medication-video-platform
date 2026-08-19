-- Physical reduction of legacy surfaces that no longer participate in the
-- application or P0 training-data path. This migration is intentionally
-- fail-closed: historical rows must be reviewed before the objects disappear.
DO $$
DECLARE
    legacy_row_count BIGINT;
BEGIN
    SELECT count(*) INTO legacy_row_count FROM asset_folder;
    IF legacy_row_count > 0 THEN
        RAISE EXCEPTION 'V65 blocked: asset_folder contains % historical rows', legacy_row_count;
    END IF;

    SELECT count(*) INTO legacy_row_count FROM capture_event_log;
    IF legacy_row_count > 0 THEN
        RAISE EXCEPTION 'V65 blocked: capture_event_log contains % historical rows', legacy_row_count;
    END IF;

    SELECT count(*) INTO legacy_row_count FROM ai_evaluation;
    IF legacy_row_count > 0 THEN
        RAISE EXCEPTION 'V65 blocked: ai_evaluation contains % historical rows', legacy_row_count;
    END IF;

    SELECT count(*) INTO legacy_row_count FROM ai_evaluation_metric;
    IF legacy_row_count > 0 THEN
        RAISE EXCEPTION 'V65 blocked: ai_evaluation_metric contains % historical rows', legacy_row_count;
    END IF;
END;
$$;

ALTER TABLE upload_batch
    DROP CONSTRAINT fk_upload_batch_asset_folder_project,
    DROP CONSTRAINT ck_upload_batch_source_folder,
    DROP COLUMN asset_folder_id;

ALTER TABLE clip_asset
    DROP CONSTRAINT fk_clip_asset_project_folder,
    DROP COLUMN folder_id;

DROP TABLE asset_folder;

DROP TABLE capture_event_log;

DROP TABLE ai_evaluation_metric;

DROP TABLE ai_evaluation;
