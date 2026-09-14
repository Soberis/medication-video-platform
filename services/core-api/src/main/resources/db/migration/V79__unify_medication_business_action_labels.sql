-- Publish the unified v4 business action vocabulary.
-- The v3 schema remains immutable for tasks, revisions, candidates and exports
-- that already reference it; new tasks resolve the new active schema.

UPDATE label_schema
   SET status = 'RETIRED'
 WHERE status = 'ACTIVE';

INSERT INTO label_schema (
    label_schema_id, schema_key, schema_version, schema_digest, status, created_at)
VALUES (
    '10000000-0000-0000-0000-000000000004',
    'medication-primary-step',
    'p0-atomic-action.v4',
    '2156bfa17488e30b3660ed2060d3ad6d7db3fc853a24a0c4878abf4b9e1c3072',
    'ACTIVE',
    TIMESTAMP WITH TIME ZONE '2026-09-14 00:00:00+00'
)
ON CONFLICT (label_schema_id) DO UPDATE
   SET status = 'ACTIVE',
       schema_digest = EXCLUDED.schema_digest;

INSERT INTO label_definition (
    label_definition_id, label_schema_id, label_id, label_type,
    display_name_zh, description_zh, sort_order)
VALUES
    (
        '20000000-0000-0000-0000-000000000046',
        '10000000-0000-0000-0000-000000000004',
        'acquire_item',
        'PRIMARY_STEP',
        '拿取容器/工具',
        '手明确到达并取得将参与配药的容器或工具，物体离开原稳定位置并进入操作区；不标持续持有、调整位置或重复抓握',
        1
    ),
    (
        '20000000-0000-0000-0000-000000000041',
        '10000000-0000-0000-0000-000000000004',
        'open_container',
        'PRIMARY_STEP',
        '开启容器',
        '明确打开药品主容器，如掰开安瓿或移除西林瓶/输液容器的保护部件；不包括拆注射器包装',
        2
    ),
    (
        '20000000-0000-0000-0000-000000000042',
        '10000000-0000-0000-0000-000000000004',
        'insert_needle',
        'PRIMARY_STEP',
        '针头进入容器',
        '清楚看到针头穿过瓶塞或接口并进入目标药品容器；仅靠近或被遮挡时不标注',
        4
    ),
    (
        '20000000-0000-0000-0000-000000000043',
        '10000000-0000-0000-0000-000000000004',
        'draw_liquid',
        'PRIMARY_STEP',
        '抽取药液',
        '针头已进入容器且活塞向后移动，能看到药液进入注射器；仅进针没有抽液证据时不标注',
        5
    ),
    (
        '20000000-0000-0000-0000-000000000044',
        '10000000-0000-0000-0000-000000000004',
        'transfer_liquid',
        'PRIMARY_STEP',
        '转移药液',
        '使用注射器把药液从一个容器推入另一个目标容器；要求能确认来源、目标和推注方向，不表示面向患者给药',
        6
    ),
    (
        '20000000-0000-0000-0000-000000000045',
        '10000000-0000-0000-0000-000000000004',
        'disinfect_access',
        'PRIMARY_STEP',
        '消毒容器接口',
        '看到棉签或其他消毒用品实际擦拭瓶塞、安瓿颈或容器接口；仅出现用品不构成该动作',
        3
    ),
    (
        '20000000-0000-0000-0000-000000000047',
        '10000000-0000-0000-0000-000000000004',
        'agitate_solution',
        'PRIMARY_STEP',
        '混匀药液',
        '明确摇晃、翻转或振荡装有药液的容器以混匀；仅拿着或移动容器不标注',
        7
    ),
    (
        '20000000-0000-0000-0000-000000000048',
        '10000000-0000-0000-0000-000000000004',
        'discard_sharps',
        'PRIMARY_STEP',
        '处置锐器',
        '明确把针头、安瓿或其他锐器放入锐器盒；仅出现锐器盒或丢弃普通耗材不标注',
        8
    ),
    (
        '20000000-0000-0000-0000-000000000049',
        '10000000-0000-0000-0000-000000000004',
        'label_prepared_medication',
        'PRIMARY_STEP',
        '给成品贴签',
        '看到标签实际粘贴到注射器、药瓶或成品输液容器；仅查看、拿着标签或成品包装不标注',
        9
    )
ON CONFLICT (label_schema_id, label_id) DO NOTHING;
