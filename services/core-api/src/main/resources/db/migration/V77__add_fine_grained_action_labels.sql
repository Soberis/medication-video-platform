-- Retire existing 5-step schema and publish v2 schema with 10 medication labels
-- (5 primary steps + 5 fine-grained action steps for robotic demonstration & AI annotation).

UPDATE label_schema
   SET status = 'RETIRED'
 WHERE label_schema_id = '10000000-0000-0000-0000-000000000001';

INSERT INTO label_schema (
    label_schema_id, schema_key, schema_version, schema_digest, status, created_at)
VALUES (
    '10000000-0000-0000-0000-000000000002',
    'medication-primary-step',
    'p0-primary-step.v2',
    '6fe015879e5573db8e796dbba25fcf6c9e73d6a115e41a9dd4cfd1279b843901',
    'ACTIVE',
    TIMESTAMP WITH TIME ZONE '2026-09-10 00:00:00+00'
)
ON CONFLICT (label_schema_id) DO UPDATE
   SET status = 'ACTIVE',
       schema_digest = EXCLUDED.schema_digest;

-- Copy existing 5 primary step definitions to the new schema
INSERT INTO label_definition (
    label_definition_id, label_schema_id, label_id, label_type,
    display_name_zh, description_zh, sort_order)
VALUES
    (
        '20000000-0000-0000-0000-000000000011',
        '10000000-0000-0000-0000-000000000002',
        'prepare_workspace',
        'PRIMARY_STEP',
        '准备工位/器具',
        '拿取或摆放托盘、注射器、棉签、药瓶、输液容器等；开始接触目标药品/耗材前结束。',
        1
    ),
    (
        '20000000-0000-0000-0000-000000000012',
        '10000000-0000-0000-0000-000000000002',
        'retrieve_medicine',
        'PRIMARY_STEP',
        '取药/取耗材',
        '取用药瓶、安瓿、西林瓶、输液容器或相关耗材；目标物进入配制动作前结束。',
        2
    ),
    (
        '20000000-0000-0000-0000-000000000013',
        '10000000-0000-0000-0000-000000000002',
        'prepare_dose',
        'PRIMARY_STEP',
        '剂量处理/配制',
        '抽吸、推注、转移、稀释、混匀、排气、消毒接口等核心配制动作；液体转移或剂量处理完成后结束。',
        3
    ),
    (
        '20000000-0000-0000-0000-000000000014',
        '10000000-0000-0000-0000-000000000002',
        'final_review',
        'PRIMARY_STEP',
        '最终复核/观察',
        '观察针筒刻度、液体状态、瓶口、标签或托盘；只标可见观察行为，不判断医疗正确性。',
        4
    ),
    (
        '20000000-0000-0000-0000-000000000015',
        '10000000-0000-0000-0000-000000000002',
        'cleanup',
        'PRIMARY_STEP',
        '清理/废弃处理',
        '处理棉签、瓶盖、针帽、包装、废弃物容器或锐器盒；废弃处理完成或视频结束。',
        5
    ),
    -- Fine-grained action labels for robotic demonstration & AI pre-annotation
    (
        '20000000-0000-0000-0000-000000000016',
        '10000000-0000-0000-0000-000000000002',
        'open_cap',
        'PRIMARY_STEP',
        '操作瓶盖',
        '开启或盖上药瓶、西林瓶、安瓿瓶或输液袋的保护帽/保护盖。',
        6
    ),
    (
        '20000000-0000-0000-0000-000000000017',
        '10000000-0000-0000-0000-000000000002',
        'draw_liquid',
        'PRIMARY_STEP',
        '抽取药液',
        '持注射器抽取药瓶或溶媒中的液体，拉动活塞吸液。',
        7
    ),
    (
        '20000000-0000-0000-0000-000000000018',
        '10000000-0000-0000-0000-000000000002',
        'inject_mix',
        'PRIMARY_STEP',
        '注入混匀',
        '将药液注入输液袋、小瓶，或进行摇晃、翻转混匀。',
        8
    ),
    (
        '20000000-0000-0000-0000-000000000019',
        '10000000-0000-0000-0000-000000000002',
        'check_info',
        'PRIMARY_STEP',
        '核对信息',
        '核对药品名称、剂量标签、刻度或配药单信息。',
        9
    ),
    (
        '20000000-0000-0000-0000-000000000020',
        '10000000-0000-0000-0000-000000000002',
        'label_seal',
        'PRIMARY_STEP',
        '贴签封包',
        '粘贴输液标签、密封袋口或打包完成品。',
        10
    )
ON CONFLICT (label_schema_id, label_id) DO NOTHING;

-- Update unsubmitted assigned annotation tasks to use the active schema v2
UPDATE annotation_task
   SET label_schema_id = '10000000-0000-0000-0000-000000000002',
       label_schema_digest = '6fe015879e5573db8e796dbba25fcf6c9e73d6a115e41a9dd4cfd1279b843901'
 WHERE task_status = 'ASSIGNED'
   AND annotation_task_id NOT IN (SELECT annotation_task_id FROM annotation_revision);
