CREATE TABLE dataset_build_snapshot (
    dataset_build_snapshot_id UUID PRIMARY KEY,
    dataset_version_id UUID NOT NULL,
    project_id UUID NOT NULL,
    dataset_build_specification_id UUID NOT NULL,
    dataset_split_plan_id UUID NOT NULL,
    snapshot_schema_version VARCHAR(64) NOT NULL,
    input_variant_policy VARCHAR(64) NOT NULL,
    member_count INTEGER NOT NULL,
    stream_count INTEGER NOT NULL,
    snapshot_digest VARCHAR(64) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_dataset_build_snapshot_version
        FOREIGN KEY (project_id, dataset_version_id)
        REFERENCES dataset_version (project_id, dataset_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_dataset_build_snapshot_specification
        FOREIGN KEY (dataset_build_specification_id, dataset_version_id)
        REFERENCES dataset_build_specification (dataset_build_specification_id, dataset_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_dataset_build_snapshot_split_plan
        FOREIGN KEY (dataset_split_plan_id, dataset_version_id)
        REFERENCES dataset_split_plan (dataset_split_plan_id, dataset_version_id) ON DELETE RESTRICT,
    CONSTRAINT uk_dataset_build_snapshot_version
        UNIQUE (dataset_version_id),
    CONSTRAINT uk_dataset_build_snapshot_identity_version
        UNIQUE (dataset_version_id, dataset_build_snapshot_id),
    CONSTRAINT uk_dataset_build_snapshot_creator_idempotency
        UNIQUE (project_id, created_by, idempotency_key),
    CONSTRAINT ck_dataset_build_snapshot_schema
        CHECK (snapshot_schema_version = 'DATASET_RELATIONAL_SNAPSHOT_V1'),
    CONSTRAINT ck_dataset_build_snapshot_input_policy
        CHECK (input_variant_policy = 'RAW_CLIP_ASSET_V1'),
    CONSTRAINT ck_dataset_build_snapshot_member_count
        CHECK (member_count BETWEEN 1 AND 100),
    CONSTRAINT ck_dataset_build_snapshot_stream_count
        CHECK (stream_count BETWEEN 1 AND 6400),
    CONSTRAINT ck_dataset_build_snapshot_digest
        CHECK (snapshot_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_dataset_build_snapshot_created_by
        CHECK (created_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_dataset_build_snapshot_idempotency
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_dataset_build_snapshot_request_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$')
);

ALTER TABLE dataset_version
    DROP CONSTRAINT ck_dataset_version_status,
    DROP CONSTRAINT ck_dataset_version_build_start_state,
    ADD COLUMN build_snapshot_id UUID,
    ADD CONSTRAINT fk_dataset_version_build_snapshot
        FOREIGN KEY (dataset_version_id, build_snapshot_id)
        REFERENCES dataset_build_snapshot (dataset_version_id, dataset_build_snapshot_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_dataset_version_status
        CHECK (dataset_version_status IN ('DRAFT', 'BUILDING', 'VALIDATING')),
    ADD CONSTRAINT ck_dataset_version_build_state
        CHECK (
            (dataset_version_status = 'DRAFT'
                AND build_started_by IS NULL
                AND build_start_idempotency_key IS NULL
                AND build_start_request_fingerprint IS NULL
                AND build_started_at IS NULL
                AND build_snapshot_id IS NULL)
            OR
            (dataset_version_status = 'BUILDING'
                AND build_started_by IS NOT NULL
                AND build_start_idempotency_key IS NOT NULL
                AND build_start_request_fingerprint IS NOT NULL
                AND build_started_at IS NOT NULL
                AND build_snapshot_id IS NULL)
            OR
            (dataset_version_status = 'VALIDATING'
                AND build_started_by IS NOT NULL
                AND build_start_idempotency_key IS NOT NULL
                AND build_start_request_fingerprint IS NOT NULL
                AND build_started_at IS NOT NULL
                AND build_snapshot_id IS NOT NULL)
        );
