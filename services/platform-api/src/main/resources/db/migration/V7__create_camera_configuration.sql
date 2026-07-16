CREATE TABLE camera_channel (
    camera_channel_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    provisioning_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_camera_channel_project_key UNIQUE (project_id, provisioning_key),
    CONSTRAINT ck_camera_channel_provisioning_key
        CHECK (provisioning_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')
);

CREATE INDEX ix_camera_channel_project_created
    ON camera_channel (project_id, created_at, camera_channel_id);

CREATE TABLE camera_set (
    camera_set_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    camera_set_key VARCHAR(128) NOT NULL,
    configuration_version INTEGER NOT NULL,
    layout_version VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_camera_set_project_key_version
        UNIQUE (project_id, camera_set_key, configuration_version),
    CONSTRAINT ck_camera_set_key
        CHECK (camera_set_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_camera_set_configuration_version CHECK (configuration_version > 0),
    CONSTRAINT ck_camera_set_layout_version
        CHECK (layout_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_camera_set_request_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$')
);

CREATE INDEX ix_camera_set_project_key_version
    ON camera_set (project_id, camera_set_key, configuration_version DESC);

CREATE TABLE camera_set_member (
    camera_set_member_id UUID PRIMARY KEY,
    camera_set_id UUID NOT NULL,
    camera_channel_id UUID NOT NULL,
    camera_index INTEGER NOT NULL,
    camera_role VARCHAR(64) NOT NULL,
    mount_position VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_camera_set_member_set
        FOREIGN KEY (camera_set_id) REFERENCES camera_set (camera_set_id) ON DELETE RESTRICT,
    CONSTRAINT fk_camera_set_member_channel
        FOREIGN KEY (camera_channel_id) REFERENCES camera_channel (camera_channel_id) ON DELETE RESTRICT,
    CONSTRAINT uk_camera_set_member_channel UNIQUE (camera_set_id, camera_channel_id),
    CONSTRAINT uk_camera_set_member_index UNIQUE (camera_set_id, camera_index),
    CONSTRAINT ck_camera_set_member_index CHECK (camera_index BETWEEN 0 AND 63),
    CONSTRAINT ck_camera_set_member_role
        CHECK (camera_role ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_camera_set_member_mount_position
        CHECK (mount_position ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')
);

CREATE INDEX ix_camera_set_member_set_index
    ON camera_set_member (camera_set_id, camera_index);
