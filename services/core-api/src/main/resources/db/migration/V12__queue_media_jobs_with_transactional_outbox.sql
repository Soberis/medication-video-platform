CREATE TABLE media_job_attempt (
    media_job_attempt_id UUID PRIMARY KEY,
    media_job_id UUID NOT NULL,
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
    CONSTRAINT fk_media_job_attempt_job
        FOREIGN KEY (media_job_id) REFERENCES media_job (media_job_id) ON DELETE RESTRICT,
    CONSTRAINT uk_media_job_attempt_number UNIQUE (media_job_id, attempt),
    CONSTRAINT uk_media_job_attempt_message UNIQUE (message_id),
    CONSTRAINT ck_media_job_attempt_number CHECK (attempt > 0),
    CONSTRAINT ck_media_job_attempt_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_media_job_attempt_lease_hash
        CHECK (lease_token_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_media_job_attempt_lease_times CHECK (
        (lease_started_at IS NULL AND lease_until IS NULL AND last_heartbeat_at IS NULL)
        OR (lease_started_at IS NOT NULL AND lease_until IS NOT NULL
            AND last_heartbeat_at IS NOT NULL AND lease_until > lease_started_at)
    ),
    CONSTRAINT ck_media_job_attempt_error_fields CHECK (
        (status <> 'FAILED' AND error_class IS NULL AND error_code IS NULL
            AND safe_error_message IS NULL AND error_retryable IS NULL)
        OR (status = 'FAILED' AND error_class IS NOT NULL AND error_code IS NOT NULL
            AND safe_error_message IS NOT NULL AND error_retryable IS NOT NULL)
    )
);

CREATE INDEX ix_media_job_attempt_job_status
    ON media_job_attempt (media_job_id, status, attempt DESC);

CREATE TABLE outbox_event (
    outbox_event_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    exchange_name VARCHAR(128) NOT NULL,
    routing_key VARCHAR(128) NOT NULL,
    payload_json JSONB NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claim_token UUID,
    claimed_until TIMESTAMP WITH TIME ZONE,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    published_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(64),
    CONSTRAINT ck_outbox_event_payload_object CHECK (jsonb_typeof(payload_json) = 'object'),
    CONSTRAINT ck_outbox_event_claim CHECK (
        (claim_token IS NULL AND claimed_until IS NULL)
        OR (claim_token IS NOT NULL AND claimed_until IS NOT NULL)
    ),
    CONSTRAINT ck_outbox_event_publish_attempts CHECK (publish_attempts >= 0),
    CONSTRAINT ck_outbox_event_published_claim CHECK (
        published_at IS NULL OR (claim_token IS NULL AND claimed_until IS NULL)
    )
);

CREATE INDEX ix_outbox_event_pending
    ON outbox_event (available_at, occurred_at, outbox_event_id)
    WHERE published_at IS NULL;

DO $$
DECLARE
    existing_job RECORD;
    generated_message_id UUID;
    generated_lease_token VARCHAR(128);
BEGIN
    IF EXISTS (SELECT 1 FROM media_job WHERE status <> 'REQUESTED') THEN
        RAISE EXCEPTION 'V12 cannot backfill media jobs that already left REQUESTED';
    END IF;

    FOR existing_job IN SELECT * FROM media_job WHERE status = 'REQUESTED'
    LOOP
        generated_message_id := gen_random_uuid();
        generated_lease_token := replace(gen_random_uuid()::text, '-', '')
            || replace(gen_random_uuid()::text, '-', '');

        INSERT INTO media_job_attempt (
            media_job_attempt_id, media_job_id, attempt, message_id, status,
            lease_token_hash, created_at, updated_at)
        VALUES (
            gen_random_uuid(), existing_job.media_job_id, 1, generated_message_id, 'QUEUED',
            encode(sha256(convert_to(generated_lease_token, 'UTF8')), 'hex'),
            existing_job.created_at, existing_job.updated_at);

        INSERT INTO outbox_event (
            outbox_event_id, aggregate_type, aggregate_id, event_type, schema_version,
            exchange_name, routing_key, payload_json, occurred_at, available_at)
        VALUES (
            gen_random_uuid(), 'MediaJob', existing_job.media_job_id,
            'MediaProbeJobRequestedV1', 'media-probe-job.v1', 'platform.media.v1',
            'media.probe.requested.v1',
            jsonb_build_object(
                'schema_version', 'media-probe-job.v1',
                'message_id', generated_message_id,
                'job_id', existing_job.media_job_id,
                'job_type', existing_job.job_type,
                'attempt', 1,
                'lease_token', generated_lease_token,
                'project_id', existing_job.project_id,
                'clip_asset_id', existing_job.clip_asset_id,
                'input_sha256', existing_job.input_digest,
                'input_size_bytes', existing_job.input_size_bytes,
                'content_type', existing_job.content_type,
                'probe_profile_key', existing_job.probe_profile_key,
                'recipe_digest', existing_job.recipe_digest,
                'requested_at', existing_job.created_at),
            existing_job.created_at, existing_job.created_at);

        UPDATE media_job
        SET status = 'QUEUED'
        WHERE media_job_id = existing_job.media_job_id;
    END LOOP;
END $$;
