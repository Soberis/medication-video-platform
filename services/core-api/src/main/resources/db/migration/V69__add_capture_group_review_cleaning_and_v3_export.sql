ALTER TABLE annotation_review
    ADD COLUMN capture_group_id UUID,
    ADD COLUMN annotation_revision_number INTEGER,
    ADD COLUMN primary_clip_asset_id UUID,
    ADD COLUMN primary_camera_number INTEGER,
    ADD COLUMN confirmed_view_set JSONB,
    ADD COLUMN input_version VARCHAR(128),
    ADD COLUMN qualified_views JSONB,
    ADD COLUMN rejected_missing_views JSONB,
    ADD COLUMN candidate_evidence JSONB;

ALTER TABLE annotation_review
    ADD CONSTRAINT fk_annotation_review_capture_group
        FOREIGN KEY (capture_group_id) REFERENCES capture_session (capture_session_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_annotation_review_group_primary_clip
        FOREIGN KEY (primary_clip_asset_id) REFERENCES clip_asset (clip_asset_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_annotation_review_group_context
        CHECK (
            (capture_group_id IS NULL
                AND annotation_revision_number IS NULL
                AND primary_clip_asset_id IS NULL
                AND primary_camera_number IS NULL
                AND confirmed_view_set IS NULL
                AND input_version IS NULL
                AND qualified_views IS NULL
                AND rejected_missing_views IS NULL
                AND candidate_evidence IS NULL)
            OR
            (capture_group_id IS NOT NULL
                AND annotation_revision_number > 0
                AND primary_clip_asset_id IS NOT NULL
                AND primary_camera_number BETWEEN 1 AND 64
                AND jsonb_typeof(confirmed_view_set) = 'object'
                AND input_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
                AND jsonb_typeof(qualified_views) = 'array'
                AND jsonb_typeof(rejected_missing_views) = 'array'
                AND jsonb_typeof(candidate_evidence) = 'array'));

CREATE INDEX ix_annotation_review_capture_group
    ON annotation_review (project_id, capture_group_id, annotation_revision_number DESC);

CREATE TABLE capture_group_cleaning (
    capture_group_cleaning_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    capture_group_id UUID NOT NULL,
    annotation_revision_id UUID NOT NULL,
    annotation_review_id UUID NOT NULL,
    annotation_review_decision VARCHAR(32) NOT NULL,
    annotation_reviewed_by VARCHAR(128) NOT NULL,
    annotation_reviewed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    group_quality_review_id UUID NOT NULL,
    group_quality_result VARCHAR(32) NOT NULL,
    group_quality_revision BIGINT NOT NULL,
    primary_clip_asset_id UUID NOT NULL,
    primary_camera_number INTEGER NOT NULL,
    primary_interval JSONB NOT NULL,
    view_evidence JSONB NOT NULL,
    participating_views JSONB NOT NULL,
    excluded_views JSONB NOT NULL,
    mapping_provenance JSONB NOT NULL,
    candidate_provenance JSONB NOT NULL,
    rule_set_key VARCHAR(64) NOT NULL,
    rule_set_version VARCHAR(64) NOT NULL,
    rule_set_digest VARCHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    reason_codes JSONB NOT NULL,
    executed_checks JSONB NOT NULL,
    input_fingerprint VARCHAR(64) NOT NULL,
    evaluated_by VARCHAR(128) NOT NULL,
    authorization_basis VARCHAR(128) NOT NULL,
    evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_capture_group_cleaning_input
        UNIQUE (annotation_revision_id, input_fingerprint),
    CONSTRAINT fk_capture_group_cleaning_group
        FOREIGN KEY (capture_group_id) REFERENCES capture_session (capture_session_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_group_cleaning_revision
        FOREIGN KEY (project_id, annotation_revision_id)
        REFERENCES annotation_revision (project_id, annotation_revision_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_group_cleaning_review
        FOREIGN KEY (annotation_review_id) REFERENCES annotation_review (annotation_review_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_group_cleaning_quality
        FOREIGN KEY (group_quality_review_id)
        REFERENCES capture_group_quality_review (capture_group_quality_review_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_group_cleaning_primary_clip
        FOREIGN KEY (primary_clip_asset_id) REFERENCES clip_asset (clip_asset_id) ON DELETE RESTRICT,
    CONSTRAINT ck_capture_group_cleaning_review
        CHECK (annotation_review_decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_capture_group_cleaning_quality
        CHECK (group_quality_result IN ('QUALIFIED', 'QUALIFIED_WITH_EXCLUSIONS')),
    CONSTRAINT ck_capture_group_cleaning_quality_revision
        CHECK (group_quality_revision > 0),
    CONSTRAINT ck_capture_group_cleaning_primary_camera
        CHECK (primary_camera_number BETWEEN 1 AND 64),
    CONSTRAINT ck_capture_group_cleaning_interval
        CHECK (jsonb_typeof(primary_interval) = 'array'),
    CONSTRAINT ck_capture_group_cleaning_view_evidence
        CHECK (jsonb_typeof(view_evidence) = 'array'),
    CONSTRAINT ck_capture_group_cleaning_participating
        CHECK (jsonb_typeof(participating_views) = 'array'),
    CONSTRAINT ck_capture_group_cleaning_excluded
        CHECK (jsonb_typeof(excluded_views) = 'array'),
    CONSTRAINT ck_capture_group_cleaning_mapping
        CHECK (jsonb_typeof(mapping_provenance) = 'array'),
    CONSTRAINT ck_capture_group_cleaning_candidate
        CHECK (jsonb_typeof(candidate_provenance) = 'array'),
    CONSTRAINT ck_capture_group_cleaning_rule_set_key
        CHECK (rule_set_key ~ '^[a-z][a-z0-9._-]{0,63}$'),
    CONSTRAINT ck_capture_group_cleaning_rule_set_version
        CHECK (rule_set_version ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT ck_capture_group_cleaning_rule_set_digest
        CHECK (rule_set_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_capture_group_cleaning_outcome
        CHECK (outcome IN ('PASS', 'WARNING', 'REJECT')),
    CONSTRAINT ck_capture_group_cleaning_reasons
        CHECK (jsonb_typeof(reason_codes) = 'array'),
    CONSTRAINT ck_capture_group_cleaning_checks
        CHECK (jsonb_typeof(executed_checks) = 'array'),
    CONSTRAINT ck_capture_group_cleaning_fingerprint
        CHECK (input_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_capture_group_cleaning_actor
        CHECK (evaluated_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_capture_group_cleaning_authorization
        CHECK (length(btrim(authorization_basis)) > 0)
);

CREATE INDEX ix_capture_group_cleaning_latest
    ON capture_group_cleaning (project_id, capture_group_id, evaluated_at DESC,
                               capture_group_cleaning_id DESC);

CREATE VIEW training_data_group_eligibility_v3 AS
WITH latest_cleaning AS (
    SELECT DISTINCT ON (cleaning.project_id, cleaning.annotation_revision_id)
           cleaning.*
      FROM capture_group_cleaning cleaning
     ORDER BY cleaning.project_id,
              cleaning.annotation_revision_id,
              cleaning.evaluated_at DESC,
              cleaning.capture_group_cleaning_id DESC
), current_group_quality AS (
    SELECT DISTINCT ON (quality.project_id, quality.capture_group_id)
           quality.*
      FROM capture_group_quality_review quality
     ORDER BY quality.project_id,
              quality.capture_group_id,
              quality.revision DESC,
              quality.decided_at DESC,
              quality.capture_group_quality_review_id DESC
)
SELECT revision.project_id,
       revision.capture_group_id,
       revision.annotation_revision_id,
       revision.revision_number,
       revision.annotation_document,
       revision.primary_clip_asset_id,
       revision.primary_camera_number,
       revision.confirmed_view_set,
       revision.input_version,
       review.annotation_review_id,
       review.decision AS review_decision,
       review.reviewed_by,
       review.reviewed_at,
       review.qualified_views,
       review.rejected_missing_views,
       review.candidate_evidence,
       quality.capture_group_quality_review_id,
       quality.result AS group_quality_result,
       quality.revision AS group_quality_revision,
       quality.reviewer_actor_id AS group_quality_reviewed_by,
       quality.decided_at AS group_quality_decided_at,
       cleaning.capture_group_cleaning_id,
       cleaning.annotation_reviewed_by AS cleaning_reviewed_by,
       cleaning.annotation_reviewed_at AS cleaning_reviewed_at,
       cleaning.primary_interval,
       cleaning.view_evidence,
       cleaning.participating_views,
       cleaning.excluded_views,
       cleaning.mapping_provenance,
       cleaning.candidate_provenance,
       cleaning.rule_set_key,
       cleaning.rule_set_version,
       cleaning.rule_set_digest,
       cleaning.outcome AS cleaning_outcome,
       cleaning.reason_codes AS cleaning_reason_codes,
       cleaning.executed_checks AS cleaning_executed_checks,
       cleaning.input_fingerprint AS cleaning_input_fingerprint,
       cleaning.evaluated_by AS cleaning_evaluated_by,
       cleaning.authorization_basis AS cleaning_authorization_basis,
       cleaning.evaluated_at AS cleaning_evaluated_at
  FROM latest_cleaning cleaning
  JOIN annotation_revision revision
    ON revision.project_id = cleaning.project_id
   AND revision.annotation_revision_id = cleaning.annotation_revision_id
  JOIN annotation_task task
    ON task.project_id = revision.project_id
   AND task.annotation_task_id = revision.annotation_task_id
   AND task.capture_group_id = revision.capture_group_id
   AND task.task_status = 'COMPLETED'
  JOIN annotation_review review
    ON review.project_id = revision.project_id
   AND review.annotation_revision_id = revision.annotation_revision_id
   AND review.annotation_review_id = cleaning.annotation_review_id
   AND review.decision = 'APPROVED'
  JOIN current_group_quality quality
    ON quality.project_id = revision.project_id
   AND quality.capture_group_id = revision.capture_group_id
   AND quality.capture_group_quality_review_id = cleaning.group_quality_review_id
   AND quality.revision = cleaning.group_quality_revision
   AND quality.result IN ('QUALIFIED', 'QUALIFIED_WITH_EXCLUSIONS')
 WHERE cleaning.outcome = 'PASS';
