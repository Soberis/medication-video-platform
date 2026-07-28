CREATE TABLE dataset_version (
    dataset_version_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    dataset_version_status VARCHAR(16) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_dataset_version_project
        FOREIGN KEY (project_id) REFERENCES project (project_id) ON DELETE RESTRICT,
    CONSTRAINT uk_dataset_version_creator_idempotency
        UNIQUE (project_id, created_by, idempotency_key),
    CONSTRAINT uk_dataset_version_project_version
        UNIQUE (project_id, dataset_version_id),
    CONSTRAINT ck_dataset_version_display_name
        CHECK (display_name = btrim(display_name)
            AND char_length(display_name) BETWEEN 1 AND 128
            AND display_name !~ '[[:cntrl:]]'),
    CONSTRAINT ck_dataset_version_status
        CHECK (dataset_version_status = 'DRAFT'),
    CONSTRAINT ck_dataset_version_created_by
        CHECK (created_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_dataset_version_idempotency
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_dataset_version_request_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_dataset_version_row_version
        CHECK (row_version >= 0)
);

CREATE INDEX ix_dataset_version_project_created
    ON dataset_version (project_id, created_at DESC, dataset_version_id DESC);

CREATE TABLE dataset_member_session (
    dataset_member_session_id UUID PRIMARY KEY,
    dataset_version_id UUID NOT NULL,
    project_id UUID NOT NULL,
    capture_session_id UUID NOT NULL,
    candidate_index INTEGER NOT NULL,
    expected_camera_count INTEGER NOT NULL,
    received_camera_count INTEGER NOT NULL,
    assembly_status VARCHAR(32) NOT NULL,
    quality_status VARCHAR(32) NOT NULL,
    CONSTRAINT fk_dataset_member_session_version
        FOREIGN KEY (project_id, dataset_version_id)
        REFERENCES dataset_version (project_id, dataset_version_id) ON DELETE RESTRICT,
    CONSTRAINT uk_dataset_member_session_version_session
        UNIQUE (dataset_version_id, capture_session_id),
    CONSTRAINT uk_dataset_member_session_version_member
        UNIQUE (dataset_version_id, dataset_member_session_id),
    CONSTRAINT uk_dataset_member_session_version_index
        UNIQUE (dataset_version_id, candidate_index),
    CONSTRAINT ck_dataset_member_session_index
        CHECK (candidate_index >= 0),
    CONSTRAINT ck_dataset_member_session_expected_count
        CHECK (expected_camera_count BETWEEN 1 AND 64),
    CONSTRAINT ck_dataset_member_session_received_count
        CHECK (received_camera_count BETWEEN 0 AND expected_camera_count),
    CONSTRAINT ck_dataset_member_session_assembly
        CHECK (assembly_status IN ('OPEN', 'ASSEMBLING', 'COMPLETE', 'INCOMPLETE', 'CLOSED')),
    CONSTRAINT ck_dataset_member_session_quality
        CHECK (quality_status IN ('PENDING', 'PASS', 'WARNING', 'REJECT'))
);

CREATE INDEX ix_dataset_member_session_project_capture
    ON dataset_member_session (project_id, capture_session_id);

CREATE TABLE dataset_member_stream (
    dataset_member_stream_id UUID PRIMARY KEY,
    dataset_member_session_id UUID NOT NULL,
    dataset_version_id UUID NOT NULL,
    project_id UUID NOT NULL,
    camera_stream_id UUID NOT NULL,
    clip_asset_id UUID NOT NULL,
    camera_index INTEGER NOT NULL,
    asset_sha256 VARCHAR(64) NOT NULL,
    quality_review_decision VARCHAR(32) NOT NULL,
    quality_review_version BIGINT NOT NULL,
    annotation_revision_id UUID NOT NULL,
    annotation_document_sha256 VARCHAR(64) NOT NULL,
    label_schema_digest VARCHAR(64) NOT NULL,
    cleaning_result_id UUID NOT NULL,
    cleaning_run_id UUID NOT NULL,
    cleaning_rule_set_digest VARCHAR(64) NOT NULL,
    post_cleaning_outcome VARCHAR(16) NOT NULL,
    CONSTRAINT fk_dataset_member_stream_version_session
        FOREIGN KEY (dataset_version_id, dataset_member_session_id)
        REFERENCES dataset_member_session (dataset_version_id, dataset_member_session_id) ON DELETE RESTRICT,
    CONSTRAINT fk_dataset_member_stream_version
        FOREIGN KEY (project_id, dataset_version_id)
        REFERENCES dataset_version (project_id, dataset_version_id) ON DELETE RESTRICT,
    CONSTRAINT uk_dataset_member_stream_session_stream
        UNIQUE (dataset_member_session_id, camera_stream_id),
    CONSTRAINT uk_dataset_member_stream_version_asset
        UNIQUE (dataset_version_id, clip_asset_id),
    CONSTRAINT ck_dataset_member_stream_camera_index
        CHECK (camera_index BETWEEN 0 AND 63),
    CONSTRAINT ck_dataset_member_stream_asset_digest
        CHECK (asset_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_dataset_member_stream_quality_decision
        CHECK (quality_review_decision = 'QUALIFIED'),
    CONSTRAINT ck_dataset_member_stream_quality_version
        CHECK (quality_review_version >= 0),
    CONSTRAINT ck_dataset_member_stream_document_digest
        CHECK (annotation_document_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_dataset_member_stream_label_schema_digest
        CHECK (label_schema_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_dataset_member_stream_cleaning_rule_digest
        CHECK (cleaning_rule_set_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_dataset_member_stream_post_cleaning_outcome
        CHECK (post_cleaning_outcome = 'PASS')
);

CREATE INDEX ix_dataset_member_stream_project_asset
    ON dataset_member_stream (project_id, clip_asset_id);
