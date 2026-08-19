ALTER TABLE annotation_revision
    DROP CONSTRAINT ck_annotation_revision_number;

ALTER TABLE annotation_revision
    ADD CONSTRAINT ck_annotation_revision_number
        CHECK (revision_number > 0);
