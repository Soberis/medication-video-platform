-- Sharpen visual criteria and anti-hallucination boundaries for atomic action labels
-- without breaking schema compatibility or existing revision immutability.

UPDATE label_definition
   SET description_zh = '手伸向原静止物品并将其拿起离开台面的瞬间（通常仅1~3秒），物体进入操作区；持续握持不动、调整握姿、拆包装或未使物体发生位移绝不标注。'
 WHERE label_schema_id = '10000000-0000-0000-0000-000000000004' AND label_id = 'acquire_item';

UPDATE label_definition
   SET description_zh = '明确打开药品主容器或包装密封，如掰开安瓿瓶、取下西林瓶铝盖/塑料塞或拔下注射器针帽；不包括拆外层纸塑包装。'
 WHERE label_schema_id = '10000000-0000-0000-0000-000000000004' AND label_id = 'open_container';

UPDATE label_definition
   SET description_zh = '看到棉签或其他消毒用品实际反复擦拭瓶塞、安瓿颈或容器接口（持续擦拭2秒以上）；仅拿着棉签或轻微靠近不标注。'
 WHERE label_schema_id = '10000000-0000-0000-0000-000000000004' AND label_id = 'disinfect_access';

UPDATE label_definition
   SET description_zh = '清楚看到针头穿过瓶塞或接口并进入目标药品容器且保持进针状态；仅靠近、悬停或被遮挡时不标注。'
 WHERE label_schema_id = '10000000-0000-0000-0000-000000000004' AND label_id = 'insert_needle';

UPDATE label_definition
   SET description_zh = '针头已在容器内且活塞向后移动，能清楚看到药液进入注射器针筒；仅进针但没有抽吸液体证据时不标注。'
 WHERE label_schema_id = '10000000-0000-0000-0000-000000000004' AND label_id = 'draw_liquid';

UPDATE label_definition
   SET description_zh = '使用注射器把药液推入另一目标药瓶、输液袋或容器；必须能确认推注方向与活塞前移，不表示面向患者给药。'
 WHERE label_schema_id = '10000000-0000-0000-0000-000000000004' AND label_id = 'transfer_liquid';

UPDATE label_definition
   SET description_zh = '明确上下翻转输液袋或反复摇晃/振荡药瓶以混匀药液；水剂抽吸无此动作，仅拿着或微移容器绝不标注。'
 WHERE label_schema_id = '10000000-0000-0000-0000-000000000004' AND label_id = 'agitate_solution';

UPDATE label_definition
   SET description_zh = '明确看到将针头、安瓿等锐器投入硬质利器盒（Sharps Container）；手部移开、放下物品或将垃圾扔进普通垃圾袋绝不标注。'
 WHERE label_schema_id = '10000000-0000-0000-0000-000000000004' AND label_id = 'discard_sharps';

UPDATE label_definition
   SET description_zh = '看到配药标签实际粘贴到注射器、药瓶或成品输液容器；仅查看标签、拿着标签或拿取成品包装绝不标注。'
 WHERE label_schema_id = '10000000-0000-0000-0000-000000000004' AND label_id = 'label_prepared_medication';
