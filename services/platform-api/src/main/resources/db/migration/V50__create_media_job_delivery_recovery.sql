CREATE TABLE media_job_delivery_recovery (
    recovery_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    media_job_id UUID NOT NULL,
    source_message_id UUID NOT NULL,
    source_attempt INTEGER NOT NULL,
    new_attempt INTEGER NOT NULL,
    operator_actor_id VARCHAR(128) NOT NULL,
    recovery_reason VARCHAR(64) NOT NULL,
    change_reference VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_media_job_delivery_recovery_job
        FOREIGN KEY (media_job_id) REFERENCES media_job (media_job_id) ON DELETE RESTRICT,
    CONSTRAINT uk_media_job_delivery_recovery_source UNIQUE (source_message_id),
    CONSTRAINT uk_media_job_delivery_recovery_idempotency UNIQUE (project_id, idempotency_key),
    CONSTRAINT uk_media_job_delivery_recovery_attempt UNIQUE (media_job_id, new_attempt),
    CONSTRAINT ck_media_job_delivery_recovery_attempts
        CHECK (source_attempt > 0 AND new_attempt = source_attempt + 1),
    CONSTRAINT ck_media_job_delivery_recovery_reason
        CHECK (recovery_reason = 'BROKER_MESSAGE_LOST'),
    CONSTRAINT ck_media_job_delivery_recovery_actor
        CHECK (operator_actor_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_media_job_delivery_recovery_change_reference
        CHECK (change_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/#-]{2,127}$'),
    CONSTRAINT ck_media_job_delivery_recovery_idempotency
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_media_job_delivery_recovery_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$')
);

CREATE INDEX ix_media_job_delivery_recovery_job
    ON media_job_delivery_recovery (media_job_id, created_at DESC, recovery_id DESC);
