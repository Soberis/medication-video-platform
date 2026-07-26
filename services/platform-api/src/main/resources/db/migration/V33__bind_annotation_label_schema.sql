CREATE TABLE label_schema (
    label_schema_id UUID PRIMARY KEY,
    schema_key VARCHAR(64) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    schema_digest VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_label_schema_key_version UNIQUE (schema_key, schema_version),
    CONSTRAINT uk_label_schema_identity UNIQUE (label_schema_id, schema_digest),
    CONSTRAINT ck_label_schema_key
        CHECK (schema_key ~ '^[a-z][a-z0-9._-]{0,63}$'),
    CONSTRAINT ck_label_schema_version
        CHECK (schema_version ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT ck_label_schema_digest
        CHECK (schema_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_label_schema_status
        CHECK (status IN ('ACTIVE', 'RETIRED'))
);

CREATE UNIQUE INDEX uk_label_schema_single_active
    ON label_schema (status)
    WHERE status = 'ACTIVE';

CREATE TABLE label_definition (
    label_definition_id UUID PRIMARY KEY,
    label_schema_id UUID NOT NULL,
    label_id VARCHAR(64) NOT NULL,
    label_type VARCHAR(32) NOT NULL,
    display_name_zh VARCHAR(128) NOT NULL,
    description_zh VARCHAR(512) NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT uk_label_definition_id UNIQUE (label_schema_id, label_id),
    CONSTRAINT uk_label_definition_order UNIQUE (label_schema_id, sort_order),
    CONSTRAINT fk_label_definition_schema
        FOREIGN KEY (label_schema_id)
        REFERENCES label_schema (label_schema_id) ON DELETE RESTRICT,
    CONSTRAINT ck_label_definition_id
        CHECK (label_id ~ '^[a-z][a-z0-9_]{0,63}$'),
    CONSTRAINT ck_label_definition_type
        CHECK (label_type = 'PRIMARY_STEP'),
    CONSTRAINT ck_label_definition_display_name
        CHECK (length(btrim(display_name_zh)) > 0 AND display_name_zh !~ '[[:cntrl:]]'),
    CONSTRAINT ck_label_definition_description
        CHECK (length(btrim(description_zh)) > 0 AND description_zh !~ '[[:cntrl:]]'),
    CONSTRAINT ck_label_definition_order CHECK (sort_order > 0)
);

INSERT INTO label_schema (
    label_schema_id, schema_key, schema_version, schema_digest, status, created_at)
VALUES
    -- Retired compatibility marker: SHA-256 over UTF-8 "legacy-free-text\n".
    (
        '10000000-0000-0000-0000-000000000000',
        'legacy-free-text',
        'legacy-free-text.v1',
        'fd4336f9e0d4d12eff2b0c6d9cd27190b3b88c6b78b3b17d2001b59e9341c586',
        'RETIRED',
        TIMESTAMP WITH TIME ZONE '2026-07-26 00:00:00+00'
    ),
    (
        '10000000-0000-0000-0000-000000000001',
        'medication-primary-step',
        'p0-primary-step.v1',
        '305e92dcd10343e6c97b5780e59485b63183fadad7e9b87bc85db4bb037aa203',
        'ACTIVE',
        TIMESTAMP WITH TIME ZONE '2026-07-26 00:00:00+00'
    );

-- The active digest is SHA-256 over UTF-8 lines ordered by sort_order:
-- primary_step|label_id|display_name_zh|description_zh|sort_order\n
INSERT INTO label_definition (
    label_definition_id, label_schema_id, label_id, label_type,
    display_name_zh, description_zh, sort_order)
VALUES
    (
        '20000000-0000-0000-0000-000000000001',
        '10000000-0000-0000-0000-000000000001',
        'prepare_workspace',
        'PRIMARY_STEP',
        '准备工位/器具',
        '拿取或摆放托盘、注射器、棉签、药瓶、输液容器等；开始接触目标药品/耗材前结束。',
        1
    ),
    (
        '20000000-0000-0000-0000-000000000002',
        '10000000-0000-0000-0000-000000000001',
        'retrieve_medicine',
        'PRIMARY_STEP',
        '取药/取耗材',
        '取用药瓶、安瓿、西林瓶、输液容器或相关耗材；目标物进入配制动作前结束。',
        2
    ),
    (
        '20000000-0000-0000-0000-000000000003',
        '10000000-0000-0000-0000-000000000001',
        'prepare_dose',
        'PRIMARY_STEP',
        '剂量处理/配制',
        '抽吸、推注、转移、稀释、混匀、排气、消毒接口等核心配制动作；液体转移或剂量处理完成后结束。',
        3
    ),
    (
        '20000000-0000-0000-0000-000000000004',
        '10000000-0000-0000-0000-000000000001',
        'final_review',
        'PRIMARY_STEP',
        '最终复核/观察',
        '观察针筒刻度、液体状态、瓶口、标签或托盘；只标可见观察行为，不判断医疗正确性。',
        4
    ),
    (
        '20000000-0000-0000-0000-000000000005',
        '10000000-0000-0000-0000-000000000001',
        'cleanup',
        'PRIMARY_STEP',
        '清理/废弃处理',
        '处理棉签、瓶盖、针帽、包装、废弃物容器或锐器盒；废弃处理完成或视频结束。',
        5
    );

ALTER TABLE annotation_task
    ADD COLUMN label_schema_id UUID,
    ADD COLUMN label_schema_digest VARCHAR(64);

UPDATE annotation_task
SET label_schema_id = '10000000-0000-0000-0000-000000000001',
    label_schema_digest = '305e92dcd10343e6c97b5780e59485b63183fadad7e9b87bc85db4bb037aa203';

ALTER TABLE annotation_task
    ALTER COLUMN label_schema_id SET NOT NULL,
    ALTER COLUMN label_schema_digest SET NOT NULL,
    ADD CONSTRAINT fk_annotation_task_label_schema
        FOREIGN KEY (label_schema_id, label_schema_digest)
        REFERENCES label_schema (label_schema_id, schema_digest) ON DELETE RESTRICT;

ALTER TABLE annotation_revision
    ADD COLUMN label_schema_id UUID,
    ADD COLUMN label_schema_digest VARCHAR(64);

UPDATE annotation_revision
SET label_schema_id = '10000000-0000-0000-0000-000000000000',
    label_schema_digest = 'fd4336f9e0d4d12eff2b0c6d9cd27190b3b88c6b78b3b17d2001b59e9341c586';

ALTER TABLE annotation_revision
    ALTER COLUMN label_schema_id SET NOT NULL,
    ALTER COLUMN label_schema_digest SET NOT NULL,
    ADD CONSTRAINT fk_annotation_revision_label_schema
        FOREIGN KEY (label_schema_id, label_schema_digest)
        REFERENCES label_schema (label_schema_id, schema_digest) ON DELETE RESTRICT;
