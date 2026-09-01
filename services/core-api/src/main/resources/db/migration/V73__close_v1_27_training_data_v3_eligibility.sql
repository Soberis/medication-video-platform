-- V1.27 training-data.v3 must prove the current CaptureGroup evidence at read time.
-- Keep an internal integrity projection so the application can distinguish an
-- ineligible group from a corrupted or ambiguous PASS row and fail closed.
DROP VIEW training_data_group_eligibility_v3;

CREATE VIEW training_data_group_eligibility_v3_integrity AS
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
), revision_facts AS (
    SELECT revision.project_id,
           revision.capture_group_id,
           revision.annotation_revision_id,
           revision.revision_number,
           revision.annotation_document,
           revision.primary_clip_asset_id AS revision_primary_clip_asset_id,
           revision.primary_camera_number AS revision_primary_camera_number,
           revision.confirmed_view_set AS revision_confirmed_view_set,
           revision.input_version AS revision_input_version,
           revision.annotation_task_id,
           revision.submitted_at,
           task.project_id AS task_project_id,
           task.capture_group_id AS task_capture_group_id,
           task.task_status,
           task.primary_clip_asset_id AS task_primary_clip_asset_id,
           task.primary_camera_number AS task_primary_camera_number,
           task.capture_group_quality_review_id AS task_quality_id,
           task.group_quality_revision AS task_quality_revision,
           task.confirmed_view_set AS task_confirmed_view_set,
           task.group_input_version AS task_input_version,
           cleaning.capture_group_cleaning_id,
           cleaning.project_id AS cleaning_project_id,
           cleaning.capture_group_id AS cleaning_capture_group_id,
           cleaning.annotation_revision_id AS cleaning_revision_id,
           cleaning.annotation_review_id AS cleaning_review_id,
           cleaning.annotation_review_decision AS cleaning_review_decision,
           cleaning.annotation_reviewed_by AS cleaning_reviewed_by,
           cleaning.annotation_reviewed_at AS cleaning_reviewed_at,
           cleaning.group_quality_review_id AS cleaning_quality_id,
           cleaning.group_quality_result AS cleaning_quality_result,
           cleaning.group_quality_revision AS cleaning_quality_revision,
           cleaning.primary_clip_asset_id AS cleaning_primary_clip_asset_id,
           cleaning.primary_camera_number AS cleaning_primary_camera_number,
           cleaning.primary_interval AS cleaning_primary_interval,
           cleaning.view_evidence AS cleaning_view_evidence,
           cleaning.participating_views AS cleaning_participating_views,
           cleaning.excluded_views AS cleaning_excluded_views,
           cleaning.mapping_provenance AS cleaning_mapping_provenance,
           cleaning.candidate_provenance AS cleaning_candidate_provenance,
           cleaning.rule_set_key AS cleaning_rule_set_key,
           cleaning.rule_set_version AS cleaning_rule_set_version,
           cleaning.rule_set_digest AS cleaning_rule_set_digest,
           cleaning.outcome AS cleaning_outcome,
           cleaning.reason_codes AS cleaning_reason_codes,
           cleaning.executed_checks AS cleaning_executed_checks,
           cleaning.input_fingerprint AS cleaning_input_fingerprint,
           cleaning.evaluated_by AS cleaning_evaluated_by,
           cleaning.authorization_basis AS cleaning_authorization_basis,
           cleaning.evaluated_at AS cleaning_evaluated_at,
           review.annotation_review_id AS review_annotation_review_id,
           review.project_id AS review_project_id,
           review.annotation_revision_id AS review_revision_id,
           review.capture_group_id AS review_capture_group_id,
           review.annotation_revision_number AS review_revision_number,
           review.decision AS review_decision,
           review.reviewed_by,
           review.reviewed_at,
           review.primary_clip_asset_id AS review_primary_clip_asset_id,
           review.primary_camera_number AS review_primary_camera_number,
           review.confirmed_view_set AS review_confirmed_view_set,
           review.input_version AS review_input_version,
           review.qualified_views AS review_qualified_views,
           review.rejected_missing_views AS review_rejected_missing_views,
           review.candidate_evidence AS review_candidate_evidence,
           quality.capture_group_quality_review_id AS quality_id,
           quality.project_id AS quality_project_id,
           quality.capture_group_id AS quality_capture_group_id,
           quality.result AS quality_result,
           quality.revision AS quality_revision,
           quality.reviewer_actor_id AS quality_reviewer_actor_id,
           quality.decided_at AS quality_decided_at,
           session.capture_session_id AS session_id,
           session.project_id AS session_project_id,
           session.capture_group_workflow AS session_workflow,
           primary_asset.project_id AS primary_asset_project_id,
           current_quality.capture_group_quality_review_id AS current_quality_id,
           current_quality.revision AS current_quality_revision,
           current_quality.result AS current_quality_result
      FROM annotation_revision revision
      JOIN annotation_task task
        ON task.annotation_task_id = revision.annotation_task_id
      LEFT JOIN latest_cleaning cleaning
        ON cleaning.project_id = revision.project_id
       AND cleaning.annotation_revision_id = revision.annotation_revision_id
      LEFT JOIN annotation_review review
        ON review.annotation_review_id = cleaning.annotation_review_id
      LEFT JOIN capture_group_quality_review quality
        ON quality.capture_group_quality_review_id = cleaning.group_quality_review_id
      LEFT JOIN capture_session session
        ON session.capture_session_id = revision.capture_group_id
      LEFT JOIN clip_asset primary_asset
        ON primary_asset.clip_asset_id = cleaning.primary_clip_asset_id
      LEFT JOIN current_group_quality current_quality
        ON current_quality.project_id = revision.project_id
       AND current_quality.capture_group_id = revision.capture_group_id
     WHERE revision.capture_group_id IS NOT NULL
), context_checked AS (
    SELECT facts.*,
           CASE
               WHEN facts.task_project_id IS NOT DISTINCT FROM facts.project_id
                AND facts.task_capture_group_id IS NOT DISTINCT FROM facts.capture_group_id
                AND facts.task_status = 'COMPLETED'
                AND facts.task_primary_clip_asset_id IS NOT DISTINCT FROM facts.revision_primary_clip_asset_id
                AND facts.task_primary_camera_number IS NOT DISTINCT FROM facts.revision_primary_camera_number
                AND facts.review_annotation_review_id IS NOT NULL
                AND facts.review_project_id IS NOT DISTINCT FROM facts.project_id
                AND facts.review_revision_id IS NOT DISTINCT FROM facts.annotation_revision_id
                AND facts.review_capture_group_id IS NOT DISTINCT FROM facts.capture_group_id
                AND facts.review_revision_number IS NOT DISTINCT FROM facts.revision_number
                AND facts.review_decision = 'APPROVED'
                AND facts.review_primary_clip_asset_id IS NOT DISTINCT FROM facts.revision_primary_clip_asset_id
                AND facts.review_primary_camera_number IS NOT DISTINCT FROM facts.revision_primary_camera_number
                AND facts.review_confirmed_view_set IS NOT DISTINCT FROM facts.revision_confirmed_view_set
                AND facts.review_input_version IS NOT DISTINCT FROM facts.revision_input_version
                AND facts.quality_id IS NOT NULL
                AND facts.quality_project_id IS NOT DISTINCT FROM facts.project_id
                AND facts.quality_capture_group_id IS NOT DISTINCT FROM facts.capture_group_id
                AND facts.quality_result IN ('QUALIFIED', 'QUALIFIED_WITH_EXCLUSIONS')
                AND facts.cleaning_project_id IS NOT DISTINCT FROM facts.project_id
                AND facts.cleaning_capture_group_id IS NOT DISTINCT FROM facts.capture_group_id
                AND facts.cleaning_revision_id IS NOT DISTINCT FROM facts.annotation_revision_id
                AND facts.cleaning_review_id IS NOT DISTINCT FROM facts.review_annotation_review_id
                AND facts.cleaning_review_decision IS NOT DISTINCT FROM facts.review_decision
                AND facts.cleaning_reviewed_by IS NOT DISTINCT FROM facts.reviewed_by
                AND facts.cleaning_reviewed_at IS NOT DISTINCT FROM facts.reviewed_at
                AND facts.cleaning_quality_id IS NOT DISTINCT FROM facts.quality_id
                AND facts.cleaning_quality_result IS NOT DISTINCT FROM facts.quality_result
                AND facts.cleaning_quality_revision IS NOT DISTINCT FROM facts.quality_revision
                AND facts.cleaning_primary_clip_asset_id IS NOT DISTINCT FROM facts.revision_primary_clip_asset_id
                AND facts.cleaning_primary_camera_number IS NOT DISTINCT FROM facts.revision_primary_camera_number
                AND facts.session_id IS NOT DISTINCT FROM facts.capture_group_id
                AND facts.session_project_id IS NOT DISTINCT FROM facts.project_id
                AND facts.session_workflow IS TRUE
                AND facts.primary_asset_project_id IS NOT DISTINCT FROM facts.project_id
               THEN TRUE
               ELSE FALSE
           END AS revision_context_consistent,
           CASE
               WHEN facts.task_project_id IS NOT DISTINCT FROM facts.project_id
                AND facts.task_capture_group_id IS NOT DISTINCT FROM facts.capture_group_id
                AND facts.task_status = 'COMPLETED'
                AND facts.task_primary_clip_asset_id IS NOT DISTINCT FROM facts.revision_primary_clip_asset_id
                AND facts.task_primary_camera_number IS NOT DISTINCT FROM facts.revision_primary_camera_number
                AND facts.task_quality_id IS NOT DISTINCT FROM facts.cleaning_quality_id
                AND facts.task_quality_revision IS NOT DISTINCT FROM facts.cleaning_quality_revision
                AND facts.task_confirmed_view_set IS NOT DISTINCT FROM facts.revision_confirmed_view_set
                AND facts.task_input_version IS NOT DISTINCT FROM facts.revision_input_version
                AND facts.review_annotation_review_id IS NOT NULL
                AND facts.review_project_id IS NOT DISTINCT FROM facts.project_id
                AND facts.review_revision_id IS NOT DISTINCT FROM facts.annotation_revision_id
                AND facts.review_capture_group_id IS NOT DISTINCT FROM facts.capture_group_id
                AND facts.review_revision_number IS NOT DISTINCT FROM facts.revision_number
                AND facts.review_decision = 'APPROVED'
                AND facts.review_primary_clip_asset_id IS NOT DISTINCT FROM facts.revision_primary_clip_asset_id
                AND facts.review_primary_camera_number IS NOT DISTINCT FROM facts.revision_primary_camera_number
                AND facts.review_confirmed_view_set IS NOT DISTINCT FROM facts.revision_confirmed_view_set
                AND facts.review_input_version IS NOT DISTINCT FROM facts.revision_input_version
                AND facts.quality_id IS NOT NULL
                AND facts.quality_project_id IS NOT DISTINCT FROM facts.project_id
                AND facts.quality_capture_group_id IS NOT DISTINCT FROM facts.capture_group_id
                AND facts.quality_result IN ('QUALIFIED', 'QUALIFIED_WITH_EXCLUSIONS')
                AND facts.current_quality_id IS NOT DISTINCT FROM facts.cleaning_quality_id
                AND facts.current_quality_revision IS NOT DISTINCT FROM facts.cleaning_quality_revision
                AND facts.current_quality_result IS NOT DISTINCT FROM facts.cleaning_quality_result
                AND facts.cleaning_project_id IS NOT DISTINCT FROM facts.project_id
                AND facts.cleaning_capture_group_id IS NOT DISTINCT FROM facts.capture_group_id
                AND facts.cleaning_revision_id IS NOT DISTINCT FROM facts.annotation_revision_id
                AND facts.cleaning_review_id IS NOT DISTINCT FROM facts.review_annotation_review_id
                AND facts.cleaning_review_decision IS NOT DISTINCT FROM facts.review_decision
                AND facts.cleaning_reviewed_by IS NOT DISTINCT FROM facts.reviewed_by
                AND facts.cleaning_reviewed_at IS NOT DISTINCT FROM facts.reviewed_at
                AND facts.cleaning_quality_id IS NOT DISTINCT FROM facts.quality_id
                AND facts.cleaning_quality_result IS NOT DISTINCT FROM facts.quality_result
                AND facts.cleaning_quality_revision IS NOT DISTINCT FROM facts.quality_revision
                AND facts.cleaning_primary_clip_asset_id IS NOT DISTINCT FROM facts.revision_primary_clip_asset_id
                AND facts.cleaning_primary_camera_number IS NOT DISTINCT FROM facts.revision_primary_camera_number
                AND facts.session_id IS NOT DISTINCT FROM facts.capture_group_id
                AND facts.session_project_id IS NOT DISTINCT FROM facts.project_id
                AND facts.session_workflow IS TRUE
                AND facts.primary_asset_project_id IS NOT DISTINCT FROM facts.project_id
               THEN TRUE
               ELSE FALSE
           END AS context_consistent
      FROM revision_facts facts
), source_checked AS (
    SELECT context.*,
           CASE
               WHEN context.cleaning_outcome IS DISTINCT FROM 'PASS' THEN FALSE
               WHEN context.cleaning_participating_views IS NULL
                 OR jsonb_typeof(context.cleaning_participating_views) <> 'array' THEN FALSE
               WHEN source_media.all_valid IS TRUE
                AND source_media.primary_present IS TRUE THEN TRUE
               ELSE FALSE
           END AS source_media_valid
      FROM context_checked context
      LEFT JOIN LATERAL (
          SELECT bool_and(
                     COALESCE(
                         frozen.camera_number BETWEEN 1 AND 64
                         AND frozen.camera_stream_id IS NOT NULL
                         AND frozen.clip_asset_id IS NOT NULL
                         AND frozen.clip_asset_sha256 ~ '^[a-f0-9]{64}$'
                         AND frozen.present IS TRUE
                         AND frozen.asset_available IS TRUE
                         AND frozen.media_status = 'INGESTED'
                         AND frozen.quality_decision = 'QUALIFIED'
                         AND frozen.stream_version IS NOT NULL
                         AND frozen.clip_asset_version IS NOT NULL
                         AND frozen.quality_review_version IS NOT NULL
                         AND EXISTS (
                             SELECT 1
                               FROM camera_stream stream
                               JOIN clip_asset asset
                                 ON asset.clip_asset_id = stream.clip_asset_id
                              WHERE stream.camera_stream_id = frozen.camera_stream_id
                                AND stream.capture_session_id = context.capture_group_id
                                AND stream.camera_index + 1 = frozen.camera_number
                                AND stream.clip_asset_id = frozen.clip_asset_id
                                AND stream.stream_status = 'INGESTED'
                                AND stream.quality_status = 'PASS'
                                AND stream.version = frozen.stream_version
                                AND asset.project_id = context.project_id
                                AND asset.asset_status = 'INGESTED'
                                AND asset.deleted_at IS NULL
                                AND asset.version = frozen.clip_asset_version
                                AND asset.sha256 = frozen.clip_asset_sha256
                         ),
                         FALSE)) AS all_valid,
                 bool_or(
                     frozen.camera_number = context.revision_primary_camera_number
                     AND frozen.clip_asset_id = context.revision_primary_clip_asset_id) AS primary_present
            FROM jsonb_to_recordset(context.cleaning_participating_views) AS frozen(
                camera_number INTEGER,
                camera_stream_id UUID,
                clip_asset_id UUID,
                clip_asset_sha256 TEXT,
                present BOOLEAN,
                asset_available BOOLEAN,
                media_status TEXT,
                stream_version BIGINT,
                clip_asset_version BIGINT,
                quality_decision TEXT,
                quality_review_version BIGINT)
      ) source_media ON TRUE
), eligible_counts AS (
    SELECT project_id,
           capture_group_id,
           COUNT(*) FILTER (
               WHERE cleaning_outcome = 'PASS'
                 AND revision_context_consistent IS TRUE
                 AND source_media_valid IS TRUE) AS eligible_revision_count
      FROM source_checked
     GROUP BY project_id, capture_group_id
), current_rows AS (
    SELECT source_checked.*,
           ROW_NUMBER() OVER (
               PARTITION BY source_checked.project_id, source_checked.capture_group_id
               ORDER BY source_checked.revision_number DESC,
                        source_checked.submitted_at DESC,
                        source_checked.annotation_revision_id DESC) AS current_revision_rank
      FROM source_checked
)
SELECT current_row.project_id,
       current_row.capture_group_id,
       current_row.annotation_revision_id,
       current_row.revision_number,
       current_row.annotation_document,
       current_row.revision_primary_clip_asset_id AS primary_clip_asset_id,
       current_row.revision_primary_camera_number AS primary_camera_number,
       current_row.revision_confirmed_view_set AS confirmed_view_set,
       current_row.revision_input_version AS input_version,
       current_row.review_annotation_review_id AS annotation_review_id,
       current_row.review_decision,
       current_row.reviewed_by,
       current_row.reviewed_at,
       current_row.review_qualified_views AS qualified_views,
       current_row.review_rejected_missing_views AS rejected_missing_views,
       current_row.review_candidate_evidence AS candidate_evidence,
       current_row.quality_id AS capture_group_quality_review_id,
       current_row.quality_result AS group_quality_result,
       current_row.quality_revision AS group_quality_revision,
       current_row.quality_reviewer_actor_id AS group_quality_reviewed_by,
       current_row.quality_decided_at AS group_quality_decided_at,
       current_row.capture_group_cleaning_id,
       current_row.cleaning_reviewed_by,
       current_row.cleaning_reviewed_at,
       current_row.cleaning_primary_interval AS primary_interval,
       current_row.cleaning_view_evidence AS view_evidence,
       current_row.cleaning_participating_views AS participating_views,
       current_row.cleaning_excluded_views AS excluded_views,
       current_row.cleaning_mapping_provenance AS mapping_provenance,
       current_row.cleaning_candidate_provenance AS candidate_provenance,
       current_row.cleaning_rule_set_key AS rule_set_key,
       current_row.cleaning_rule_set_version AS rule_set_version,
       current_row.cleaning_rule_set_digest AS rule_set_digest,
       current_row.cleaning_outcome,
       current_row.cleaning_reason_codes,
       current_row.cleaning_executed_checks,
       current_row.cleaning_input_fingerprint,
       current_row.cleaning_evaluated_by,
       current_row.cleaning_authorization_basis,
       current_row.cleaning_evaluated_at,
       current_row.context_consistent,
       current_row.source_media_valid,
       counts.eligible_revision_count
  FROM current_rows current_row
  JOIN eligible_counts counts
    ON counts.project_id = current_row.project_id
   AND counts.capture_group_id = current_row.capture_group_id
 WHERE current_row.current_revision_rank = 1;

