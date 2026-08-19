-- Retire the superseded DatasetVersion/Admission/Build/Freeze/Export flow.
-- The current P0 export reads the training-data.v2 views directly.
-- Historical rows and legacy queue messages must be reviewed before removal.
DO $$
DECLARE
    legacy_table TEXT;
    legacy_row_count BIGINT;
BEGIN
    FOREACH legacy_table IN ARRAY ARRAY[
        'asset_classification_command',
        'dataset_version',
        'dataset_member_session',
        'dataset_member_stream',
        'dataset_split_plan',
        'dataset_split_assignment',
        'dataset_build_specification',
        'dataset_build_snapshot',
        'dataset_version_validation',
        'approval_request',
        'approval_decision',
        'dataset_export',
        'dataset_export_attempt',
        'export_artifact',
        'dataset_export_manual_replay'
    ] LOOP
        EXECUTE format('SELECT count(*) FROM %I', legacy_table) INTO legacy_row_count;
        IF legacy_row_count > 0 THEN
            RAISE EXCEPTION 'V66 blocked: % contains % historical rows', legacy_table, legacy_row_count;
        END IF;
    END LOOP;

    IF EXISTS (
        SELECT 1
        FROM inbox_message
        WHERE consumer_name = 'dataset-export-result-v1'
           OR message_type = 'DatasetExportResultV1'
    ) THEN
        RAISE EXCEPTION 'V66 blocked: inbox_message contains legacy dataset-export messages';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM outbox_event
        WHERE schema_version = 'dataset-export-job.v1'
           OR aggregate_type = 'DatasetExport'
           OR event_type LIKE 'DatasetExport%'
           OR routing_key LIKE 'dataset.export.%'
    ) THEN
        RAISE EXCEPTION 'V66 blocked: outbox_event contains legacy dataset-export messages';
    END IF;
END;
$$;

-- Remove the two legacy references held by dataset_version before dropping the
-- mutually-referencing snapshot and approval tables.
ALTER TABLE dataset_version
    DROP CONSTRAINT fk_dataset_version_build_snapshot,
    DROP CONSTRAINT fk_dataset_version_freeze_request,
    DROP CONSTRAINT fk_dataset_version_freeze_decision;

ALTER TABLE inbox_message
    DROP CONSTRAINT ck_inbox_message_contract,
    ADD CONSTRAINT ck_inbox_message_contract CHECK (
        (consumer_name = 'media-probe-result-v1'
            AND message_type = 'MediaProbeResultV1')
        OR (consumer_name = 'capture-sync-result-v1'
            AND message_type = 'CaptureSyncResultV1')
    );

ALTER TABLE outbox_event
    DROP CONSTRAINT ck_outbox_capability_redacted,
    ADD CONSTRAINT ck_outbox_capability_redacted CHECK (
        published_at IS NULL
        OR schema_version <> 'media-probe-job.v1'
        OR NOT jsonb_exists(payload_json, 'lease_token')
    );

DROP TABLE asset_classification_command;

DROP TABLE dataset_export_manual_replay;

DROP TABLE export_artifact;

DROP TABLE dataset_export_attempt;

DROP TABLE dataset_export;

DROP TABLE approval_decision;

DROP TABLE approval_request;

DROP TABLE dataset_split_assignment;

DROP TABLE dataset_member_stream;

DROP TABLE dataset_version_validation;

DROP TABLE dataset_build_snapshot;

DROP TABLE dataset_build_specification;

DROP TABLE dataset_split_plan;

DROP TABLE dataset_member_session;

DROP TABLE dataset_version;
