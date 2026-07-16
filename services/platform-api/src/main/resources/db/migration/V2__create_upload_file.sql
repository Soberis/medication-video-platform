CREATE TABLE upload_file (
    upload_file_id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    client_file_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    file_kind VARCHAR(32) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    part_size_bytes BIGINT NOT NULL,
    total_parts INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_upload_file_batch
        FOREIGN KEY (batch_id) REFERENCES upload_batch (batch_id) ON DELETE RESTRICT,
    CONSTRAINT uk_upload_file_batch_client UNIQUE (batch_id, client_file_id),
    CONSTRAINT uk_upload_file_batch_idempotency UNIQUE (batch_id, idempotency_key),
    CONSTRAINT ck_upload_file_idempotency_key
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_upload_file_request_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_upload_file_kind
        CHECK (file_kind IN ('VIDEO', 'METADATA', 'EVENT_LOG')),
    CONSTRAINT ck_upload_file_content_type
        CHECK (content_type ~ '^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*$'),
    CONSTRAINT ck_upload_file_size CHECK (size_bytes > 0),
    CONSTRAINT ck_upload_file_sha256 CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_upload_file_part_size
        CHECK (part_size_bytes BETWEEN 8388608 AND 67108864),
    CONSTRAINT ck_upload_file_total_parts
        CHECK (total_parts BETWEEN 1 AND 10000),
    CONSTRAINT ck_upload_file_status CHECK (status IN ('REGISTERED'))
);

CREATE INDEX ix_upload_file_batch_status
    ON upload_file (batch_id, status, created_at);

CREATE INDEX ix_upload_file_sha256
    ON upload_file (sha256);
