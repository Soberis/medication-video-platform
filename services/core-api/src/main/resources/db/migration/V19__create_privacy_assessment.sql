CREATE TABLE privacy_assessment (
    privacy_assessment_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    clip_asset_id UUID NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    assessment_version INTEGER NOT NULL,
    assessment_method VARCHAR(32) NOT NULL,
    policy_key VARCHAR(64) NOT NULL,
    policy_digest VARCHAR(64) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    required_action VARCHAR(64) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    assessed_by VARCHAR(128) NOT NULL,
    supersedes_assessment_id UUID,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    assessed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_privacy_assessment_clip_asset
        FOREIGN KEY (clip_asset_id) REFERENCES clip_asset (clip_asset_id) ON DELETE RESTRICT,
    CONSTRAINT fk_privacy_assessment_supersedes
        FOREIGN KEY (supersedes_assessment_id)
        REFERENCES privacy_assessment (privacy_assessment_id) ON DELETE RESTRICT,
    CONSTRAINT uk_privacy_assessment_project_idempotency
        UNIQUE (project_id, idempotency_key),
    CONSTRAINT uk_privacy_assessment_asset_version
        UNIQUE (project_id, clip_asset_id, input_digest, assessment_version),
    CONSTRAINT uk_privacy_assessment_supersedes
        UNIQUE (supersedes_assessment_id),
    CONSTRAINT ck_privacy_assessment_input_digest
        CHECK (input_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_privacy_assessment_version
        CHECK (assessment_version > 0),
    CONSTRAINT ck_privacy_assessment_method
        CHECK (assessment_method IN ('HUMAN_REVIEW')),
    CONSTRAINT ck_privacy_assessment_policy_key
        CHECK (policy_key = 'privacy-assessment-policy-v1'),
    CONSTRAINT ck_privacy_assessment_policy_digest
        CHECK (policy_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_privacy_assessment_risk_level
        CHECK (risk_level IN ('NONE', 'SUSPECTED', 'CONFIRMED')),
    CONSTRAINT ck_privacy_assessment_required_action
        CHECK (required_action IN (
            'RELEASE_REVIEW_REQUIRED',
            'DEIDENTIFICATION_REQUIRED',
            'QUARANTINE_REQUIRED')),
    CONSTRAINT ck_privacy_assessment_reason_code
        CHECK (reason_code IN (
            'NO_IDENTIFIABLE_INFORMATION_OBSERVED',
            'IDENTIFIABLE_INFORMATION_OBSERVED',
            'RISK_REQUIRES_ISOLATION')),
    CONSTRAINT ck_privacy_assessment_actor
        CHECK (assessed_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_privacy_assessment_idempotency_key
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT ck_privacy_assessment_request_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_privacy_assessment_chain
        CHECK ((assessment_version = 1 AND supersedes_assessment_id IS NULL)
            OR (assessment_version > 1 AND supersedes_assessment_id IS NOT NULL)),
    CONSTRAINT ck_privacy_assessment_decision
        CHECK (
            (risk_level = 'NONE'
                AND required_action = 'RELEASE_REVIEW_REQUIRED'
                AND reason_code = 'NO_IDENTIFIABLE_INFORMATION_OBSERVED')
            OR
            (risk_level = 'SUSPECTED'
                AND required_action = 'QUARANTINE_REQUIRED'
                AND reason_code = 'RISK_REQUIRES_ISOLATION')
            OR
            (risk_level = 'CONFIRMED'
                AND required_action = 'DEIDENTIFICATION_REQUIRED'
                AND reason_code = 'IDENTIFIABLE_INFORMATION_OBSERVED')
            OR
            (risk_level = 'CONFIRMED'
                AND required_action = 'QUARANTINE_REQUIRED'
                AND reason_code = 'RISK_REQUIRES_ISOLATION'))
);

CREATE INDEX ix_privacy_assessment_project_asset_current
    ON privacy_assessment (
        project_id, clip_asset_id, input_digest, assessment_version DESC);

CREATE TABLE privacy_finding (
    privacy_assessment_id UUID NOT NULL,
    finding_type VARCHAR(32) NOT NULL,
    PRIMARY KEY (privacy_assessment_id, finding_type),
    CONSTRAINT fk_privacy_finding_assessment
        FOREIGN KEY (privacy_assessment_id)
        REFERENCES privacy_assessment (privacy_assessment_id) ON DELETE RESTRICT,
    CONSTRAINT ck_privacy_finding_type
        CHECK (finding_type IN (
            'FACE',
            'NAME',
            'MEDICAL_RECORD_ID',
            'SCREEN',
            'BADGE',
            'ORDER_TEXT',
            'LABEL_TEXT',
            'VOICE',
            'OTHER'))
);

CREATE INDEX ix_privacy_finding_type
    ON privacy_finding (finding_type, privacy_assessment_id);
