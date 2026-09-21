CREATE TABLE annotation_task_view (
    annotation_task_view_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    annotation_task_id UUID NOT NULL,
    camera_number INTEGER NOT NULL,
    camera_stream_id UUID,
    clip_asset_id UUID,
    camera_stream_version BIGINT NOT NULL,
    clip_asset_version BIGINT NOT NULL,
    source_asset_sha256 VARCHAR(64),
    media_status VARCHAR(32) NOT NULL,
    view_state VARCHAR(16) NOT NULL,
    draft_document JSONB NOT NULL,
    exclusion_reason VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_annotation_task_view_camera UNIQUE (project_id, annotation_task_id, camera_number),
    CONSTRAINT fk_annotation_task_view_task FOREIGN KEY (project_id, annotation_task_id)
        REFERENCES annotation_task (project_id, annotation_task_id) ON DELETE RESTRICT,
    CONSTRAINT fk_annotation_task_view_stream FOREIGN KEY (camera_stream_id)
        REFERENCES camera_stream (camera_stream_id) ON DELETE RESTRICT,
    CONSTRAINT fk_annotation_task_view_clip FOREIGN KEY (clip_asset_id)
        REFERENCES clip_asset (clip_asset_id) ON DELETE RESTRICT,
    CONSTRAINT ck_annotation_task_view_camera CHECK (camera_number BETWEEN 1 AND 64),
    CONSTRAINT ck_annotation_task_view_versions CHECK (camera_stream_version >= 0 AND clip_asset_version >= 0),
    CONSTRAINT ck_annotation_task_view_sha CHECK (source_asset_sha256 IS NULL OR source_asset_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_annotation_task_view_state CHECK (view_state IN ('PENDING', 'DRAFT', 'SUBMITTED', 'EXCLUDED')),
    CONSTRAINT ck_annotation_task_view_document CHECK (
        jsonb_typeof(draft_document) = 'object'
        AND jsonb_typeof(draft_document->'segments') = 'array'
        AND jsonb_typeof(draft_document->'note') = 'string'),
    CONSTRAINT ck_annotation_task_view_exclusion CHECK (
        (view_state = 'EXCLUDED' AND exclusion_reason IS NOT NULL AND length(btrim(exclusion_reason)) > 0)
        OR (view_state <> 'EXCLUDED' AND exclusion_reason IS NULL))
);

CREATE INDEX ix_annotation_task_view_task
    ON annotation_task_view (project_id, annotation_task_id, camera_number);

INSERT INTO annotation_task_view (
    annotation_task_view_id, project_id, annotation_task_id, camera_number,
    camera_stream_id, clip_asset_id, camera_stream_version, clip_asset_version,
    source_asset_sha256, media_status, view_state, draft_document,
    created_at, updated_at, version
)
SELECT gen_random_uuid(), task.project_id, task.annotation_task_id,
       (slot.value->>'camera_number')::INTEGER,
       NULLIF(slot.value->>'camera_stream_id', '')::UUID,
       NULLIF(slot.value->>'clip_asset_id', '')::UUID,
       COALESCE((slot.value->>'stream_version')::BIGINT, 0),
       COALESCE((slot.value->>'clip_asset_version')::BIGINT, 0),
       asset.sha256,
       COALESCE(slot.value->>'media_status', 'UNKNOWN'),
       'PENDING', jsonb_build_object('segments', '[]'::JSONB, 'note', ''),
       task.created_at, task.updated_at, 0
  FROM annotation_task task
 CROSS JOIN LATERAL jsonb_array_elements(
       CASE WHEN jsonb_typeof(task.confirmed_view_set->'views') = 'array'
            THEN task.confirmed_view_set->'views' ELSE '[]'::JSONB END
 ) AS slot(value)
  LEFT JOIN clip_asset asset
    ON asset.clip_asset_id = NULLIF(slot.value->>'clip_asset_id', '')::UUID
 WHERE task.capture_group_id IS NOT NULL
   AND jsonb_typeof(slot.value) = 'object'
   AND slot.value ? 'camera_number';

CREATE TABLE annotation_task_view_history (
    annotation_task_view_history_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    annotation_task_id UUID NOT NULL,
    camera_number INTEGER NOT NULL,
    camera_stream_id UUID,
    clip_asset_id UUID,
    camera_stream_version BIGINT NOT NULL,
    clip_asset_version BIGINT NOT NULL,
    source_asset_sha256 VARCHAR(64),
    action VARCHAR(24) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    view_version BIGINT NOT NULL,
    view_snapshot JSONB NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_annotation_task_view_history_view FOREIGN KEY (project_id, annotation_task_id, camera_number)
        REFERENCES annotation_task_view (project_id, annotation_task_id, camera_number) ON DELETE RESTRICT,
    CONSTRAINT uk_annotation_task_view_history_idempotency
        UNIQUE (project_id, annotation_task_id, camera_number, actor_id, idempotency_key),
    CONSTRAINT ck_annotation_task_view_history_camera CHECK (camera_number BETWEEN 1 AND 64),
    CONSTRAINT ck_annotation_task_view_history_action
        CHECK (action IN ('DRAFT_SAVED', 'SUBMITTED', 'EXCLUDED', 'RESTORED', 'INPUT_CHANGED')),
    CONSTRAINT ck_annotation_task_view_history_actor
        CHECK (actor_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_annotation_task_view_history_idempotency
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_annotation_task_view_history_snapshot
        CHECK (jsonb_typeof(view_snapshot) = 'object')
);

CREATE INDEX ix_annotation_task_view_history_view
    ON annotation_task_view_history (project_id, annotation_task_id, camera_number, occurred_at DESC);

CREATE OR REPLACE FUNCTION reject_annotation_task_view_history_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    RAISE EXCEPTION 'Annotation task view history is append-only.' USING ERRCODE = '55000';
END;
$function$;

CREATE TRIGGER trg_annotation_task_view_history_append_only
    BEFORE UPDATE OR DELETE ON annotation_task_view_history
    FOR EACH ROW EXECUTE FUNCTION reject_annotation_task_view_history_mutation();

ALTER TABLE annotation_revision
    ALTER COLUMN clip_asset_id DROP NOT NULL,
    ALTER COLUMN primary_clip_asset_id DROP NOT NULL,
    ALTER COLUMN primary_camera_number DROP NOT NULL;

ALTER TABLE annotation_revision DROP CONSTRAINT ck_annotation_revision_schema;
ALTER TABLE annotation_revision
    ADD CONSTRAINT ck_annotation_revision_schema CHECK (
        annotation_schema_version IN ('annotation-document.v1', 'annotation-document.v2'));

ALTER TABLE annotation_revision DROP CONSTRAINT ck_annotation_revision_owner;
ALTER TABLE annotation_revision
    ADD CONSTRAINT ck_annotation_revision_owner CHECK (
        capture_group_id IS NOT NULL
        AND confirmed_view_set IS NOT NULL
        AND input_version IS NOT NULL
        AND ((annotation_schema_version = 'annotation-document.v1'
                AND clip_asset_id IS NOT NULL
                AND primary_clip_asset_id IS NOT NULL
                AND primary_camera_number BETWEEN 1 AND 64
                AND clip_asset_id = primary_clip_asset_id)
            OR (annotation_schema_version = 'annotation-document.v2'
                AND clip_asset_id IS NULL
                AND primary_clip_asset_id IS NULL
                AND primary_camera_number IS NULL))
    );

CREATE OR REPLACE FUNCTION validate_annotation_revision_owner()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    task_capture_group_id UUID;
    task_primary_clip_asset_id UUID;
BEGIN
    SELECT capture_group_id, primary_clip_asset_id
      INTO task_capture_group_id, task_primary_clip_asset_id
      FROM annotation_task
     WHERE project_id = NEW.project_id
       AND annotation_task_id = NEW.annotation_task_id;

    IF task_capture_group_id IS NULL THEN
        RAISE EXCEPTION 'Annotation revision task must belong to a CaptureGroup.' USING ERRCODE = '23514';
    ELSIF NEW.capture_group_id IS DISTINCT FROM task_capture_group_id THEN
        RAISE EXCEPTION 'Capture-group annotation revision must retain its group.' USING ERRCODE = '23514';
    ELSIF NEW.annotation_schema_version = 'annotation-document.v1'
        AND (task_primary_clip_asset_id IS NULL
            OR NEW.primary_clip_asset_id IS DISTINCT FROM task_primary_clip_asset_id
            OR NEW.clip_asset_id IS DISTINCT FROM NEW.primary_clip_asset_id) THEN
        RAISE EXCEPTION 'Legacy group revision must retain its primary view.' USING ERRCODE = '23514';
    ELSIF NEW.annotation_schema_version = 'annotation-document.v2'
        AND (NEW.clip_asset_id IS NOT NULL OR NEW.primary_clip_asset_id IS NOT NULL OR NEW.primary_camera_number IS NOT NULL) THEN
        RAISE EXCEPTION 'Multicamera group revision cannot declare a primary view.' USING ERRCODE = '23514';
    ELSIF NEW.annotation_schema_version NOT IN ('annotation-document.v1', 'annotation-document.v2') THEN
        RAISE EXCEPTION 'Unsupported annotation document schema version.' USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$function$;

ALTER TABLE annotation_review ADD COLUMN reviewed_camera_numbers JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE annotation_review ADD COLUMN return_camera_numbers JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE annotation_review DROP CONSTRAINT ck_annotation_review_group_context;
ALTER TABLE annotation_review
    ADD CONSTRAINT ck_annotation_review_group_context CHECK (
        capture_group_id IS NOT NULL
        AND annotation_revision_number > 0
        AND ((primary_clip_asset_id IS NULL AND primary_camera_number IS NULL)
            OR (primary_clip_asset_id IS NOT NULL AND primary_camera_number BETWEEN 1 AND 64))
        AND jsonb_typeof(confirmed_view_set) = 'object'
        AND input_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        AND jsonb_typeof(qualified_views) = 'array'
        AND jsonb_typeof(rejected_missing_views) = 'array'
        AND jsonb_typeof(candidate_evidence) = 'array'
        AND jsonb_typeof(reviewed_camera_numbers) = 'array'
        AND jsonb_typeof(return_camera_numbers) = 'array');
