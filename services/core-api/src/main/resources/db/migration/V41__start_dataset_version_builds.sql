ALTER TABLE dataset_version
    DROP CONSTRAINT ck_dataset_version_status;

ALTER TABLE dataset_version
    ADD COLUMN build_started_by VARCHAR(128),
    ADD COLUMN build_start_idempotency_key VARCHAR(128),
    ADD COLUMN build_start_request_fingerprint VARCHAR(64),
    ADD COLUMN build_started_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT ck_dataset_version_status
        CHECK (dataset_version_status IN ('DRAFT', 'BUILDING')),
    ADD CONSTRAINT ck_dataset_version_build_started_by
        CHECK (build_started_by IS NULL
            OR build_started_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    ADD CONSTRAINT ck_dataset_version_build_start_idempotency
        CHECK (build_start_idempotency_key IS NULL
            OR build_start_idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    ADD CONSTRAINT ck_dataset_version_build_start_fingerprint
        CHECK (build_start_request_fingerprint IS NULL
            OR build_start_request_fingerprint ~ '^[a-f0-9]{64}$'),
    ADD CONSTRAINT ck_dataset_version_build_start_state
        CHECK (
            (dataset_version_status = 'DRAFT'
                AND build_started_by IS NULL
                AND build_start_idempotency_key IS NULL
                AND build_start_request_fingerprint IS NULL
                AND build_started_at IS NULL)
            OR
            (dataset_version_status = 'BUILDING'
                AND build_started_by IS NOT NULL
                AND build_start_idempotency_key IS NOT NULL
                AND build_start_request_fingerprint IS NOT NULL
                AND build_started_at IS NOT NULL)
        );

CREATE UNIQUE INDEX uk_dataset_version_build_start_idempotency
    ON dataset_version (project_id, build_started_by, build_start_idempotency_key)
    WHERE build_start_idempotency_key IS NOT NULL;
