ALTER TABLE camera_set_member
    ADD COLUMN enabled_for_hand_detection BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE camera_set_snapshot_member
    ADD COLUMN enabled_for_hand_detection BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE ai_job
    ADD COLUMN hand_result VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD CONSTRAINT ck_ai_job_hand_result
        CHECK (hand_result IN ('UNKNOWN', 'HAND_DETECTED', 'NO_HAND_DETECTED'));

ALTER TABLE ai_job_attempt
    ADD COLUMN analysis_complete BOOLEAN,
    ADD COLUMN hand_result VARCHAR(32),
    ADD COLUMN started_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN total_video_frames BIGINT,
    ADD COLUMN inference_frame_count BIGINT,
    ADD COLUMN frames_with_hand BIGINT,
    ADD COLUMN frames_without_hand BIGINT,
    ADD COLUMN left_hand_detection_count BIGINT,
    ADD COLUMN right_hand_detection_count BIGINT,
    ADD COLUMN invalid_landmark_count BIGINT,
    ADD COLUMN processing_duration_ms BIGINT,
    ADD COLUMN failure_reason VARCHAR(128),
    ADD CONSTRAINT ck_ai_job_attempt_hand_result
        CHECK (hand_result IS NULL OR hand_result IN ('UNKNOWN', 'HAND_DETECTED', 'NO_HAND_DETECTED')),
    ADD CONSTRAINT ck_ai_job_attempt_summary_counts
        CHECK (
            (total_video_frames IS NULL OR total_video_frames >= 0)
            AND (inference_frame_count IS NULL OR inference_frame_count >= 0)
            AND (frames_with_hand IS NULL OR frames_with_hand >= 0)
            AND (frames_without_hand IS NULL OR frames_without_hand >= 0)
            AND (left_hand_detection_count IS NULL OR left_hand_detection_count >= 0)
            AND (right_hand_detection_count IS NULL OR right_hand_detection_count >= 0)
            AND (invalid_landmark_count IS NULL OR invalid_landmark_count >= 0)
            AND (processing_duration_ms IS NULL OR processing_duration_ms >= 0)
        );

CREATE INDEX ix_ai_job_attempt_terminal_summary
    ON ai_job_attempt (ai_job_id, status, completed_at DESC, ai_job_attempt_id DESC);
