-- Switch the action pre-annotation algorithm family from Qwen3-VL to Qwen3.5.
-- The legacy value stays in the constraint so existing ai_job rows remain valid.
ALTER TABLE ai_job DROP CONSTRAINT ck_ai_job_algorithm;
ALTER TABLE ai_job ADD CONSTRAINT ck_ai_job_algorithm
    CHECK (algorithm_key IN ('MEDIAPIPE_HAND_LANDMARKER', 'QWEN3_VL_ACTION', 'QWEN3_5_ACTION'));
