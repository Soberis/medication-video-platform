CREATE TABLE ai_job (
    ai_job_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    clip_asset_id UUID NOT NULL,
    algorithm_key VARCHAR(64) NOT NULL,
    algorithm_version VARCHAR(32) NOT NULL,
    model_sha256 VARCHAR(64) NOT NULL,
    input_sha256 VARCHAR(64) NOT NULL,
    input_size_bytes BIGINT NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    sampling_fps INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_ai_job_idempotency UNIQUE (project_id, idempotency_key),
    CONSTRAINT uk_ai_job_input_algorithm UNIQUE (project_id, clip_asset_id, input_sha256, algorithm_key,
        algorithm_version, model_sha256, sampling_fps),
    CONSTRAINT ck_ai_job_algorithm CHECK (algorithm_key = 'MEDIAPIPE_HAND_LANDMARKER'),
    CONSTRAINT ck_ai_job_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_ai_job_model_sha256 CHECK (model_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_ai_job_input_sha256 CHECK (input_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_ai_job_sampling_fps CHECK (sampling_fps BETWEEN 1 AND 15),
    CONSTRAINT ck_ai_job_idempotency CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_ai_job_fingerprint CHECK (request_fingerprint ~ '^[a-f0-9]{64}$')
);

CREATE TABLE ai_job_attempt (
    ai_job_attempt_id UUID PRIMARY KEY,
    ai_job_id UUID NOT NULL REFERENCES ai_job (ai_job_id) ON DELETE RESTRICT,
    attempt INTEGER NOT NULL,
    message_id UUID NOT NULL,
    lease_token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_ai_job_attempt_number UNIQUE (ai_job_id, attempt),
    CONSTRAINT uk_ai_job_attempt_message UNIQUE (message_id),
    CONSTRAINT ck_ai_job_attempt_positive CHECK (attempt > 0),
    CONSTRAINT ck_ai_job_attempt_lease_hash CHECK (lease_token_hash ~ '^[a-f0-9]{64}$')
);

CREATE TABLE candidate_annotation (
    candidate_annotation_id UUID PRIMARY KEY,
    ai_job_id UUID NOT NULL REFERENCES ai_job (ai_job_id) ON DELETE RESTRICT,
    project_id UUID NOT NULL,
    clip_asset_id UUID NOT NULL,
    frame_timestamp_ms BIGINT NOT NULL,
    hand_index INTEGER NOT NULL,
    handedness VARCHAR(16) NOT NULL,
    handedness_score NUMERIC(5,4) NOT NULL,
    bounding_box JSONB NOT NULL,
    landmarks JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_candidate_annotation_frame_hand UNIQUE (ai_job_id, frame_timestamp_ms, hand_index),
    CONSTRAINT ck_candidate_annotation_timestamp CHECK (frame_timestamp_ms >= 0),
    CONSTRAINT ck_candidate_annotation_hand_index CHECK (hand_index BETWEEN 0 AND 1),
    CONSTRAINT ck_candidate_annotation_handedness CHECK (handedness IN ('LEFT', 'RIGHT', 'UNKNOWN')),
    CONSTRAINT ck_candidate_annotation_handedness_score CHECK (handedness_score >= 0 AND handedness_score <= 1),
    CONSTRAINT ck_candidate_annotation_box CHECK (jsonb_typeof(bounding_box) = 'object'),
    CONSTRAINT ck_candidate_annotation_landmarks CHECK (jsonb_typeof(landmarks) = 'array')
);

CREATE INDEX ix_ai_job_project_created ON ai_job (project_id, created_at DESC, ai_job_id DESC);
CREATE INDEX ix_candidate_annotation_job_frame ON candidate_annotation (ai_job_id, frame_timestamp_ms, hand_index);
