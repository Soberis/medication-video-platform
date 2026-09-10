-- Extend algorithm constraint on ai_job to support Qwen3-VL Action pre-annotation
ALTER TABLE ai_job DROP CONSTRAINT ck_ai_job_algorithm;
ALTER TABLE ai_job ADD CONSTRAINT ck_ai_job_algorithm
    CHECK (algorithm_key IN ('MEDIAPIPE_HAND_LANDMARKER', 'QWEN3_VL_ACTION'));

-- Action Candidate persistent table
CREATE TABLE action_candidate (
    action_candidate_id UUID PRIMARY KEY,
    ai_job_id UUID NOT NULL REFERENCES ai_job (ai_job_id) ON DELETE RESTRICT,
    project_id UUID NOT NULL,
    capture_group_id UUID NOT NULL,
    clip_asset_id UUID NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    action_label VARCHAR(64) NOT NULL,
    confidence NUMERIC(5,4) NOT NULL,
    source_window INTEGER NOT NULL,
    stitched_windows JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_action_candidate_interval CHECK (end_ms > start_ms AND start_ms >= 0),
    CONSTRAINT ck_action_candidate_confidence CHECK (confidence >= 0.0 AND confidence <= 1.0)
);

CREATE INDEX idx_action_candidate_group ON action_candidate (capture_group_id, start_ms);
CREATE INDEX idx_action_candidate_job ON action_candidate (ai_job_id);
