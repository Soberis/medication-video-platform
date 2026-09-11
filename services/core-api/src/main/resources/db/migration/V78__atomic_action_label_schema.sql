-- Retire existing v2 schema and publish v3 schema with 7 atomic action labels
-- (Retiring broad macro steps: prepare_workspace, prepare_dose, final_review, cleanup).

UPDATE label_schema
   SET status = 'RETIRED'
 WHERE label_schema_id = '10000000-0000-0000-0000-000000000002';

INSERT INTO label_schema (
    label_schema_id, schema_key, schema_version, schema_digest, status, created_at)
VALUES (
    '10000000-0000-0000-0000-000000000003',
    'medication-primary-step',
    'p0-atomic-action.v3',
    'c966386d1eed4e923b213bd5c23a69791ee71afc55423a39f2756cc83c0604e1',
    'ACTIVE',
    TIMESTAMP WITH TIME ZONE '2026-09-11 00:00:00+00'
)
ON CONFLICT (label_schema_id) DO UPDATE
   SET status = 'ACTIVE',
       schema_digest = EXCLUDED.schema_digest;

INSERT INTO label_definition (
    label_definition_id, label_schema_id, label_id, label_type,
    display_name_zh, description_zh, sort_order)
VALUES
    (
        '20000000-0000-0000-0000-000000000021',
        '10000000-0000-0000-0000-000000000003',
        'retrieve_medicine',
        'PRIMARY_STEP',
        '拿取药瓶/耗材',
        '拿取或抓握药瓶、西林瓶、安瓿瓶、注射器或输液容器等目标耗材。',
        1
    ),
    (
        '20000000-0000-0000-0000-000000000022',
        '10000000-0000-0000-0000-000000000003',
        'open_cap',
        'PRIMARY_STEP',
        '开启瓶塞/针帽',
        '开启或掰开药瓶盖、西林瓶铝盖、安瓿瓶颈部，或拔下注射器针帽。',
        2
    ),
    (
        '20000000-0000-0000-0000-000000000023',
        '10000000-0000-0000-0000-000000000003',
        'draw_liquid',
        'PRIMARY_STEP',
        '抽取药液',
        '持注射器穿刺药瓶或溶媒，拉动活塞吸取药液。',
        3
    ),
    (
        '20000000-0000-0000-0000-000000000024',
        '10000000-0000-0000-0000-000000000003',
        'inject_liquid',
        'PRIMARY_STEP',
        '推注药液',
        '推动注射器活塞将药液注入输液袋、药瓶或目标容器。',
        4
    ),
    (
        '20000000-0000-0000-0000-000000000025',
        '10000000-0000-0000-0000-000000000003',
        'mix_solution',
        'PRIMARY_STEP',
        '晃动混匀',
        '手持输液袋、小瓶或注射器进行轻微晃动、翻转以混匀药液。',
        5
    ),
    (
        '20000000-0000-0000-0000-000000000026',
        '10000000-0000-0000-0000-000000000003',
        'check_info',
        'PRIMARY_STEP',
        '核对信息',
        '拿起或观察药瓶标签、输液卡、处方或针筒刻度以核对信息。',
        6
    ),
    (
        '20000000-0000-0000-0000-000000000027',
        '10000000-0000-0000-0000-000000000003',
        'label_seal',
        'PRIMARY_STEP',
        '粘贴标签/封签',
        '在输液瓶/袋上粘贴核对标签，或进行成品密封封签。',
        7
    )
ON CONFLICT (label_schema_id, label_id) DO NOTHING;

-- Update unsubmitted assigned annotation tasks to use active schema v3
UPDATE annotation_task
   SET label_schema_id = '10000000-0000-0000-0000-000000000003',
       label_schema_digest = 'c966386d1eed4e923b213bd5c23a69791ee71afc55423a39f2756cc83c0604e1'
 WHERE task_status = 'ASSIGNED'
   AND annotation_task_id NOT IN (SELECT annotation_task_id FROM annotation_revision);