CREATE VIEW training_data_group_eligibility_v3 AS
SELECT project_id,
       capture_group_id,
       annotation_revision_id,
       revision_number,
       annotation_document,
       primary_clip_asset_id,
       primary_camera_number,
       confirmed_view_set,
       input_version,
       annotation_review_id,
       review_decision,
       reviewed_by,
       reviewed_at,
       qualified_views,
       rejected_missing_views,
       candidate_evidence,
       capture_group_quality_review_id,
       group_quality_result,
       group_quality_revision,
       group_quality_reviewed_by,
       group_quality_decided_at,
       capture_group_cleaning_id,
       cleaning_reviewed_by,
       cleaning_reviewed_at,
       primary_interval,
       view_evidence,
       participating_views,
       excluded_views,
       mapping_provenance,
       candidate_provenance,
       rule_set_key,
       rule_set_version,
       rule_set_digest,
       cleaning_outcome,
       cleaning_reason_codes,
       cleaning_executed_checks,
       cleaning_input_fingerprint,
       cleaning_evaluated_by,
       cleaning_authorization_basis,
       cleaning_evaluated_at
  FROM training_data_group_eligibility_v3_integrity
 WHERE cleaning_outcome = 'PASS'
   AND context_consistent IS TRUE
   AND source_media_valid IS TRUE
   AND eligible_revision_count = 1;
