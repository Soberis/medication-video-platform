CREATE TABLE inbox_message (
    message_id UUID PRIMARY KEY,
    consumer_name VARCHAR(64) NOT NULL,
    message_type VARCHAR(128) NOT NULL,
    aggregate_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    result_fingerprint VARCHAR(64) NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_inbox_message_consumer
        CHECK (consumer_name = 'media-probe-result-v1'),
    CONSTRAINT ck_inbox_message_type
        CHECK (message_type = 'MediaProbeResultV1'),
    CONSTRAINT ck_inbox_message_attempt CHECK (attempt > 0),
    CONSTRAINT ck_inbox_message_fingerprint
        CHECK (result_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_inbox_message_status
        CHECK (result_status IN ('SUCCEEDED', 'FAILED'))
);

CREATE INDEX ix_inbox_message_aggregate_attempt
    ON inbox_message (aggregate_id, attempt, processed_at DESC);

ALTER TABLE outbox_event
    ADD COLUMN payload_redacted_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT ck_outbox_payload_redaction_time CHECK (
        payload_redacted_at IS NULL OR published_at IS NOT NULL
    );

UPDATE outbox_event
SET payload_json = payload_json - 'lease_token',
    payload_redacted_at = published_at
WHERE schema_version = 'media-probe-job.v1'
  AND published_at IS NOT NULL
  AND jsonb_exists(payload_json, 'lease_token');

ALTER TABLE outbox_event
    ADD CONSTRAINT ck_outbox_media_capability_redacted CHECK (
        published_at IS NULL
        OR schema_version <> 'media-probe-job.v1'
        OR NOT jsonb_exists(payload_json, 'lease_token')
    );
