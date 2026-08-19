CREATE TABLE asset_folder (
    folder_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_asset_folder_project
        FOREIGN KEY (project_id) REFERENCES project (project_id) ON DELETE RESTRICT,
    CONSTRAINT uk_asset_folder_project_identity
        UNIQUE (project_id, folder_id),
    CONSTRAINT ck_asset_folder_display_name
        CHECK (display_name = btrim(display_name)
            AND char_length(display_name) BETWEEN 1 AND 128
            AND display_name !~ '[[:cntrl:]]'),
    CONSTRAINT ck_asset_folder_created_by
        CHECK (created_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_asset_folder_updated_by
        CHECK (updated_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_asset_folder_row_version
        CHECK (row_version >= 0)
);

CREATE UNIQUE INDEX uk_asset_folder_project_name_ci
    ON asset_folder (project_id, lower(display_name));

CREATE INDEX ix_asset_folder_project_name
    ON asset_folder (project_id, display_name, folder_id);

ALTER TABLE clip_asset
    ADD COLUMN folder_id UUID,
    ADD CONSTRAINT fk_clip_asset_project_folder
        FOREIGN KEY (project_id, folder_id)
        REFERENCES asset_folder (project_id, folder_id) ON DELETE RESTRICT;

CREATE INDEX ix_clip_asset_project_folder_created
    ON clip_asset (project_id, folder_id, created_at DESC, clip_asset_id DESC);

CREATE TABLE asset_classification_command (
    command_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    performed_by VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    result_folder_id UUID,
    result_display_name VARCHAR(128),
    result_clip_asset_id UUID,
    result_resource_version BIGINT NOT NULL,
    result_created_at TIMESTAMP WITH TIME ZONE,
    result_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_asset_classification_command_project
        FOREIGN KEY (project_id) REFERENCES project (project_id) ON DELETE RESTRICT,
    CONSTRAINT uk_asset_classification_command_idempotency
        UNIQUE (project_id, performed_by, idempotency_key),
    CONSTRAINT ck_asset_classification_command_actor
        CHECK (performed_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_asset_classification_command_idempotency_key
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_asset_classification_command_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_asset_classification_command_type
        CHECK (command_type IN (
            'CREATE_FOLDER', 'RENAME_FOLDER', 'DELETE_FOLDER',
            'ASSIGN_CLIP_ASSET', 'UNCLASSIFY_CLIP_ASSET')),
    CONSTRAINT ck_asset_classification_command_version
        CHECK (result_resource_version >= 0),
    CONSTRAINT ck_asset_classification_command_result
        CHECK (
            (command_type IN ('CREATE_FOLDER', 'RENAME_FOLDER', 'DELETE_FOLDER')
                AND result_folder_id IS NOT NULL
                AND result_display_name IS NOT NULL
                AND result_clip_asset_id IS NULL
                AND result_created_at IS NOT NULL)
            OR
            (command_type = 'ASSIGN_CLIP_ASSET'
                AND result_folder_id IS NOT NULL
                AND result_display_name IS NULL
                AND result_clip_asset_id IS NOT NULL
                AND result_created_at IS NULL)
            OR
            (command_type = 'UNCLASSIFY_CLIP_ASSET'
                AND result_folder_id IS NULL
                AND result_display_name IS NULL
                AND result_clip_asset_id IS NOT NULL
                AND result_created_at IS NULL)
        )
);

CREATE INDEX ix_asset_classification_command_resource
    ON asset_classification_command (
        project_id, result_folder_id, result_clip_asset_id, completed_at DESC);
