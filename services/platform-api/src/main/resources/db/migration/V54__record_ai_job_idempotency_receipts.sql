CREATE TABLE ai_job_idempotency_receipt (
    ai_job_idempotency_receipt_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    ai_job_id UUID NOT NULL REFERENCES ai_job (ai_job_id) ON DELETE RESTRICT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_ai_job_idempotency_receipt UNIQUE (project_id, idempotency_key),
    CONSTRAINT ck_ai_job_idempotency_receipt_key CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_ai_job_idempotency_receipt_fingerprint CHECK (request_fingerprint ~ '^[a-f0-9]{64}$')
);

CREATE INDEX ix_ai_job_idempotency_receipt_job ON ai_job_idempotency_receipt (ai_job_id);
