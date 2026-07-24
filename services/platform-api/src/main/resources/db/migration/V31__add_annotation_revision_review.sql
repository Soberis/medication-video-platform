ALTER TABLE annotation_revision
    ADD CONSTRAINT uk_annotation_revision_project_revision
        UNIQUE (project_id, annotation_revision_id);

CREATE TABLE annotation_review (
    annotation_review_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    annotation_revision_id UUID NOT NULL,
    decision VARCHAR(32) NOT NULL,
    review_note VARCHAR(1000),
    reviewed_by VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    reviewed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_annotation_review_revision UNIQUE (annotation_revision_id),
    CONSTRAINT uk_annotation_review_idempotency
        UNIQUE (annotation_revision_id, reviewed_by, idempotency_key),
    CONSTRAINT fk_annotation_review_revision
        FOREIGN KEY (project_id, annotation_revision_id)
        REFERENCES annotation_revision (project_id, annotation_revision_id) ON DELETE RESTRICT,
    CONSTRAINT ck_annotation_review_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_annotation_review_note CHECK (review_note IS NULL OR review_note !~ '[[:cntrl:]]'),
    CONSTRAINT ck_annotation_review_reviewer
        CHECK (reviewed_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_annotation_review_idempotency
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$')
);
