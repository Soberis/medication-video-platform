ALTER TABLE dataset_version_validation
    ADD CONSTRAINT uk_dataset_version_validation_identity_version
        UNIQUE (dataset_version_id, dataset_version_validation_id);

CREATE TABLE approval_request (
    approval_request_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    request_type VARCHAR(64) NOT NULL,
    object_type VARCHAR(64) NOT NULL,
    object_id UUID NOT NULL,
    object_version UUID NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL,
    CONSTRAINT fk_approval_request_project
        FOREIGN KEY (project_id)
        REFERENCES project (project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_approval_request_dataset_version
        FOREIGN KEY (project_id, object_id)
        REFERENCES dataset_version (project_id, dataset_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_approval_request_dataset_validation
        FOREIGN KEY (object_id, object_version)
        REFERENCES dataset_version_validation (
            dataset_version_id,
            dataset_version_validation_id
        ) ON DELETE RESTRICT,
    CONSTRAINT uk_approval_request_type_object
        UNIQUE (request_type, object_id),
    CONSTRAINT uk_approval_request_project_identity
        UNIQUE (project_id, approval_request_id),
    CONSTRAINT uk_approval_request_project_object_identity
        UNIQUE (project_id, object_id, approval_request_id),
    CONSTRAINT ck_approval_request_type
        CHECK (request_type = 'DATASET_VERSION_FREEZE'),
    CONSTRAINT ck_approval_request_object_type
        CHECK (object_type = 'DatasetVersion'),
    CONSTRAINT ck_approval_request_requested_by
        CHECK (requested_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_approval_request_status
        CHECK (status IN ('PENDING', 'APPROVED'))
);

CREATE TABLE approval_decision (
    approval_decision_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    approval_request_id UUID NOT NULL,
    decision VARCHAR(16) NOT NULL,
    decided_by VARCHAR(128) NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_approval_decision_request
        FOREIGN KEY (project_id, approval_request_id)
        REFERENCES approval_request (project_id, approval_request_id) ON DELETE RESTRICT,
    CONSTRAINT uk_approval_decision_request
        UNIQUE (approval_request_id),
    CONSTRAINT uk_approval_decision_project_identity
        UNIQUE (project_id, approval_decision_id),
    CONSTRAINT uk_approval_decision_request_identity
        UNIQUE (project_id, approval_request_id, approval_decision_id),
    CONSTRAINT ck_approval_decision_value
        CHECK (decision = 'APPROVED'),
    CONSTRAINT ck_approval_decision_decided_by
        CHECK (decided_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$')
);

INSERT INTO approval_request (
    approval_request_id,
    project_id,
    request_type,
    object_type,
    object_id,
    object_version,
    requested_by,
    requested_at,
    status
)
SELECT
    gen_random_uuid(),
    validation.project_id,
    'DATASET_VERSION_FREEZE',
    'DatasetVersion',
    validation.dataset_version_id,
    validation.dataset_version_validation_id,
    validation.validated_by,
    validation.validated_at,
    'PENDING'
FROM dataset_version_validation validation
WHERE validation.outcome = 'PASS';

ALTER TABLE dataset_version
    DROP CONSTRAINT ck_dataset_version_status,
    DROP CONSTRAINT ck_dataset_version_build_state,
    ADD COLUMN freeze_approval_request_id UUID,
    ADD COLUMN freeze_approval_decision_id UUID,
    ADD COLUMN frozen_by VARCHAR(128),
    ADD COLUMN freeze_idempotency_key VARCHAR(128),
    ADD COLUMN freeze_request_fingerprint VARCHAR(64),
    ADD COLUMN frozen_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT fk_dataset_version_freeze_request
        FOREIGN KEY (
            project_id,
            dataset_version_id,
            freeze_approval_request_id
        )
        REFERENCES approval_request (
            project_id,
            object_id,
            approval_request_id
        ) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_dataset_version_freeze_decision
        FOREIGN KEY (
            project_id,
            freeze_approval_request_id,
            freeze_approval_decision_id
        )
        REFERENCES approval_decision (
            project_id,
            approval_request_id,
            approval_decision_id
        ) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_dataset_version_status
        CHECK (dataset_version_status IN ('DRAFT', 'BUILDING', 'VALIDATING', 'FROZEN')),
    ADD CONSTRAINT ck_dataset_version_frozen_by
        CHECK (frozen_by IS NULL
            OR frozen_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    ADD CONSTRAINT ck_dataset_version_freeze_idempotency
        CHECK (freeze_idempotency_key IS NULL
            OR freeze_idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    ADD CONSTRAINT ck_dataset_version_freeze_fingerprint
        CHECK (freeze_request_fingerprint IS NULL
            OR freeze_request_fingerprint ~ '^[a-f0-9]{64}$'),
    ADD CONSTRAINT ck_dataset_version_build_state
        CHECK (
            (dataset_version_status = 'DRAFT'
                AND build_started_by IS NULL
                AND build_start_idempotency_key IS NULL
                AND build_start_request_fingerprint IS NULL
                AND build_started_at IS NULL
                AND build_snapshot_id IS NULL
                AND freeze_approval_request_id IS NULL
                AND freeze_approval_decision_id IS NULL
                AND frozen_by IS NULL
                AND freeze_idempotency_key IS NULL
                AND freeze_request_fingerprint IS NULL
                AND frozen_at IS NULL)
            OR
            (dataset_version_status = 'BUILDING'
                AND build_started_by IS NOT NULL
                AND build_start_idempotency_key IS NOT NULL
                AND build_start_request_fingerprint IS NOT NULL
                AND build_started_at IS NOT NULL
                AND build_snapshot_id IS NULL
                AND freeze_approval_request_id IS NULL
                AND freeze_approval_decision_id IS NULL
                AND frozen_by IS NULL
                AND freeze_idempotency_key IS NULL
                AND freeze_request_fingerprint IS NULL
                AND frozen_at IS NULL)
            OR
            (dataset_version_status = 'VALIDATING'
                AND build_started_by IS NOT NULL
                AND build_start_idempotency_key IS NOT NULL
                AND build_start_request_fingerprint IS NOT NULL
                AND build_started_at IS NOT NULL
                AND build_snapshot_id IS NOT NULL
                AND freeze_approval_request_id IS NULL
                AND freeze_approval_decision_id IS NULL
                AND frozen_by IS NULL
                AND freeze_idempotency_key IS NULL
                AND freeze_request_fingerprint IS NULL
                AND frozen_at IS NULL)
            OR
            (dataset_version_status = 'FROZEN'
                AND build_started_by IS NOT NULL
                AND build_start_idempotency_key IS NOT NULL
                AND build_start_request_fingerprint IS NOT NULL
                AND build_started_at IS NOT NULL
                AND build_snapshot_id IS NOT NULL
                AND freeze_approval_request_id IS NOT NULL
                AND freeze_approval_decision_id IS NOT NULL
                AND frozen_by IS NOT NULL
                AND freeze_idempotency_key IS NOT NULL
                AND freeze_request_fingerprint IS NOT NULL
                AND frozen_at IS NOT NULL)
        );

CREATE UNIQUE INDEX uk_dataset_version_freeze_idempotency
    ON dataset_version (project_id, frozen_by, freeze_idempotency_key)
    WHERE freeze_idempotency_key IS NOT NULL;
