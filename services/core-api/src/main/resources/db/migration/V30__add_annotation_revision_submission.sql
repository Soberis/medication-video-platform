ALTER TABLE annotation_task
    DROP CONSTRAINT ck_annotation_task_status;

ALTER TABLE annotation_task
    ADD CONSTRAINT ck_annotation_task_status
        CHECK (task_status IN ('ASSIGNED', 'SUBMITTED'));

ALTER TABLE annotation_task
    ADD CONSTRAINT uk_annotation_task_project_task
        UNIQUE (project_id, annotation_task_id);

CREATE TABLE annotation_revision (
    annotation_revision_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    annotation_task_id UUID NOT NULL,
    clip_asset_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    annotation_schema_version VARCHAR(64) NOT NULL,
    annotation_document JSONB NOT NULL,
    document_sha256 VARCHAR(64) NOT NULL,
    submitted_by VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_annotation_revision_task_number UNIQUE (annotation_task_id, revision_number),
    CONSTRAINT uk_annotation_revision_idempotency
        UNIQUE (annotation_task_id, submitted_by, idempotency_key),
    CONSTRAINT fk_annotation_revision_task
        FOREIGN KEY (project_id, annotation_task_id)
        REFERENCES annotation_task (project_id, annotation_task_id) ON DELETE RESTRICT,
    CONSTRAINT fk_annotation_revision_clip_asset
        FOREIGN KEY (project_id, clip_asset_id)
        REFERENCES clip_asset (project_id, clip_asset_id) ON DELETE RESTRICT,
    CONSTRAINT ck_annotation_revision_number CHECK (revision_number = 1),
    CONSTRAINT ck_annotation_revision_schema CHECK (annotation_schema_version = 'annotation-document.v1'),
    CONSTRAINT ck_annotation_revision_digest CHECK (document_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_annotation_revision_submitter
        CHECK (submitted_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_annotation_revision_idempotency
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$')
);

CREATE INDEX ix_annotation_revision_project_task
    ON annotation_revision (project_id, annotation_task_id);
