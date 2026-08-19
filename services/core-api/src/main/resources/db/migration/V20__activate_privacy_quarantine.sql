CREATE TABLE quarantine_record (
    quarantine_record_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    clip_asset_id UUID NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_id UUID NOT NULL,
    source_version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    activated_by VARCHAR(128) NOT NULL,
    activated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_quarantine_record_clip_asset
        FOREIGN KEY (clip_asset_id) REFERENCES clip_asset (clip_asset_id) ON DELETE RESTRICT,
    CONSTRAINT uk_quarantine_record_source
        UNIQUE (project_id, source_type, source_id),
    CONSTRAINT ck_quarantine_record_input_digest
        CHECK (input_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_quarantine_record_reason_code
        CHECK (reason_code IN (
            'PRIVACY_RISK',
            'BAD_FILE',
            'NON_TARGET_SCENE',
            'DUPLICATE')),
    CONSTRAINT ck_quarantine_record_source_type
        CHECK (source_type IN ('PRIVACY_ASSESSMENT')),
    CONSTRAINT ck_quarantine_record_source_version
        CHECK (source_version > 0),
    CONSTRAINT ck_quarantine_record_status
        CHECK (status IN ('ACTIVE')),
    CONSTRAINT ck_quarantine_record_actor
        CHECK (activated_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_quarantine_record_row_version
        CHECK (row_version >= 0)
);

CREATE INDEX ix_quarantine_record_project_asset_active
    ON quarantine_record (
        project_id, clip_asset_id, input_digest, status, activated_at DESC);

INSERT INTO quarantine_record (
    quarantine_record_id,
    project_id,
    clip_asset_id,
    input_digest,
    reason_code,
    source_type,
    source_id,
    source_version,
    status,
    activated_by,
    activated_at)
SELECT gen_random_uuid(),
       assessment.project_id,
       assessment.clip_asset_id,
       assessment.input_digest,
       'PRIVACY_RISK',
       'PRIVACY_ASSESSMENT',
       assessment.privacy_assessment_id,
       assessment.assessment_version,
       'ACTIVE',
       'flyway-v20',
       CURRENT_TIMESTAMP
FROM privacy_assessment assessment
WHERE assessment.required_action = 'QUARANTINE_REQUIRED';

INSERT INTO audit_event (
    event_id,
    occurred_at_utc,
    actor_type,
    actor_id,
    project_id,
    action,
    object_type,
    object_id,
    object_version,
    outcome,
    reason_code,
    request_id,
    authorization_reference,
    details_json)
SELECT gen_random_uuid(),
       record.activated_at,
       'SYSTEM',
       record.activated_by,
       record.project_id,
       'QUARANTINE_ACTIVATED',
       'QuarantineRecord',
       record.quarantine_record_id::text,
       '1',
       'SUCCEEDED',
       record.reason_code,
       'v20-quarantine-' || record.source_id::text,
       'MIGRATION',
       jsonb_build_object(
           'clip_asset_id', record.clip_asset_id::text,
           'input_digest', record.input_digest,
           'source_type', record.source_type,
           'source_id', record.source_id::text,
           'source_version', record.source_version::text,
           'status', record.status)
FROM quarantine_record record;
