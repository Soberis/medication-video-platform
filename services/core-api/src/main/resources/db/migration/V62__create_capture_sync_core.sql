ALTER TABLE capture_session
    ADD CONSTRAINT uk_capture_session_id_project UNIQUE (capture_session_id, project_id);

ALTER TABLE camera_set_snapshot
    ADD CONSTRAINT uk_camera_set_snapshot_id_session UNIQUE (camera_set_snapshot_id, capture_session_id);

ALTER TABLE camera_set_snapshot_member
    ADD CONSTRAINT uk_camera_set_snapshot_member_id_snapshot
        UNIQUE (camera_set_snapshot_member_id, camera_set_snapshot_id);

ALTER TABLE camera_stream
    ADD CONSTRAINT uk_camera_stream_id_session UNIQUE (camera_stream_id, capture_session_id);

ALTER TABLE camera_stream
    ADD CONSTRAINT uk_camera_stream_id_asset_session
        UNIQUE (camera_stream_id, clip_asset_id, capture_session_id);

ALTER TABLE clip_asset
    ADD CONSTRAINT uk_clip_asset_id_project UNIQUE (clip_asset_id, project_id);

ALTER TABLE media_report
    ADD CONSTRAINT uk_media_report_id_asset_project
        UNIQUE (media_report_id, clip_asset_id, project_id);

