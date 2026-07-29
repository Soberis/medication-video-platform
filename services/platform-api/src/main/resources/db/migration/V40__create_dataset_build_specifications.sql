CREATE TABLE dataset_build_specification (
    dataset_build_specification_id UUID PRIMARY KEY,
    dataset_version_id UUID NOT NULL,
    project_id UUID NOT NULL,
    selection_rule_version VARCHAR(64) NOT NULL,
    label_schema_digest VARCHAR(64) NOT NULL,
    cleaning_rule_set_digest VARCHAR(64) NOT NULL,
    dataset_split_plan_id UUID NOT NULL,
    export_profile_key VARCHAR(64) NOT NULL,
    export_profile_digest VARCHAR(64) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_dataset_build_specification_version
        FOREIGN KEY (project_id, dataset_version_id)
        REFERENCES dataset_version (project_id, dataset_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_dataset_build_specification_split
        FOREIGN KEY (dataset_split_plan_id, dataset_version_id)
        REFERENCES dataset_split_plan (dataset_split_plan_id, dataset_version_id) ON DELETE RESTRICT,
    CONSTRAINT uk_dataset_build_specification_version
        UNIQUE (dataset_version_id),
    CONSTRAINT uk_dataset_build_specification_identity_version
        UNIQUE (dataset_build_specification_id, dataset_version_id),
    CONSTRAINT uk_dataset_build_specification_creator_idempotency
        UNIQUE (project_id, created_by, idempotency_key),
    CONSTRAINT ck_dataset_build_specification_selection_rule
        CHECK (selection_rule_version = 'EXPLICIT_CAPTURE_SESSION_SNAPSHOT_V1'),
    CONSTRAINT ck_dataset_build_specification_label_schema_digest
        CHECK (label_schema_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_dataset_build_specification_cleaning_rule_digest
        CHECK (cleaning_rule_set_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_dataset_build_specification_export_profile_key
        CHECK (export_profile_key = 'p0-dataset-export-v1'),
    CONSTRAINT ck_dataset_build_specification_export_profile_digest
        CHECK (export_profile_digest = '3fac783efda31ad2b68ec4bc18ca7b46c7f6636aeae7bb61c070f4ac1d6da888'),
    CONSTRAINT ck_dataset_build_specification_created_by
        CHECK (created_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_dataset_build_specification_idempotency
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_dataset_build_specification_request_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$')
);
