CREATE TABLE ai_evaluation (
    ai_evaluation_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    sample_version VARCHAR(128),
    sample_sha256 VARCHAR(64),
    model_sha256 VARCHAR(64) NOT NULL,
    profile_sha256 VARCHAR(64) NOT NULL,
    evaluation_status VARCHAR(16) NOT NULL,
    parameter_adjustment_note VARCHAR(2000) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    CONSTRAINT fk_ai_evaluation_project
        FOREIGN KEY (project_id) REFERENCES project (project_id) ON DELETE RESTRICT,
    CONSTRAINT ck_ai_evaluation_sample_binding CHECK (sample_version IS NOT NULL OR sample_sha256 IS NOT NULL),
    CONSTRAINT ck_ai_evaluation_sample_version CHECK (sample_version IS NULL OR sample_version ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'),
    CONSTRAINT ck_ai_evaluation_sample_sha256 CHECK (sample_sha256 IS NULL OR sample_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_ai_evaluation_model_sha256 CHECK (model_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_ai_evaluation_profile_sha256 CHECK (profile_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_ai_evaluation_status CHECK (evaluation_status IN ('PENDING', 'RECORDED')),
    CONSTRAINT ck_ai_evaluation_schema_version CHECK (schema_version = 'ai-evaluation.v1'),
    CONSTRAINT ck_ai_evaluation_created_by CHECK (created_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_ai_evaluation_note CHECK (parameter_adjustment_note !~ '[[:cntrl:]]' AND length(btrim(parameter_adjustment_note)) > 0)
);

CREATE TABLE ai_evaluation_metric (
    ai_evaluation_metric_id UUID PRIMARY KEY,
    ai_evaluation_id UUID NOT NULL REFERENCES ai_evaluation (ai_evaluation_id) ON DELETE RESTRICT,
    metric_key VARCHAR(64) NOT NULL,
    metric_status VARCHAR(16) NOT NULL,
    metric_value DOUBLE PRECISION,
    CONSTRAINT uk_ai_evaluation_metric_key UNIQUE (ai_evaluation_id, metric_key),
    CONSTRAINT ck_ai_evaluation_metric_key CHECK (metric_key IN (
        'HAND_FRAME_RECALL',
        'NO_HAND_FRAME_FALSE_POSITIVE_RATE',
        'LANDMARK_21_VALID_RATE',
        'HANDEDNESS_ACCURACY',
        'FAST_MOTION_OCCLUSION_USEFULNESS',
        'OVERLAY_TIME_ERROR',
        'PROCESSING_TIME_PER_MINUTE'
    )),
    CONSTRAINT ck_ai_evaluation_metric_status CHECK (metric_status IN ('PENDING', 'RECORDED')),
    CONSTRAINT ck_ai_evaluation_metric_value CHECK (
        (metric_status = 'PENDING' AND metric_value IS NULL)
        OR (metric_status = 'RECORDED' AND metric_value IS NOT NULL AND metric_value >= 0
            AND metric_value < 'Infinity'::double precision)
    ),
    CONSTRAINT ck_ai_evaluation_metric_range CHECK (
        metric_status = 'PENDING'
        OR metric_key IN ('OVERLAY_TIME_ERROR', 'PROCESSING_TIME_PER_MINUTE')
        OR metric_value <= 1
    )
);

CREATE INDEX ix_ai_evaluation_project_created
    ON ai_evaluation (project_id, created_at DESC, ai_evaluation_id DESC);

CREATE INDEX ix_ai_evaluation_metric_evaluation
    ON ai_evaluation_metric (ai_evaluation_id, metric_key);
