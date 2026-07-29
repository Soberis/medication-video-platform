ALTER TABLE dataset_member_session
    ADD CONSTRAINT uk_dataset_member_session_split_identity
    UNIQUE (dataset_version_id, dataset_member_session_id, capture_session_id, candidate_index);

CREATE TABLE dataset_split_plan (
    dataset_split_plan_id UUID PRIMARY KEY,
    dataset_version_id UUID NOT NULL,
    project_id UUID NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    split_seed INTEGER NOT NULL,
    holdout_count INTEGER NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_dataset_split_plan_version
        FOREIGN KEY (project_id, dataset_version_id)
        REFERENCES dataset_version (project_id, dataset_version_id) ON DELETE RESTRICT,
    CONSTRAINT uk_dataset_split_plan_version
        UNIQUE (dataset_version_id),
    CONSTRAINT uk_dataset_split_plan_identity_version
        UNIQUE (dataset_split_plan_id, dataset_version_id),
    CONSTRAINT uk_dataset_split_plan_creator_idempotency
        UNIQUE (project_id, created_by, idempotency_key),
    CONSTRAINT ck_dataset_split_plan_policy
        CHECK (policy_version = 'CAPTURE_SESSION_HASH_RANK_V1'),
    CONSTRAINT ck_dataset_split_plan_seed
        CHECK (split_seed > 0),
    CONSTRAINT ck_dataset_split_plan_holdout_count
        CHECK (holdout_count BETWEEN 1 AND 99),
    CONSTRAINT ck_dataset_split_plan_created_by
        CHECK (created_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_dataset_split_plan_idempotency
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_dataset_split_plan_request_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$')
);

CREATE TABLE dataset_split_assignment (
    dataset_split_assignment_id UUID PRIMARY KEY,
    dataset_split_plan_id UUID NOT NULL,
    dataset_version_id UUID NOT NULL,
    dataset_member_session_id UUID NOT NULL,
    project_id UUID NOT NULL,
    capture_session_id UUID NOT NULL,
    candidate_index INTEGER NOT NULL,
    leakage_group_type VARCHAR(32) NOT NULL,
    leakage_group_id UUID NOT NULL,
    split VARCHAR(16) NOT NULL,
    assignment_rank INTEGER NOT NULL,
    CONSTRAINT fk_dataset_split_assignment_plan
        FOREIGN KEY (dataset_split_plan_id, dataset_version_id)
        REFERENCES dataset_split_plan (dataset_split_plan_id, dataset_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_dataset_split_assignment_member
        FOREIGN KEY (dataset_version_id, dataset_member_session_id, capture_session_id, candidate_index)
        REFERENCES dataset_member_session (
            dataset_version_id,
            dataset_member_session_id,
            capture_session_id,
            candidate_index
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_dataset_split_assignment_version
        FOREIGN KEY (project_id, dataset_version_id)
        REFERENCES dataset_version (project_id, dataset_version_id) ON DELETE RESTRICT,
    CONSTRAINT uk_dataset_split_assignment_plan_member
        UNIQUE (dataset_split_plan_id, dataset_member_session_id),
    CONSTRAINT uk_dataset_split_assignment_plan_rank
        UNIQUE (dataset_split_plan_id, assignment_rank),
    CONSTRAINT uk_dataset_split_assignment_version_session
        UNIQUE (dataset_version_id, capture_session_id),
    CONSTRAINT ck_dataset_split_assignment_candidate_index
        CHECK (candidate_index BETWEEN 0 AND 99),
    CONSTRAINT ck_dataset_split_assignment_leakage_group_type
        CHECK (leakage_group_type = 'CAPTURE_SESSION'),
    CONSTRAINT ck_dataset_split_assignment_leakage_group_identity
        CHECK (leakage_group_id = capture_session_id),
    CONSTRAINT ck_dataset_split_assignment_split
        CHECK (split IN ('PILOT', 'HOLDOUT')),
    CONSTRAINT ck_dataset_split_assignment_rank
        CHECK (assignment_rank BETWEEN 0 AND 99)
);

CREATE INDEX ix_dataset_split_assignment_plan_candidate
    ON dataset_split_assignment (dataset_split_plan_id, candidate_index);
