CREATE TABLE annotation_task (
    annotation_task_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    clip_asset_id UUID NOT NULL,
    assignee_actor_id VARCHAR(128) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    task_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_annotation_task_clip_asset UNIQUE (project_id, clip_asset_id),
    CONSTRAINT uk_annotation_task_idempotency UNIQUE (project_id, created_by, idempotency_key),
    CONSTRAINT fk_annotation_task_clip_asset
        FOREIGN KEY (project_id, clip_asset_id)
        REFERENCES clip_asset (project_id, clip_asset_id) ON DELETE RESTRICT,
    CONSTRAINT ck_annotation_task_assignee
        CHECK (assignee_actor_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_annotation_task_creator
        CHECK (created_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_annotation_task_status CHECK (task_status = 'ASSIGNED'),
    CONSTRAINT ck_annotation_task_version CHECK (version >= 0)
);

CREATE INDEX ix_annotation_task_project_assignee_created
    ON annotation_task (project_id, assignee_actor_id, created_at DESC, annotation_task_id DESC);
