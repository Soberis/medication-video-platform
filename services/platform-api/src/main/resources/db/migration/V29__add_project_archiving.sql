ALTER TABLE project
    DROP CONSTRAINT ck_project_status;

ALTER TABLE project
    ADD CONSTRAINT ck_project_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED'));
