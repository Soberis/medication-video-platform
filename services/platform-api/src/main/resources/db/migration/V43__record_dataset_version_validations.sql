CREATE TABLE dataset_version_validation (
    dataset_version_validation_id UUID PRIMARY KEY,
    dataset_version_id UUID NOT NULL,
    project_id UUID NOT NULL,
    dataset_build_snapshot_id UUID NOT NULL,
    validation_profile_version VARCHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    reason_codes JSONB NOT NULL,
    executed_checks JSONB NOT NULL,
    validated_snapshot_digest VARCHAR(64) NOT NULL,
    validated_by VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    validated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_dataset_version_validation_version
        FOREIGN KEY (project_id, dataset_version_id)
        REFERENCES dataset_version (project_id, dataset_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_dataset_version_validation_snapshot
        FOREIGN KEY (dataset_version_id, dataset_build_snapshot_id)
        REFERENCES dataset_build_snapshot (dataset_version_id, dataset_build_snapshot_id)
        ON DELETE RESTRICT,
    CONSTRAINT uk_dataset_version_validation_version
        UNIQUE (dataset_version_id),
    CONSTRAINT uk_dataset_version_validation_creator_idempotency
        UNIQUE (project_id, validated_by, idempotency_key),
    CONSTRAINT ck_dataset_version_validation_profile
        CHECK (validation_profile_version = 'DATASET_FREEZE_VALIDATION_V1'),
    CONSTRAINT ck_dataset_version_validation_outcome
        CHECK (outcome IN ('PASS', 'FAIL')),
    CONSTRAINT ck_dataset_version_validation_reason_codes
        CHECK (
            jsonb_typeof(reason_codes) = 'array'
            AND (
                (outcome = 'PASS' AND jsonb_array_length(reason_codes) = 0)
                OR
                (outcome = 'FAIL' AND jsonb_array_length(reason_codes) > 0)
            )
        ),
    CONSTRAINT ck_dataset_version_validation_executed_checks
        CHECK (
            jsonb_typeof(executed_checks) = 'array'
            AND jsonb_array_length(executed_checks) = 5
        ),
    CONSTRAINT ck_dataset_version_validation_snapshot_digest
        CHECK (validated_snapshot_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_dataset_version_validation_validated_by
        CHECK (validated_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_dataset_version_validation_idempotency
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_dataset_version_validation_request_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$')
);
