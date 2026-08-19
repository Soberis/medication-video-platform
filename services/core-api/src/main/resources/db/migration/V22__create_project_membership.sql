CREATE TABLE project (
    project_id UUID PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_project_creator_idempotency
        UNIQUE (created_by, idempotency_key),
    CONSTRAINT ck_project_display_name
        CHECK (display_name = btrim(display_name)
            AND char_length(display_name) BETWEEN 1 AND 128
            AND display_name !~ '[[:cntrl:]]'),
    CONSTRAINT ck_project_status
        CHECK (status IN ('ACTIVE')),
    CONSTRAINT ck_project_created_by
        CHECK (created_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_project_idempotency_key
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_project_request_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_project_row_version
        CHECK (row_version >= 0)
);

CREATE INDEX ix_project_status_created
    ON project (status, created_at, project_id);

CREATE TABLE membership (
    membership_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    removed_by VARCHAR(128),
    removed_at TIMESTAMP WITH TIME ZONE,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_membership_project
        FOREIGN KEY (project_id) REFERENCES project (project_id) ON DELETE RESTRICT,
    CONSTRAINT uk_membership_project_actor
        UNIQUE (project_id, actor_id),
    CONSTRAINT ck_membership_actor_id
        CHECK (actor_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_membership_role
        CHECK (role IN ('ADMIN', 'MEMBER')),
    CONSTRAINT ck_membership_status
        CHECK (status IN ('ACTIVE', 'REMOVED')),
    CONSTRAINT ck_membership_created_by
        CHECK (created_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_membership_updated_by
        CHECK (updated_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_membership_removed_by
        CHECK (removed_by IS NULL
            OR removed_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_membership_removal_state
        CHECK ((status = 'ACTIVE' AND removed_by IS NULL AND removed_at IS NULL)
            OR (status = 'REMOVED' AND removed_by IS NOT NULL AND removed_at IS NOT NULL)),
    CONSTRAINT ck_membership_row_version
        CHECK (row_version >= 0)
);

CREATE INDEX ix_membership_actor_status_project
    ON membership (actor_id, status, project_id);

CREATE INDEX ix_membership_project_status_role
    ON membership (project_id, status, role, created_at, membership_id);

CREATE TABLE project_membership_command (
    command_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    performed_by VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    target_actor_id VARCHAR(128) NOT NULL,
    result_membership_id UUID NOT NULL,
    result_role VARCHAR(32) NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    result_version BIGINT NOT NULL,
    result_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_project_membership_command_project
        FOREIGN KEY (project_id) REFERENCES project (project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_project_membership_command_result
        FOREIGN KEY (result_membership_id)
        REFERENCES membership (membership_id) ON DELETE RESTRICT,
    CONSTRAINT uk_project_membership_command_idempotency
        UNIQUE (project_id, performed_by, idempotency_key),
    CONSTRAINT ck_project_membership_command_performed_by
        CHECK (performed_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_project_membership_command_idempotency_key
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_project_membership_command_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_project_membership_command_type
        CHECK (command_type IN ('UPSERT', 'REMOVE')),
    CONSTRAINT ck_project_membership_command_target_actor
        CHECK (target_actor_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_project_membership_command_result_role
        CHECK (result_role IN ('ADMIN', 'MEMBER')),
    CONSTRAINT ck_project_membership_command_result_status
        CHECK (result_status IN ('ACTIVE', 'REMOVED')),
    CONSTRAINT ck_project_membership_command_result_version
        CHECK (result_version >= 0)
);

CREATE INDEX ix_project_membership_command_target
    ON project_membership_command (project_id, target_actor_id, completed_at DESC);
