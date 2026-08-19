ALTER TABLE candidate_annotation
    ADD COLUMN image_width INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN image_height INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN valid_landmark_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE candidate_annotation
    ADD CONSTRAINT ck_candidate_annotation_image_width CHECK (image_width > 0),
    ADD CONSTRAINT ck_candidate_annotation_image_height CHECK (image_height > 0),
    ADD CONSTRAINT ck_candidate_annotation_valid_landmarks CHECK (valid_landmark_count BETWEEN 0 AND 21);

CREATE TABLE ai_job_result_inbox (
    message_id UUID PRIMARY KEY,
    ai_job_id UUID NOT NULL REFERENCES ai_job (ai_job_id) ON DELETE RESTRICT,
    attempt INTEGER NOT NULL,
    result_sha256 VARCHAR(64) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_ai_job_result_inbox_digest CHECK (result_sha256 ~ '^[a-f0-9]{64}$')
);
