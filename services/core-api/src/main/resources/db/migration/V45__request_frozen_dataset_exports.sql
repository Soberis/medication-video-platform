ALTER TABLE approval_request
    DROP CONSTRAINT ck_approval_request_type,
    ADD CONSTRAINT ck_approval_request_type
        CHECK (request_type IN ('DATASET_VERSION_FREEZE', 'DATASET_EXPORT'));

CREATE TABLE dataset_export (
    dataset_export_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    dataset_version_id UUID NOT NULL,
    dataset_build_specification_id UUID NOT NULL,
    dataset_build_snapshot_id UUID NOT NULL,
    dataset_version_validation_id UUID NOT NULL,
    approval_request_id UUID NOT NULL,
    export_profile_key VARCHAR(64) NOT NULL,
    export_profile_digest VARCHAR(64) NOT NULL,
    snapshot_digest VARCHAR(64) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_dataset_export_version
        FOREIGN KEY (project_id, dataset_version_id)
        REFERENCES dataset_version (project_id, dataset_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_dataset_export_specification
        FOREIGN KEY (dataset_build_specification_id, dataset_version_id)
        REFERENCES dataset_build_specification (
            dataset_build_specification_id,
            dataset_version_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_dataset_export_snapshot
        FOREIGN KEY (dataset_version_id, dataset_build_snapshot_id)
        REFERENCES dataset_build_snapshot (
            dataset_version_id,
            dataset_build_snapshot_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_dataset_export_validation
        FOREIGN KEY (dataset_version_id, dataset_version_validation_id)
        REFERENCES dataset_version_validation (
            dataset_version_id,
            dataset_version_validation_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_dataset_export_approval_request
        FOREIGN KEY (project_id, dataset_version_id, approval_request_id)
        REFERENCES approval_request (
            project_id,
            object_id,
            approval_request_id
        ) ON DELETE RESTRICT,
    CONSTRAINT uk_dataset_export_version_profile
        UNIQUE (dataset_version_id, export_profile_digest),
    CONSTRAINT uk_dataset_export_requester_idempotency
        UNIQUE (project_id, requested_by, idempotency_key),
    CONSTRAINT ck_dataset_export_profile_key
        CHECK (export_profile_key = 'p0-dataset-export-v1'),
    CONSTRAINT ck_dataset_export_profile_digest
        CHECK (export_profile_digest = '3fac783efda31ad2b68ec4bc18ca7b46c7f6636aeae7bb61c070f4ac1d6da888'),
    CONSTRAINT ck_dataset_export_snapshot_digest
        CHECK (snapshot_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_dataset_export_purpose
        CHECK (purpose = 'MODEL_TRAINING'),
    CONSTRAINT ck_dataset_export_status
        CHECK (status = 'REQUESTED'),
    CONSTRAINT ck_dataset_export_requested_by
        CHECK (requested_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_dataset_export_idempotency
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_dataset_export_request_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_dataset_export_expiry
        CHECK (expires_at > requested_at),
    CONSTRAINT ck_dataset_export_row_version
        CHECK (row_version >= 0)
);
