CREATE TABLE quality_review (
    quality_review_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    clip_asset_id UUID NOT NULL,
    decision VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    reviewed_by VARCHAR(128) NOT NULL,
    reviewed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_quality_review_asset UNIQUE (project_id, clip_asset_id),
    CONSTRAINT fk_quality_review_clip_asset
        FOREIGN KEY (project_id, clip_asset_id)
        REFERENCES clip_asset (project_id, clip_asset_id) ON DELETE RESTRICT,
    CONSTRAINT ck_quality_review_decision
        CHECK (decision IN ('QUALIFIED', 'REJECTED', 'NEEDS_REVIEW')),
    CONSTRAINT ck_quality_review_reason
        CHECK (
            (decision = 'QUALIFIED' AND reason_code = 'MEETS_REQUIREMENTS')
            OR
            (decision = 'REJECTED' AND reason_code IN (
                'SENSITIVE_CONTENT',
                'BLURRY_OR_OUT_OF_FOCUS',
                'BLACK_OR_CORRUPTED_FRAMES',
                'SEVERE_OCCLUSION',
                'INCOMPLETE_ACTION',
                'WRONG_SCENE',
                'DUPLICATE',
                'OTHER_QUALITY_FAILURE'))
            OR
            (decision = 'NEEDS_REVIEW' AND reason_code IN (
                'UNCERTAIN_QUALITY',
                'METADATA_MISMATCH'))
        ),
    CONSTRAINT ck_quality_review_actor
        CHECK (reviewed_by ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_quality_review_version CHECK (version >= 0)
);

CREATE INDEX ix_quality_review_project_decision
    ON quality_review (project_id, decision, reviewed_at DESC);
