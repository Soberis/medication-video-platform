CREATE TABLE dataset_export_manual_replay (
    replay_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    dataset_export_id UUID NOT NULL,
    dead_letter_id UUID NOT NULL,
    source_message_id UUID NOT NULL,
    source_attempt INTEGER NOT NULL,
    new_attempt INTEGER NOT NULL,
    dead_letter_reason VARCHAR(64) NOT NULL,
    body_sha256 VARCHAR(64) NOT NULL,
    operator_actor_id VARCHAR(128) NOT NULL,
    operator_reason_code VARCHAR(64) NOT NULL,
    change_reference VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_dataset_export_manual_replay_export
        FOREIGN KEY (dataset_export_id) REFERENCES dataset_export (dataset_export_id) ON DELETE RESTRICT,
    CONSTRAINT uk_dataset_export_manual_replay_dead_letter UNIQUE (dead_letter_id),
    CONSTRAINT uk_dataset_export_manual_replay_idempotency UNIQUE (project_id, idempotency_key),
    CONSTRAINT uk_dataset_export_manual_replay_attempt UNIQUE (dataset_export_id, new_attempt),
    CONSTRAINT ck_dataset_export_manual_replay_attempts
        CHECK (source_attempt > 0 AND new_attempt = source_attempt + 1),
    CONSTRAINT ck_dataset_export_manual_replay_reason
        CHECK (dead_letter_reason IN (
            'DATASET_EXPORT_JOB_PROFILE_UNSUPPORTED',
            'DATASET_EXPORT_JOB_ACQUIRE_REJECTED',
            'DATASET_EXPORT_JOB_LEASE_REJECTED',
            'DATASET_EXPORT_JOB_RESULT_REJECTED')),
    CONSTRAINT ck_dataset_export_manual_replay_operator_reason
        CHECK (operator_reason_code IN (
            'CONFIGURATION_FIXED',
            'WORKER_DEPLOYMENT_FIXED',
            'AUTHORIZATION_FIXED',
            'OTHER_APPROVED')),
    CONSTRAINT ck_dataset_export_manual_replay_body_sha256
        CHECK (body_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_dataset_export_manual_replay_actor
        CHECK (operator_actor_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_dataset_export_manual_replay_change_reference
        CHECK (change_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/#-]{2,127}$'),
    CONSTRAINT ck_dataset_export_manual_replay_idempotency
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_dataset_export_manual_replay_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$')
);

CREATE INDEX ix_dataset_export_manual_replay_export
    ON dataset_export_manual_replay (dataset_export_id, created_at DESC, replay_id DESC);
