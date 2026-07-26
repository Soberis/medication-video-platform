ALTER TABLE annotation_task
    DROP CONSTRAINT ck_annotation_task_status;

ALTER TABLE annotation_task
    ADD CONSTRAINT ck_annotation_task_status
        CHECK (task_status IN ('ASSIGNED', 'SUBMITTED', 'COMPLETED'));

ALTER TABLE annotation_review
    ADD CONSTRAINT uk_annotation_review_project_review_revision_decision
        UNIQUE (
            project_id,
            annotation_review_id,
            annotation_revision_id,
            decision);

ALTER TABLE annotation_revision
    ADD CONSTRAINT uk_annotation_revision_frozen_evidence
        UNIQUE (
            project_id,
            annotation_revision_id,
            clip_asset_id,
            document_sha256,
            label_schema_id,
            label_schema_digest);

ALTER TABLE clip_asset
    ADD CONSTRAINT uk_clip_asset_frozen_evidence
        UNIQUE (project_id, clip_asset_id, sha256);

ALTER TABLE media_report
    ADD CONSTRAINT uk_media_report_cleaning_evidence
        UNIQUE (
            project_id,
            media_report_id,
            clip_asset_id,
            input_digest,
            recipe_digest,
            duration_microseconds);

CREATE TABLE cleaning_rule_set (
    cleaning_rule_set_id UUID PRIMARY KEY,
    rule_set_key VARCHAR(64) NOT NULL,
    rule_set_version VARCHAR(64) NOT NULL,
    rule_set_digest VARCHAR(64) NOT NULL,
    cleaning_stage VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_cleaning_rule_set_key_version
        UNIQUE (rule_set_key, rule_set_version),
    CONSTRAINT uk_cleaning_rule_set_identity
        UNIQUE (cleaning_rule_set_id, rule_set_digest),
    CONSTRAINT ck_cleaning_rule_set_key
        CHECK (rule_set_key ~ '^[a-z][a-z0-9._-]{0,63}$'),
    CONSTRAINT ck_cleaning_rule_set_version
        CHECK (rule_set_version ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT ck_cleaning_rule_set_digest
        CHECK (rule_set_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_cleaning_rule_set_stage
        CHECK (cleaning_stage = 'POST_ANNOTATION'),
    CONSTRAINT ck_cleaning_rule_set_status
        CHECK (status IN ('ACTIVE', 'RETIRED'))
);

CREATE UNIQUE INDEX uk_cleaning_rule_set_active_stage
    ON cleaning_rule_set (cleaning_stage)
    WHERE status = 'ACTIVE';

INSERT INTO cleaning_rule_set (
    cleaning_rule_set_id,
    rule_set_key,
    rule_set_version,
    rule_set_digest,
    cleaning_stage,
    status,
    created_at)
VALUES
    (
        '30000000-0000-0000-0000-000000000000',
        'legacy-approved-revision',
        'legacy-approved-revision.v1',
        '31cd00e0fdeb91e423afda99e7b8578b86e600e74e53ee824c4146825bae79a4',
        'POST_ANNOTATION',
        'RETIRED',
        TIMESTAMP WITH TIME ZONE '2026-07-26 00:00:00+00'
    ),
    (
        '30000000-0000-0000-0000-000000000001',
        'p0-post-annotation',
        'p0-post-annotation.v1',
        '7c1aa3606467412b84964a3455af8695bd73c85cd9b5d08ce079061ed840fa0a',
        'POST_ANNOTATION',
        'ACTIVE',
        TIMESTAMP WITH TIME ZONE '2026-07-26 00:00:00+00'
    );

CREATE TABLE cleaning_run (
    cleaning_run_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    clip_asset_id UUID NOT NULL,
    annotation_revision_id UUID NOT NULL,
    annotation_review_id UUID NOT NULL,
    review_decision VARCHAR(32) NOT NULL,
    cleaning_rule_set_id UUID NOT NULL,
    rule_set_digest VARCHAR(64) NOT NULL,
    input_fingerprint VARCHAR(64),
    asset_sha256 VARCHAR(64) NOT NULL,
    document_sha256 VARCHAR(64) NOT NULL,
    label_schema_id UUID NOT NULL,
    label_schema_digest VARCHAR(64) NOT NULL,
    quality_review_version BIGINT NOT NULL,
    media_report_id UUID,
    media_input_digest VARCHAR(64),
    media_recipe_digest VARCHAR(64),
    verified_duration_microseconds BIGINT,
    trigger_type VARCHAR(32) NOT NULL,
    run_status VARCHAR(32) NOT NULL,
    triggered_by VARCHAR(128) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_cleaning_run_revision_rule_input
        UNIQUE (
            annotation_revision_id,
            cleaning_rule_set_id,
            input_fingerprint),
    CONSTRAINT uk_cleaning_run_project_run_revision
        UNIQUE (project_id, cleaning_run_id, annotation_revision_id),
    CONSTRAINT fk_cleaning_run_clip_asset
        FOREIGN KEY (project_id, clip_asset_id, asset_sha256)
        REFERENCES clip_asset (
            project_id, clip_asset_id, sha256) ON DELETE RESTRICT,
    CONSTRAINT fk_cleaning_run_annotation_revision
        FOREIGN KEY (
            project_id,
            annotation_revision_id,
            clip_asset_id,
            document_sha256,
            label_schema_id,
            label_schema_digest)
        REFERENCES annotation_revision (
            project_id,
            annotation_revision_id,
            clip_asset_id,
            document_sha256,
            label_schema_id,
            label_schema_digest) ON DELETE RESTRICT,
    CONSTRAINT fk_cleaning_run_annotation_review
        FOREIGN KEY (
            project_id,
            annotation_review_id,
            annotation_revision_id,
            review_decision)
        REFERENCES annotation_review (
            project_id,
            annotation_review_id,
            annotation_revision_id,
            decision) ON DELETE RESTRICT,
    CONSTRAINT fk_cleaning_run_rule_set
        FOREIGN KEY (cleaning_rule_set_id, rule_set_digest)
        REFERENCES cleaning_rule_set (
            cleaning_rule_set_id, rule_set_digest) ON DELETE RESTRICT,
    CONSTRAINT fk_cleaning_run_label_schema
        FOREIGN KEY (label_schema_id, label_schema_digest)
        REFERENCES label_schema (label_schema_id, schema_digest) ON DELETE RESTRICT,
    CONSTRAINT fk_cleaning_run_media_report
        FOREIGN KEY (
            project_id,
            media_report_id,
            clip_asset_id,
            media_input_digest,
            media_recipe_digest,
            verified_duration_microseconds)
        REFERENCES media_report (
            project_id,
            media_report_id,
            clip_asset_id,
            input_digest,
            recipe_digest,
            duration_microseconds)
        ON DELETE RESTRICT,
    CONSTRAINT ck_cleaning_run_asset_digest
        CHECK (asset_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_cleaning_run_document_digest
        CHECK (document_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_cleaning_run_schema_digest
        CHECK (label_schema_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_cleaning_run_rule_digest
        CHECK (rule_set_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_cleaning_run_input_fingerprint
        CHECK (
            (trigger_type = 'LEGACY_MIGRATION' AND input_fingerprint IS NULL)
            OR (
                trigger_type = 'ANNOTATION_APPROVAL'
                AND input_fingerprint ~ '^[a-f0-9]{64}$')),
    CONSTRAINT ck_cleaning_run_quality_version
        CHECK (quality_review_version >= 0),
    CONSTRAINT ck_cleaning_run_review_decision
        CHECK (review_decision = 'APPROVED'),
    CONSTRAINT ck_cleaning_run_duration
        CHECK (
            verified_duration_microseconds IS NULL
            OR verified_duration_microseconds >= 0),
    CONSTRAINT ck_cleaning_run_media_input_digest
        CHECK (
            media_input_digest IS NULL
            OR media_input_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_cleaning_run_media_recipe_digest
        CHECK (
            media_recipe_digest IS NULL
            OR media_recipe_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_cleaning_run_media_fact_complete
        CHECK (
            (
                media_report_id IS NULL
                AND media_input_digest IS NULL
                AND media_recipe_digest IS NULL
                AND verified_duration_microseconds IS NULL)
            OR (
                media_report_id IS NOT NULL
                AND media_input_digest IS NOT NULL
                AND media_recipe_digest IS NOT NULL
                AND verified_duration_microseconds IS NOT NULL)),
    CONSTRAINT ck_cleaning_run_trigger
        CHECK (trigger_type IN ('ANNOTATION_APPROVAL', 'LEGACY_MIGRATION')),
    CONSTRAINT ck_cleaning_run_status
        CHECK (run_status = 'SUCCEEDED'),
    CONSTRAINT ck_cleaning_run_actor
        CHECK (triggered_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_cleaning_run_time
        CHECK (completed_at >= started_at)
);

CREATE INDEX ix_cleaning_run_project_completed
    ON cleaning_run (project_id, completed_at DESC, cleaning_run_id DESC);

CREATE TABLE cleaning_result (
    cleaning_result_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    cleaning_run_id UUID NOT NULL,
    annotation_revision_id UUID NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    reason_codes JSONB NOT NULL,
    executed_checks JSONB NOT NULL,
    evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_cleaning_result_run UNIQUE (cleaning_run_id),
    CONSTRAINT fk_cleaning_result_run
        FOREIGN KEY (project_id, cleaning_run_id, annotation_revision_id)
        REFERENCES cleaning_run (
            project_id, cleaning_run_id, annotation_revision_id) ON DELETE RESTRICT,
    CONSTRAINT ck_cleaning_result_outcome
        CHECK (outcome IN ('PASS', 'WARNING', 'REJECT')),
    CONSTRAINT ck_cleaning_result_reasons
        CHECK (jsonb_typeof(reason_codes) = 'array'),
    CONSTRAINT ck_cleaning_result_checks
        CHECK (
            jsonb_typeof(executed_checks) = 'array'
            AND jsonb_array_length(executed_checks) > 0)
);

CREATE INDEX ix_cleaning_result_project_outcome
    ON cleaning_result (project_id, outcome, evaluated_at DESC);

CREATE INDEX ix_cleaning_result_revision_latest
    ON cleaning_result (
        annotation_revision_id,
        evaluated_at DESC,
        cleaning_result_id DESC);

INSERT INTO cleaning_run (
    cleaning_run_id,
    project_id,
    clip_asset_id,
    annotation_revision_id,
    annotation_review_id,
    review_decision,
    cleaning_rule_set_id,
    rule_set_digest,
    input_fingerprint,
    asset_sha256,
    document_sha256,
    label_schema_id,
    label_schema_digest,
    quality_review_version,
    media_report_id,
    media_input_digest,
    media_recipe_digest,
    verified_duration_microseconds,
    trigger_type,
    run_status,
    triggered_by,
    started_at,
    completed_at)
SELECT
    revision.annotation_revision_id,
    revision.project_id,
    revision.clip_asset_id,
    revision.annotation_revision_id,
    review.annotation_review_id,
    review.decision,
    '30000000-0000-0000-0000-000000000000',
    '31cd00e0fdeb91e423afda99e7b8578b86e600e74e53ee824c4146825bae79a4',
    NULL,
    asset.sha256,
    revision.document_sha256,
    revision.label_schema_id,
    revision.label_schema_digest,
    COALESCE(quality.version, 0),
    media.media_report_id,
    media.input_digest,
    media.recipe_digest,
    media.duration_microseconds,
    'LEGACY_MIGRATION',
    'SUCCEEDED',
    'flyway-v34',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM annotation_review review
JOIN annotation_revision revision
  ON revision.project_id = review.project_id
 AND revision.annotation_revision_id = review.annotation_revision_id
JOIN clip_asset asset
  ON asset.project_id = revision.project_id
 AND asset.clip_asset_id = revision.clip_asset_id
LEFT JOIN quality_review quality
  ON quality.project_id = revision.project_id
 AND quality.clip_asset_id = revision.clip_asset_id
LEFT JOIN LATERAL (
    SELECT
        report.media_report_id,
        report.input_digest,
        report.recipe_digest,
        report.duration_microseconds
    FROM media_report report
    WHERE report.project_id = revision.project_id
      AND report.clip_asset_id = revision.clip_asset_id
    ORDER BY report.created_at DESC, report.media_report_id DESC
    LIMIT 1
) media ON true
WHERE review.decision = 'APPROVED';

INSERT INTO cleaning_result (
    cleaning_result_id,
    project_id,
    cleaning_run_id,
    annotation_revision_id,
    outcome,
    reason_codes,
    executed_checks,
    evaluated_at)
SELECT
    review.annotation_review_id,
    revision.project_id,
    revision.annotation_revision_id,
    revision.annotation_revision_id,
    'WARNING',
    '["LEGACY_APPROVED_REVISION_REQUIRES_REVALIDATION"]'::jsonb,
    '["APPROVED_REVIEW_BOUND","ASSET_INPUT_DIGEST_BOUND","LABEL_SCHEMA_BOUND"]'::jsonb,
    CURRENT_TIMESTAMP
FROM annotation_review review
JOIN annotation_revision revision
  ON revision.project_id = review.project_id
 AND revision.annotation_revision_id = review.annotation_revision_id
WHERE review.decision = 'APPROVED';

UPDATE annotation_task task
SET task_status = 'COMPLETED',
    updated_at = GREATEST(task.updated_at, CURRENT_TIMESTAMP),
    version = task.version + 1
FROM annotation_revision revision
JOIN annotation_review review
  ON review.project_id = revision.project_id
 AND review.annotation_revision_id = revision.annotation_revision_id
WHERE task.project_id = revision.project_id
  AND task.annotation_task_id = revision.annotation_task_id
  AND review.decision = 'APPROVED'
  AND task.task_status = 'SUBMITTED';

INSERT INTO audit_event (
    event_id,
    occurred_at_utc,
    actor_type,
    actor_id,
    project_id,
    action,
    object_type,
    object_id,
    object_version,
    outcome,
    reason_code,
    request_id,
    authorization_reference,
    details_json)
SELECT
    gen_random_uuid(),
    result.evaluated_at,
    'SYSTEM',
    'flyway-v34',
    result.project_id,
    'POST_ANNOTATION_CLEANING_MIGRATED',
    'CleaningResult',
    result.cleaning_result_id::text,
    rules.rule_set_version,
    'SUCCEEDED',
    'LEGACY_APPROVED_REVISION_REQUIRES_REVALIDATION',
    'v34-post-clean-' || result.cleaning_result_id::text,
    'MIGRATION',
    jsonb_build_object(
        'annotation_revision_id', result.annotation_revision_id::text,
        'cleaning_run_id', result.cleaning_run_id::text,
        'outcome', result.outcome,
        'task_completed', true)
FROM cleaning_result result
JOIN cleaning_run run
  ON run.project_id = result.project_id
 AND run.cleaning_run_id = result.cleaning_run_id
JOIN cleaning_rule_set rules
  ON rules.cleaning_rule_set_id = run.cleaning_rule_set_id
WHERE run.trigger_type = 'LEGACY_MIGRATION';