CREATE TABLE capture_sync_job (
    capture_sync_job_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    capture_session_id UUID NOT NULL,
    camera_set_snapshot_id UUID NOT NULL,
    expected_camera_count INTEGER NOT NULL,
    input_manifest_sha256 VARCHAR(64) NOT NULL,
    input_manifest JSONB NOT NULL,
    sync_profile_key VARCHAR(128) NOT NULL,
    sync_profile_version VARCHAR(64) NOT NULL,
    sync_profile_sha256 VARCHAR(64) NOT NULL,
    algorithm_key VARCHAR(128) NOT NULL,
    algorithm_version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_capture_sync_job_project
        FOREIGN KEY (project_id) REFERENCES project (project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_sync_job_capture_project
        FOREIGN KEY (capture_session_id, project_id)
        REFERENCES capture_session (capture_session_id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_sync_job_snapshot_session
        FOREIGN KEY (camera_set_snapshot_id, capture_session_id)
        REFERENCES camera_set_snapshot (camera_set_snapshot_id, capture_session_id) ON DELETE RESTRICT,
    CONSTRAINT uk_capture_sync_job_business
        UNIQUE (project_id, capture_session_id, input_manifest_sha256, sync_profile_sha256,
                algorithm_key, algorithm_version),
    CONSTRAINT uk_capture_sync_job_scope
        UNIQUE (capture_sync_job_id, project_id, capture_session_id, camera_set_snapshot_id),
    CONSTRAINT uk_capture_sync_job_idempotency
        UNIQUE (project_id, idempotency_key),
    CONSTRAINT ck_capture_sync_job_expected_cameras
        CHECK (expected_camera_count BETWEEN 1 AND 64),
    CONSTRAINT ck_capture_sync_job_input_digest
        CHECK (input_manifest_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_capture_sync_job_input_manifest
        CHECK (jsonb_typeof(input_manifest) = 'object'),
    CONSTRAINT ck_capture_sync_job_profile_key
        CHECK (sync_profile_key ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'),
    CONSTRAINT ck_capture_sync_job_profile_version
        CHECK (sync_profile_version ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}$'),
    CONSTRAINT ck_capture_sync_job_profile_digest
        CHECK (sync_profile_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_capture_sync_job_algorithm_key
        CHECK (algorithm_key ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'),
    CONSTRAINT ck_capture_sync_job_algorithm_version
        CHECK (algorithm_version ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}$'),
    CONSTRAINT ck_capture_sync_job_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_capture_sync_job_idempotency
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_capture_sync_job_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_capture_sync_job_requested_by
        CHECK (requested_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_capture_sync_job_version CHECK (version >= 0)
);

CREATE INDEX ix_capture_sync_job_project_created
    ON capture_sync_job (project_id, created_at DESC, capture_sync_job_id DESC);

CREATE INDEX ix_capture_sync_job_capture_status
    ON capture_sync_job (capture_session_id, status, created_at DESC);

CREATE TABLE capture_sync_job_attempt (
    capture_sync_job_attempt_id UUID PRIMARY KEY,
    capture_sync_job_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    message_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    lease_token_hash VARCHAR(64) NOT NULL,
    worker_id VARCHAR(128),
    lease_started_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    error_class VARCHAR(64),
    error_code VARCHAR(64),
    safe_error_message VARCHAR(512),
    error_retryable BOOLEAN,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_capture_sync_attempt_job
        FOREIGN KEY (capture_sync_job_id) REFERENCES capture_sync_job (capture_sync_job_id) ON DELETE RESTRICT,
    CONSTRAINT uk_capture_sync_attempt_number UNIQUE (capture_sync_job_id, attempt),
    CONSTRAINT uk_capture_sync_attempt_message UNIQUE (message_id),
    CONSTRAINT uk_capture_sync_attempt_id_job UNIQUE (capture_sync_job_attempt_id, capture_sync_job_id),
    CONSTRAINT ck_capture_sync_attempt_number CHECK (attempt > 0),
    CONSTRAINT ck_capture_sync_attempt_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'LOST', 'CANCELLED')),
    CONSTRAINT ck_capture_sync_attempt_lease_hash
        CHECK (lease_token_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_capture_sync_attempt_lease_times CHECK (
        (lease_started_at IS NULL AND lease_until IS NULL AND last_heartbeat_at IS NULL)
        OR (lease_started_at IS NOT NULL AND lease_until IS NOT NULL
            AND last_heartbeat_at IS NOT NULL AND lease_until > lease_started_at)
    ),
    CONSTRAINT ck_capture_sync_attempt_error_fields CHECK (
        (status NOT IN ('FAILED', 'LOST')
            AND error_class IS NULL AND error_code IS NULL
            AND safe_error_message IS NULL AND error_retryable IS NULL)
        OR (status IN ('FAILED', 'LOST')
            AND error_class IS NOT NULL AND error_code IS NOT NULL
            AND safe_error_message IS NOT NULL AND error_retryable IS NOT NULL)
    ),
    CONSTRAINT ck_capture_sync_attempt_version CHECK (version >= 0)
);

CREATE INDEX ix_capture_sync_attempt_job_status
    ON capture_sync_job_attempt (capture_sync_job_id, status, attempt DESC);

CREATE INDEX ix_capture_sync_attempt_expired_lease
    ON capture_sync_job_attempt (lease_until, capture_sync_job_id, attempt)
    WHERE status = 'RUNNING';

CREATE TABLE capture_sync_revision (
    capture_sync_revision_id UUID PRIMARY KEY,
    capture_sync_job_id UUID NOT NULL,
    capture_sync_job_attempt_id UUID NOT NULL,
    project_id UUID NOT NULL,
    capture_session_id UUID NOT NULL,
    camera_set_snapshot_id UUID NOT NULL,
    supersedes_sync_revision_id UUID,
    reference_camera_number INTEGER,
    reference_clock_source VARCHAR(128) NOT NULL,
    time_basis_source VARCHAR(128) NOT NULL,
    capture_origin_utc TIMESTAMP WITH TIME ZONE,
    time_precision_ns BIGINT,
    timeline_rate_numerator BIGINT NOT NULL,
    timeline_rate_denominator BIGINT NOT NULL,
    timeline_step_numerator_ns BIGINT NOT NULL,
    timeline_step_denominator BIGINT NOT NULL,
    allowed_tolerance_ns BIGINT NOT NULL,
    algorithm_key VARCHAR(128) NOT NULL,
    algorithm_version VARCHAR(64) NOT NULL,
    sync_profile_key VARCHAR(128) NOT NULL,
    sync_profile_version VARCHAR(64) NOT NULL,
    sync_profile_sha256 VARCHAR(64) NOT NULL,
    input_manifest_sha256 VARCHAR(64) NOT NULL,
    logical_content_sha256 VARCHAR(64) NOT NULL,
    camera_count_expected INTEGER NOT NULL,
    camera_count_received BIGINT NOT NULL,
    sync_frame_count BIGINT NOT NULL,
    present_frame_count BIGINT NOT NULL,
    missing_frame_count BIGINT NOT NULL,
    invalid_frame_count BIGINT NOT NULL,
    max_sync_error_ns BIGINT,
    p95_sync_error_ns BIGINT,
    processing_duration_ms BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    CONSTRAINT fk_capture_sync_revision_job
        FOREIGN KEY (capture_sync_job_id) REFERENCES capture_sync_job (capture_sync_job_id) ON DELETE RESTRICT,
    CONSTRAINT uk_capture_sync_revision_job UNIQUE (capture_sync_job_id),
    CONSTRAINT uk_capture_sync_revision_scope
        UNIQUE (capture_sync_revision_id, project_id, capture_session_id, camera_set_snapshot_id),
    CONSTRAINT fk_capture_sync_revision_attempt
        FOREIGN KEY (capture_sync_job_attempt_id, capture_sync_job_id)
        REFERENCES capture_sync_job_attempt (capture_sync_job_attempt_id, capture_sync_job_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_sync_revision_job_scope
        FOREIGN KEY (capture_sync_job_id, project_id, capture_session_id, camera_set_snapshot_id)
        REFERENCES capture_sync_job (capture_sync_job_id, project_id, capture_session_id,
                                      camera_set_snapshot_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_sync_revision_project
        FOREIGN KEY (project_id) REFERENCES project (project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_sync_revision_capture_project
        FOREIGN KEY (capture_session_id, project_id)
        REFERENCES capture_session (capture_session_id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_sync_revision_snapshot_session
        FOREIGN KEY (camera_set_snapshot_id, capture_session_id)
        REFERENCES camera_set_snapshot (camera_set_snapshot_id, capture_session_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_sync_revision_supersedes
        FOREIGN KEY (supersedes_sync_revision_id) REFERENCES capture_sync_revision (capture_sync_revision_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_capture_sync_revision_supersedes_scope
        FOREIGN KEY (supersedes_sync_revision_id, project_id, capture_session_id, camera_set_snapshot_id)
        REFERENCES capture_sync_revision (capture_sync_revision_id, project_id, capture_session_id,
                                           camera_set_snapshot_id) ON DELETE RESTRICT,
    CONSTRAINT ck_capture_sync_revision_reference_camera
        CHECK (reference_camera_number IS NULL OR reference_camera_number BETWEEN 1 AND 64),
    CONSTRAINT ck_capture_sync_revision_reference_clock
        CHECK (reference_clock_source ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'),
    CONSTRAINT ck_capture_sync_revision_time_basis
        CHECK (time_basis_source ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'),
    CONSTRAINT ck_capture_sync_revision_precision
        CHECK (time_precision_ns IS NULL OR time_precision_ns > 0),
    CONSTRAINT ck_capture_sync_revision_rate
        CHECK (timeline_rate_numerator > 0 AND timeline_rate_denominator > 0
            AND timeline_step_numerator_ns > 0 AND timeline_step_denominator > 0),
    CONSTRAINT ck_capture_sync_revision_tolerance CHECK (allowed_tolerance_ns >= 0),
    CONSTRAINT ck_capture_sync_revision_algorithm_key
        CHECK (algorithm_key ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'),
    CONSTRAINT ck_capture_sync_revision_algorithm_version
        CHECK (algorithm_version ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}$'),
    CONSTRAINT ck_capture_sync_revision_profile_key
        CHECK (sync_profile_key ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'),
    CONSTRAINT ck_capture_sync_revision_profile_version
        CHECK (sync_profile_version ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}$'),
    CONSTRAINT ck_capture_sync_revision_profile_digest
        CHECK (sync_profile_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_capture_sync_revision_input_digest
        CHECK (input_manifest_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_capture_sync_revision_logical_digest
        CHECK (logical_content_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_capture_sync_revision_counts CHECK (
        camera_count_expected BETWEEN 1 AND 64
        AND camera_count_received BETWEEN 0 AND camera_count_expected
        AND sync_frame_count >= 0
        AND present_frame_count >= 0
        AND missing_frame_count >= 0
        AND invalid_frame_count >= 0
        AND present_frame_count + missing_frame_count + invalid_frame_count
            = sync_frame_count * camera_count_expected
        AND processing_duration_ms >= 0
    ),
    CONSTRAINT ck_capture_sync_revision_error_summary CHECK (
        (max_sync_error_ns IS NULL OR max_sync_error_ns >= 0)
        AND (p95_sync_error_ns IS NULL OR p95_sync_error_ns >= 0)
    ),
    CONSTRAINT ck_capture_sync_revision_created_by
        CHECK (created_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$')
);

CREATE INDEX ix_capture_sync_revision_capture_created
    ON capture_sync_revision (project_id, capture_session_id, created_at DESC, capture_sync_revision_id DESC);

CREATE TABLE capture_sync_stream_revision (
    capture_sync_stream_revision_id UUID PRIMARY KEY,
    capture_sync_revision_id UUID NOT NULL,
    project_id UUID NOT NULL,
    capture_session_id UUID NOT NULL,
    camera_set_snapshot_id UUID NOT NULL,
    camera_set_snapshot_member_id UUID NOT NULL,
    camera_number INTEGER NOT NULL,
    camera_stream_id UUID,
    clip_asset_id UUID,
    stream_presence_state VARCHAR(16) NOT NULL,
    source_input_sha256 VARCHAR(64),
    source_media_report_id UUID,
    source_time_base_digest VARCHAR(64),
    anchor_source_pts_ns BIGINT,
    anchor_capture_timestamp_ns BIGINT,
    drift_ppm NUMERIC(38,18),
    present_frame_count BIGINT NOT NULL,
    missing_frame_count BIGINT NOT NULL,
    invalid_frame_count BIGINT NOT NULL,
    max_sync_error_ns BIGINT,
    p95_sync_error_ns BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_capture_sync_stream_revision_revision
        FOREIGN KEY (capture_sync_revision_id) REFERENCES capture_sync_revision (capture_sync_revision_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_capture_sync_stream_revision_revision_scope
        FOREIGN KEY (capture_sync_revision_id, project_id, capture_session_id, camera_set_snapshot_id)
        REFERENCES capture_sync_revision (capture_sync_revision_id, project_id, capture_session_id,
                                           camera_set_snapshot_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_sync_stream_revision_project
        FOREIGN KEY (project_id) REFERENCES project (project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_sync_stream_revision_capture_project
        FOREIGN KEY (capture_session_id, project_id)
        REFERENCES capture_session (capture_session_id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_sync_stream_revision_snapshot_session
        FOREIGN KEY (camera_set_snapshot_id, capture_session_id)
        REFERENCES camera_set_snapshot (camera_set_snapshot_id, capture_session_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_sync_stream_revision_member_snapshot
        FOREIGN KEY (camera_set_snapshot_member_id, camera_set_snapshot_id)
        REFERENCES camera_set_snapshot_member (camera_set_snapshot_member_id, camera_set_snapshot_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_capture_sync_stream_revision_stream_session
        FOREIGN KEY (camera_stream_id, clip_asset_id, capture_session_id)
        REFERENCES camera_stream (camera_stream_id, clip_asset_id, capture_session_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_sync_stream_revision_asset_project
        FOREIGN KEY (clip_asset_id, project_id)
        REFERENCES clip_asset (clip_asset_id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_capture_sync_stream_revision_media_report
        FOREIGN KEY (source_media_report_id, clip_asset_id, project_id)
        REFERENCES media_report (media_report_id, clip_asset_id, project_id) ON DELETE RESTRICT,
    CONSTRAINT uk_capture_sync_stream_revision_camera
        UNIQUE (capture_sync_revision_id, camera_number),
    CONSTRAINT uk_capture_sync_stream_revision_member
        UNIQUE (capture_sync_revision_id, camera_set_snapshot_member_id),
    CONSTRAINT ck_capture_sync_stream_revision_camera
        CHECK (camera_number BETWEEN 1 AND 64),
    CONSTRAINT ck_capture_sync_stream_revision_state
        CHECK (stream_presence_state IN ('PRESENT', 'MISSING', 'INVALID')),
    CONSTRAINT ck_capture_sync_stream_revision_presence_facts CHECK (
        (stream_presence_state = 'MISSING'
            AND camera_stream_id IS NULL AND clip_asset_id IS NULL
            AND source_input_sha256 IS NULL AND source_media_report_id IS NULL
            AND source_time_base_digest IS NULL AND anchor_source_pts_ns IS NULL
            AND anchor_capture_timestamp_ns IS NULL AND drift_ppm IS NULL)
        OR (stream_presence_state IN ('PRESENT', 'INVALID')
            AND camera_stream_id IS NOT NULL AND clip_asset_id IS NOT NULL
            AND source_input_sha256 ~ '^[a-f0-9]{64}$'
            AND source_media_report_id IS NOT NULL
            AND source_time_base_digest ~ '^[a-f0-9]{64}$'
            AND anchor_source_pts_ns IS NOT NULL AND anchor_capture_timestamp_ns IS NOT NULL)
    ),
    CONSTRAINT ck_capture_sync_stream_revision_drift
        CHECK (drift_ppm IS NULL OR drift_ppm > -1000000),
    CONSTRAINT ck_capture_sync_stream_revision_counts CHECK (
        present_frame_count >= 0 AND missing_frame_count >= 0 AND invalid_frame_count >= 0
        AND (max_sync_error_ns IS NULL OR max_sync_error_ns >= 0)
        AND (p95_sync_error_ns IS NULL OR p95_sync_error_ns >= 0)
    )
);

CREATE INDEX ix_capture_sync_stream_revision_revision_camera
    ON capture_sync_stream_revision (capture_sync_revision_id, camera_number);

CREATE FUNCTION validate_capture_sync_stream_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    snapshot_camera_index INTEGER;
    stream_camera_index INTEGER;
BEGIN
    SELECT camera_index
    INTO snapshot_camera_index
    FROM camera_set_snapshot_member
    WHERE camera_set_snapshot_member_id = NEW.camera_set_snapshot_member_id
      AND camera_set_snapshot_id = NEW.camera_set_snapshot_id;

    IF snapshot_camera_index IS NULL OR NEW.camera_number <> snapshot_camera_index + 1 THEN
        RAISE EXCEPTION 'capture synchronization camera identity does not match its frozen snapshot member';
    END IF;

    IF NEW.camera_stream_id IS NOT NULL THEN
        SELECT camera_index
        INTO stream_camera_index
        FROM camera_stream
        WHERE camera_stream_id = NEW.camera_stream_id
          AND clip_asset_id = NEW.clip_asset_id
          AND capture_session_id = NEW.capture_session_id;

        IF stream_camera_index IS NULL OR NEW.camera_number <> stream_camera_index + 1 THEN
            RAISE EXCEPTION 'capture synchronization camera identity does not match its source stream';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_capture_sync_stream_identity
BEFORE INSERT ON capture_sync_stream_revision
FOR EACH ROW
EXECUTE FUNCTION validate_capture_sync_stream_identity();

CREATE FUNCTION reject_capture_sync_revision_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'capture synchronization revisions are immutable';
END;
$$;

CREATE TRIGGER trg_capture_sync_revision_immutable
BEFORE UPDATE OR DELETE ON capture_sync_revision
FOR EACH ROW
EXECUTE FUNCTION reject_capture_sync_revision_mutation();

CREATE TRIGGER trg_capture_sync_stream_revision_immutable
BEFORE UPDATE OR DELETE ON capture_sync_stream_revision
FOR EACH ROW
EXECUTE FUNCTION reject_capture_sync_revision_mutation();
