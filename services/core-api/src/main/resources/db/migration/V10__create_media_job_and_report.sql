CREATE TABLE media_job (
    media_job_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    clip_asset_id UUID NOT NULL,
    job_type VARCHAR(32) NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    input_size_bytes BIGINT NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_media_job_clip_asset
        FOREIGN KEY (clip_asset_id) REFERENCES clip_asset (clip_asset_id) ON DELETE RESTRICT,
    CONSTRAINT uk_media_job_project_natural_key
        UNIQUE (project_id, job_type, clip_asset_id, input_digest),
    CONSTRAINT uk_media_job_project_idempotency
        UNIQUE (project_id, idempotency_key),
    CONSTRAINT ck_media_job_input_digest
        CHECK (input_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_media_job_input_size
        CHECK (input_size_bytes > 0),
    CONSTRAINT ck_media_job_content_type
        CHECK (content_type ~ '^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*$'),
    CONSTRAINT ck_media_job_idempotency_key
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_media_job_request_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_media_job_job_type
        CHECK (job_type IN ('MEDIA_PROBE')),
    CONSTRAINT ck_media_job_status
        CHECK (status IN ('REQUESTED', 'QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCEL_REQUESTED', 'CANCELLED'))
);

CREATE INDEX ix_media_job_project_created
    ON media_job (project_id, created_at DESC, media_job_id DESC);

CREATE INDEX ix_media_job_clip_asset
    ON media_job (clip_asset_id);

CREATE TABLE media_report (
    media_report_id UUID PRIMARY KEY,
    media_job_id UUID NOT NULL,
    container_format VARCHAR(64),
    video_codec VARCHAR(64),
    audio_codec VARCHAR(64),
    has_audio BOOLEAN,
    duration_microseconds BIGINT,
    width INTEGER,
    height INTEGER,
    frame_rate_numerator INTEGER,
    frame_rate_denominator INTEGER,
    bitrate_bps BIGINT,
    color_space VARCHAR(64),
    has_b_frames BOOLEAN,
    sample_aspect_ratio VARCHAR(16),
    creation_time_rfc3339 VARCHAR(64),
    timebase_reference VARCHAR(128),
    soft_hash VARCHAR(64),
    error_class VARCHAR(64),
    error_code VARCHAR(64),
    error_message VARCHAR(512),
    error_retryable BOOLEAN,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_media_report_job
        FOREIGN KEY (media_job_id) REFERENCES media_job (media_job_id) ON DELETE CASCADE,
    CONSTRAINT uk_media_report_job
        UNIQUE (media_job_id),
    CONSTRAINT ck_media_report_width CHECK (width IS NULL OR width > 0),
    CONSTRAINT ck_media_report_height CHECK (height IS NULL OR height > 0),
    CONSTRAINT ck_media_report_duration CHECK (duration_microseconds IS NULL OR duration_microseconds >= 0),
    CONSTRAINT ck_media_report_frame_rate_denominator
        CHECK (frame_rate_denominator IS NULL OR frame_rate_denominator > 0),
    CONSTRAINT ck_media_report_frame_rate_numerator
        CHECK (frame_rate_numerator IS NULL OR frame_rate_numerator >= 0),
    CONSTRAINT ck_media_report_bitrate CHECK (bitrate_bps IS NULL OR bitrate_bps >= 0)
);
