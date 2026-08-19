ALTER TABLE upload_part
    ADD COLUMN storage_etag VARCHAR(2048),
    ADD COLUMN uploaded_size_bytes BIGINT,
    ADD COLUMN confirmed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE upload_part
    DROP CONSTRAINT ck_upload_part_status,
    DROP CONSTRAINT ck_upload_part_authorization_expiry,
    ADD CONSTRAINT ck_upload_part_status
        CHECK (status IN ('AUTHORIZED', 'UPLOADED')),
    ADD CONSTRAINT ck_upload_part_authorization_expiry
        CHECK (
            status = 'UPLOADED'
            OR authorization_expires_at > updated_at
        ),
    ADD CONSTRAINT ck_upload_part_confirmation
        CHECK (
            (status = 'AUTHORIZED'
                AND storage_etag IS NULL
                AND uploaded_size_bytes IS NULL
                AND confirmed_at IS NULL)
            OR
            (status = 'UPLOADED'
                AND length(storage_etag) > 0
                AND uploaded_size_bytes = expected_size_bytes
                AND confirmed_at IS NOT NULL)
        );
