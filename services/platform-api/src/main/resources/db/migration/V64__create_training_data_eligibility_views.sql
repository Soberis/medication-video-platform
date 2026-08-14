-- The two read-only views are the single eligibility source for the explicit P0
-- training-data API and tools/minimal-export. They do not create a dataset, job,
-- queue, worker or new business table.

CREATE VIEW training_data_eligibility AS
WITH latest_revision AS (
    SELECT DISTINCT ON (revision.project_id, revision.clip_asset_id)
           revision.project_id,
           revision.clip_asset_id,
           revision.annotation_revision_id,
           revision.annotation_task_id,
           revision.revision_number,
           revision.annotation_document,
           revision.document_sha256,
           revision.label_schema_id,
           revision.label_schema_digest,
           task.task_status
    FROM annotation_revision revision
    JOIN annotation_task task
      ON task.project_id = revision.project_id
     AND task.annotation_task_id = revision.annotation_task_id
     AND task.clip_asset_id = revision.clip_asset_id
    WHERE task.task_status = 'COMPLETED'
    ORDER BY revision.project_id,
             revision.clip_asset_id,
             revision.revision_number DESC,
             revision.submitted_at DESC,
             revision.annotation_revision_id DESC
), latest_cleaning AS (
    SELECT DISTINCT ON (result.project_id, result.annotation_revision_id)
           result.project_id,
           result.annotation_revision_id,
           result.cleaning_result_id,
           result.cleaning_run_id,
           result.outcome AS cleaning_outcome,
           result.evaluated_at,
           run.clip_asset_id,
           run.annotation_review_id,
           run.review_decision,
           run.cleaning_rule_set_id,
           run.rule_set_digest,
           run.asset_sha256,
           run.document_sha256,
           run.label_schema_id,
           run.label_schema_digest,
           run.quality_review_version,
           run.run_status AS cleaning_run_status
    FROM cleaning_result result
    JOIN cleaning_run run
      ON run.project_id = result.project_id
     AND run.cleaning_run_id = result.cleaning_run_id
     AND run.annotation_revision_id = result.annotation_revision_id
    JOIN cleaning_rule_set rule_set
      ON rule_set.cleaning_rule_set_id = run.cleaning_rule_set_id
     AND rule_set.rule_set_digest = run.rule_set_digest
    ORDER BY result.project_id,
             result.annotation_revision_id,
             result.evaluated_at DESC,
             result.cleaning_result_id DESC
)
SELECT asset.project_id,
       asset.clip_asset_id AS video_id,
       asset.content_type,
       asset.asset_status,
       asset.deleted_at IS NOT NULL AS asset_deleted,
       asset.sha256 AS asset_sha256,
       quality.decision AS quality_decision,
       quality.version AS quality_review_version,
       revision.annotation_revision_id,
       revision.annotation_task_id,
       revision.task_status,
       revision.revision_number,
       revision.annotation_document,
       revision.document_sha256,
       revision.label_schema_id,
       revision.label_schema_digest,
       review.annotation_review_id,
       review.decision AS annotation_review_decision,
       cleaning.cleaning_result_id,
       cleaning.cleaning_run_id,
       cleaning.cleaning_outcome,
       cleaning.cleaning_run_status,
       cleaning.cleaning_rule_set_id,
       cleaning.rule_set_digest
FROM clip_asset asset
JOIN quality_review quality
  ON quality.project_id = asset.project_id
 AND quality.clip_asset_id = asset.clip_asset_id
 AND quality.decision = 'QUALIFIED'
JOIN latest_revision revision
  ON revision.project_id = asset.project_id
 AND revision.clip_asset_id = asset.clip_asset_id
JOIN annotation_review review
  ON review.project_id = revision.project_id
 AND review.annotation_revision_id = revision.annotation_revision_id
 AND review.decision = 'APPROVED'
JOIN latest_cleaning cleaning
  ON cleaning.project_id = asset.project_id
 AND cleaning.clip_asset_id = asset.clip_asset_id
 AND cleaning.annotation_revision_id = revision.annotation_revision_id
 AND cleaning.annotation_review_id = review.annotation_review_id
 AND cleaning.review_decision = review.decision
 AND cleaning.quality_review_version = quality.version
 AND cleaning.asset_sha256 = asset.sha256
 AND cleaning.document_sha256 = revision.document_sha256
 AND cleaning.label_schema_id = revision.label_schema_id
 AND cleaning.label_schema_digest = revision.label_schema_digest
 AND cleaning.cleaning_outcome = 'PASS'
 AND cleaning.cleaning_run_status = 'SUCCEEDED'
WHERE asset.asset_status = 'INGESTED'
  AND asset.deleted_at IS NULL
  AND lower(asset.content_type) = 'video/mp4';

CREATE VIEW training_data_candidate_observations AS
WITH latest_successful_job AS (
    SELECT DISTINCT ON (job.project_id, job.clip_asset_id)
           job.project_id,
           job.clip_asset_id,
           job.ai_job_id,
           job.algorithm_key,
           job.algorithm_version,
           job.model_sha256,
           job.profile_sha256,
           job.status AS ai_job_status
    FROM ai_job job
    JOIN clip_asset asset
      ON asset.project_id = job.project_id
     AND asset.clip_asset_id = job.clip_asset_id
     AND asset.sha256 = job.input_sha256
     AND lower(asset.content_type) = lower(job.content_type)
    WHERE job.algorithm_key = 'MEDIAPIPE_HAND_LANDMARKER'
      AND job.status = 'SUCCEEDED'
    ORDER BY job.project_id,
             job.clip_asset_id,
             job.created_at DESC,
             job.ai_job_id DESC
)
SELECT eligibility.project_id,
       eligibility.video_id,
       eligibility.annotation_revision_id,
       eligibility.cleaning_run_id,
       eligibility.cleaning_result_id,
       job.ai_job_id,
       job.algorithm_key,
       job.algorithm_version,
       job.model_sha256,
       job.profile_sha256,
       job.ai_job_status,
       candidate.candidate_annotation_id,
       candidate.frame_timestamp_ms,
       candidate.hand_index,
       candidate.handedness,
       candidate.handedness_score,
       candidate.bounding_box,
       candidate.landmarks,
       candidate.image_width,
       candidate.image_height,
       candidate.valid_landmark_count
FROM training_data_eligibility eligibility
JOIN latest_successful_job job
  ON job.project_id = eligibility.project_id
 AND job.clip_asset_id = eligibility.video_id
JOIN candidate_annotation candidate
  ON candidate.project_id = job.project_id
 AND candidate.ai_job_id = job.ai_job_id
 AND candidate.clip_asset_id = job.clip_asset_id
ORDER BY eligibility.project_id,
         eligibility.video_id,
         candidate.frame_timestamp_ms,
         candidate.hand_index,
         candidate.candidate_annotation_id;
