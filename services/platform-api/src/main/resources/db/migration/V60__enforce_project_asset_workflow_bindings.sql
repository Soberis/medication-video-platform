-- Preserve project and clip identity through the annotation workflow. The
-- existing foreign keys checked task and clip independently, allowing a
-- direct SQL client to combine a task from one asset with a revision for
-- another asset in the same project.
ALTER TABLE annotation_task
    ADD CONSTRAINT uk_annotation_task_project_task_clip
        UNIQUE (project_id, annotation_task_id, clip_asset_id);

ALTER TABLE annotation_revision
    ADD CONSTRAINT fk_annotation_revision_task_clip
        FOREIGN KEY (project_id, annotation_task_id, clip_asset_id)
        REFERENCES annotation_task (project_id, annotation_task_id, clip_asset_id)
        ON DELETE RESTRICT;

-- AI jobs and candidate rows carry project/clip denormalized facts for query
-- isolation. Make those facts database-enforced and bind a candidate to the
-- exact AI job/clip pair that produced it.
ALTER TABLE ai_job
    ADD CONSTRAINT uk_ai_job_project_job_clip
        UNIQUE (project_id, ai_job_id, clip_asset_id),
    ADD CONSTRAINT fk_ai_job_project_clip
        FOREIGN KEY (project_id, clip_asset_id)
        REFERENCES clip_asset (project_id, clip_asset_id)
        ON DELETE RESTRICT;

ALTER TABLE candidate_annotation
    ADD CONSTRAINT fk_candidate_annotation_project_job_clip
        FOREIGN KEY (project_id, ai_job_id, clip_asset_id)
        REFERENCES ai_job (project_id, ai_job_id, clip_asset_id)
        ON DELETE RESTRICT;
