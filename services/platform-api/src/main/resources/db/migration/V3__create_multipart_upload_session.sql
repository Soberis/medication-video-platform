ALTER TABLE upload_file
    ADD COLUMN storage_bucket VARCHAR(255),
    ADD COLUMN object_key VARCHAR(1024),
    ADD COLUMN storage_upload_id VARCHAR(2048),
    ADD COLUMN multipart_created_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE upload_file
    DROP CONSTRAINT ck_upload_file_status,
    ADD CONSTRAINT ck_upload_file_status
        CHECK (status IN ('REGISTERED', 'SESSION_CREATED', 'UPLOADING')),
    ADD CONSTRAINT uk_upload_file_object_key UNIQUE (storage_bucket, object_key),
    ADD CONSTRAINT ck_upload_file_multipart_fields
        CHECK (
            (status = 'REGISTERED'
                AND storage_bucket IS NULL
                AND object_key IS NULL
                AND storage_upload_id IS NULL
                AND multipart_created_at IS NULL)
            OR
            (status IN ('SESSION_CREATED', 'UPLOADING')
                AND length(storage_bucket) > 0
                AND length(object_key) > 0
                AND length(storage_upload_id) > 0
                AND multipart_created_at IS NOT NULL)
        );

CREATE TABLE upload_part (
    upload_part_id UUID PRIMARY KEY,
    upload_file_id UUID NOT NULL,
    part_number INTEGER NOT NULL,
    expected_size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    authorization_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_upload_part_file
        FOREIGN KEY (upload_file_id) REFERENCES upload_file (upload_file_id) ON DELETE RESTRICT,
    CONSTRAINT uk_upload_part_file_number UNIQUE (upload_file_id, part_number),
    CONSTRAINT ck_upload_part_number CHECK (part_number BETWEEN 1 AND 10000),
    CONSTRAINT ck_upload_part_expected_size CHECK (expected_size_bytes > 0),
    CONSTRAINT ck_upload_part_sha256 CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_upload_part_status CHECK (status IN ('AUTHORIZED')),
    CONSTRAINT ck_upload_part_authorization_expiry
        CHECK (authorization_expires_at > updated_at)
);

CREATE INDEX ix_upload_part_file_status
    ON upload_part (upload_file_id, status, part_number);
