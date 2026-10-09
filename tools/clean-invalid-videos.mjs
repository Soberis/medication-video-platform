#!/usr/bin/env node

/**
 * clean-invalid-videos.mjs
 * 
 * 快速清理指定视频或批量清理不合格/失败视频的工具脚本。
 * 
 * 用法:
 *   node tools/clean-invalid-videos.mjs douyin-7445710436827172096
 *   node tools/clean-invalid-videos.mjs --key <key_or_id>
 *   node tools/clean-invalid-videos.mjs --list-failed
 *   node tools/clean-invalid-videos.mjs --delete-failed
 */

import { runPsql, runPsqlJson } from './lib/db.mjs'

export function deleteCaptureGroup(sessionIdOrKey) {
  const sql = `
BEGIN;
DO $$
DECLARE
  v_session_id uuid;
  v_group_key text;
  v_clip_ids uuid[];
  v_upload_file_ids uuid[];
BEGIN
  -- 查找 session id
  SELECT capture_session_id, capture_group_key 
  INTO v_session_id, v_group_key
  FROM capture_session 
  WHERE capture_session_id::text = '${sessionIdOrKey}' 
     OR capture_group_key = '${sessionIdOrKey}'
  LIMIT 1;

  IF v_session_id IS NULL THEN
    RAISE NOTICE '未找到采集记录: %', '${sessionIdOrKey}';
    RETURN;
  END IF;

  -- 查找关联的 clip_asset_id 和 upload_file_id
  SELECT array_agg(DISTINCT cs.clip_asset_id), array_agg(DISTINCT ca.source_upload_file_id)
  INTO v_clip_ids, v_upload_file_ids
  FROM camera_stream cs
  LEFT JOIN clip_asset ca ON cs.clip_asset_id = ca.clip_asset_id
  WHERE cs.capture_session_id = v_session_id;

  SET session_replication_role = 'replica';

  -- 1. AI 任务与标注
  DELETE FROM candidate_annotation WHERE clip_asset_id = ANY(v_clip_ids);
  DELETE FROM action_candidate WHERE ai_job_id IN (SELECT ai_job_id FROM ai_job WHERE clip_asset_id = ANY(v_clip_ids));
  DELETE FROM ai_job_attempt WHERE ai_job_id IN (SELECT ai_job_id FROM ai_job WHERE clip_asset_id = ANY(v_clip_ids));
  DELETE FROM ai_job_idempotency_receipt WHERE ai_job_id IN (SELECT ai_job_id FROM ai_job WHERE clip_asset_id = ANY(v_clip_ids));
  DELETE FROM ai_job_result_inbox WHERE ai_job_id IN (SELECT ai_job_id FROM ai_job WHERE clip_asset_id = ANY(v_clip_ids));
  DELETE FROM ai_job WHERE clip_asset_id = ANY(v_clip_ids);

  -- 2. Media 任务与探针
  DELETE FROM media_report WHERE clip_asset_id = ANY(v_clip_ids);
  DELETE FROM media_job_attempt WHERE media_job_id IN (SELECT media_job_id FROM media_job WHERE clip_asset_id = ANY(v_clip_ids));
  DELETE FROM media_job_delivery_recovery WHERE media_job_id IN (SELECT media_job_id FROM media_job WHERE clip_asset_id = ANY(v_clip_ids));
  DELETE FROM media_job_manual_replay WHERE media_job_id IN (SELECT media_job_id FROM media_job WHERE clip_asset_id = ANY(v_clip_ids));
  DELETE FROM media_job WHERE clip_asset_id = ANY(v_clip_ids);

  -- 3. 人工任务、复核与修订
  DELETE FROM annotation_review WHERE capture_group_id = v_session_id;
  DELETE FROM annotation_revision WHERE capture_group_id = v_session_id;
  DELETE FROM annotation_task_view_history WHERE annotation_task_id IN (SELECT annotation_task_id FROM annotation_task WHERE capture_group_id = v_session_id);
  DELETE FROM annotation_task_view WHERE annotation_task_id IN (SELECT annotation_task_id FROM annotation_task WHERE capture_group_id = v_session_id);
  DELETE FROM annotation_task WHERE capture_group_id = v_session_id;

  -- 4. 质检、清洗与隐私
  DELETE FROM capture_group_cleaning WHERE capture_group_id = v_session_id;
  DELETE FROM capture_group_quality_review WHERE capture_group_id = v_session_id;
  DELETE FROM quality_review WHERE clip_asset_id = ANY(v_clip_ids);
  DELETE FROM privacy_finding WHERE privacy_assessment_id IN (SELECT privacy_assessment_id FROM privacy_assessment WHERE clip_asset_id = ANY(v_clip_ids));
  DELETE FROM privacy_assessment WHERE clip_asset_id = ANY(v_clip_ids);
  DELETE FROM quarantine_record WHERE clip_asset_id = ANY(v_clip_ids);

  -- 5. 同步与快照
  DELETE FROM capture_sync_artifact WHERE capture_sync_revision_id IN (SELECT capture_sync_revision_id FROM capture_sync_revision WHERE capture_session_id = v_session_id);
  DELETE FROM capture_sync_job_attempt WHERE capture_sync_job_id IN (SELECT capture_sync_job_id FROM capture_sync_job WHERE capture_session_id = v_session_id);
  DELETE FROM capture_sync_stream_revision WHERE capture_session_id = v_session_id;
  DELETE FROM capture_sync_revision WHERE capture_session_id = v_session_id;
  DELETE FROM capture_sync_job WHERE capture_session_id = v_session_id;
  DELETE FROM camera_set_snapshot_member WHERE camera_set_snapshot_id IN (SELECT camera_set_snapshot_id FROM camera_set_snapshot WHERE capture_session_id = v_session_id);
  DELETE FROM camera_set_snapshot WHERE capture_session_id = v_session_id;

  -- 6. 核心会话与媒体文件
  DELETE FROM camera_stream WHERE capture_session_id = v_session_id;
  DELETE FROM capture_session WHERE capture_session_id = v_session_id;
  DELETE FROM clip_asset WHERE clip_asset_id = ANY(v_clip_ids);
  DELETE FROM upload_part WHERE upload_file_id = ANY(v_upload_file_ids);
  DELETE FROM upload_file WHERE upload_file_id = ANY(v_upload_file_ids);

  SET session_replication_role = 'origin';

  RAISE NOTICE '已彻底清理采集记录: % (%)', v_group_key, v_session_id;
END $$;
COMMIT;
`
  const out = runPsql(sql)
  return out
}

export function deleteCaptureGroupsBatch(sessionIds) {
  if (!sessionIds || !sessionIds.length) return 0
  const idsArrayLiteral = sessionIds.map((id) => `'${id}'::uuid`).join(',')
  const sql = `
BEGIN;
DO $$
DECLARE
  v_session_ids uuid[] := ARRAY[${idsArrayLiteral}];
  v_clip_ids uuid[];
  v_upload_file_ids uuid[];
BEGIN
  -- 查找关联的 clip_asset_id 和 upload_file_id
  SELECT array_agg(DISTINCT cs.clip_asset_id), array_agg(DISTINCT ca.source_upload_file_id)
  INTO v_clip_ids, v_upload_file_ids
  FROM camera_stream cs
  LEFT JOIN clip_asset ca ON cs.clip_asset_id = ca.clip_asset_id
  WHERE cs.capture_session_id = ANY(v_session_ids);

  SET session_replication_role = 'replica';

  -- 1. AI 任务与标注
  DELETE FROM candidate_annotation WHERE clip_asset_id = ANY(v_clip_ids);
  DELETE FROM action_candidate WHERE capture_group_id = ANY(v_session_ids) OR ai_job_id IN (SELECT ai_job_id FROM ai_job WHERE clip_asset_id = ANY(v_clip_ids));
  DELETE FROM ai_job_attempt WHERE ai_job_id IN (SELECT ai_job_id FROM ai_job WHERE clip_asset_id = ANY(v_clip_ids));
  DELETE FROM ai_job_idempotency_receipt WHERE ai_job_id IN (SELECT ai_job_id FROM ai_job WHERE clip_asset_id = ANY(v_clip_ids));
  DELETE FROM ai_job_result_inbox WHERE ai_job_id IN (SELECT ai_job_id FROM ai_job WHERE clip_asset_id = ANY(v_clip_ids));
  DELETE FROM ai_job WHERE clip_asset_id = ANY(v_clip_ids);

  -- 2. Media 任务与探针
  DELETE FROM media_report WHERE clip_asset_id = ANY(v_clip_ids);
  DELETE FROM media_job_attempt WHERE media_job_id IN (SELECT media_job_id FROM media_job WHERE clip_asset_id = ANY(v_clip_ids));
  DELETE FROM media_job_delivery_recovery WHERE media_job_id IN (SELECT media_job_id FROM media_job WHERE clip_asset_id = ANY(v_clip_ids));
  DELETE FROM media_job_manual_replay WHERE media_job_id IN (SELECT media_job_id FROM media_job WHERE clip_asset_id = ANY(v_clip_ids));
  DELETE FROM media_job WHERE clip_asset_id = ANY(v_clip_ids);

  -- 3. 人工任务、复核与修订
  DELETE FROM annotation_review WHERE capture_group_id = ANY(v_session_ids);
  DELETE FROM annotation_revision WHERE capture_group_id = ANY(v_session_ids);
  DELETE FROM annotation_task_view_history WHERE annotation_task_id IN (SELECT annotation_task_id FROM annotation_task WHERE capture_group_id = ANY(v_session_ids));
  DELETE FROM annotation_task_view WHERE annotation_task_id IN (SELECT annotation_task_id FROM annotation_task WHERE capture_group_id = ANY(v_session_ids));
  DELETE FROM annotation_task WHERE capture_group_id = ANY(v_session_ids);

  -- 4. 质检、清洗与隐私
  DELETE FROM capture_group_cleaning WHERE capture_group_id = ANY(v_session_ids);
  DELETE FROM capture_group_quality_review WHERE capture_group_id = ANY(v_session_ids);
  DELETE FROM quality_review WHERE clip_asset_id = ANY(v_clip_ids);
  DELETE FROM privacy_finding WHERE privacy_assessment_id IN (SELECT privacy_assessment_id FROM privacy_assessment WHERE clip_asset_id = ANY(v_clip_ids));
  DELETE FROM privacy_assessment WHERE clip_asset_id = ANY(v_clip_ids);
  DELETE FROM quarantine_record WHERE clip_asset_id = ANY(v_clip_ids);

  -- 5. 同步与快照
  DELETE FROM capture_sync_artifact WHERE capture_sync_revision_id IN (SELECT capture_sync_revision_id FROM capture_sync_revision WHERE capture_session_id = ANY(v_session_ids));
  DELETE FROM capture_sync_job_attempt WHERE capture_sync_job_id IN (SELECT capture_sync_job_id FROM capture_sync_job WHERE capture_session_id = ANY(v_session_ids));
  DELETE FROM capture_sync_stream_revision WHERE capture_session_id = ANY(v_session_ids);
  DELETE FROM capture_sync_revision WHERE capture_session_id = ANY(v_session_ids);
  DELETE FROM capture_sync_job WHERE capture_session_id = ANY(v_session_ids);
  DELETE FROM camera_set_snapshot_member WHERE camera_set_snapshot_id IN (SELECT camera_set_snapshot_id FROM camera_set_snapshot WHERE capture_session_id = ANY(v_session_ids));
  DELETE FROM camera_set_snapshot WHERE capture_session_id = ANY(v_session_ids);

  -- 6. 核心会话与媒体文件
  DELETE FROM camera_stream WHERE capture_session_id = ANY(v_session_ids);
  DELETE FROM capture_session WHERE capture_session_id = ANY(v_session_ids);
  DELETE FROM clip_asset WHERE clip_asset_id = ANY(v_clip_ids);
  DELETE FROM upload_part WHERE upload_file_id = ANY(v_upload_file_ids);
  DELETE FROM upload_file WHERE upload_file_id = ANY(v_upload_file_ids);

  SET session_replication_role = 'origin';

  RAISE NOTICE '已批量彻底清理 % 条采集记录', cardinality(v_session_ids);
END $$;
COMMIT;
`
  runPsql(sql)
  return sessionIds.length
}

export function getCaptureGroupsPipelineStatus(projectId) {
  const whereClause = projectId ? `WHERE cs.project_id = '${projectId}'` : ''
  const sql = `
SELECT 
  cs.capture_session_id,
  cs.capture_group_key,
  cs.created_at,
  mp.status as mp_status,
  mp.hand_result as mp_hand_result,
  qw.status as qw_status,
  (SELECT count(*) FROM action_candidate ac WHERE ac.capture_group_id = cs.capture_session_id) as action_candidate_count,
  CASE
    WHEN mp.hand_result = 'NO_HAND_DETECTED' THEN 'NO_HAND'
    WHEN mp.status = 'FAILED' OR qw.status = 'FAILED' THEN 'FAILED'
    WHEN mp.status IN ('QUEUED', 'RUNNING') OR qw.status IN ('QUEUED', 'RUNNING') THEN 'PROCESSING'
    WHEN mp.hand_result = 'HAND_DETECTED' AND qw.status = 'SUCCEEDED' THEN 'READY'
    WHEN mp.hand_result = 'HAND_DETECTED' THEN 'READY'
    ELSE 'PENDING'
  END as pipeline_status
FROM capture_session cs
LEFT JOIN camera_stream s ON cs.capture_session_id = s.capture_session_id
LEFT JOIN ai_job mp ON s.clip_asset_id = mp.clip_asset_id AND mp.algorithm_key = 'MEDIAPIPE_HAND_LANDMARKER'
LEFT JOIN ai_job qw ON s.clip_asset_id = qw.clip_asset_id AND qw.algorithm_key = 'QWEN3_5_ACTION'
${whereClause}
ORDER BY cs.created_at DESC;
`
  const rawItems = runPsqlJson(sql)
  const itemsMap = {}
  let readyCount = 0
  let processingCount = 0
  let invalidCount = 0
  let noHandCount = 0
  let failedCount = 0
  let pendingCount = 0

  for (const item of rawItems) {
    itemsMap[item.capture_session_id] = {
      pipeline_status: item.pipeline_status,
      mp_status: item.mp_status,
      mp_hand_result: item.mp_hand_result,
      qw_status: item.qw_status,
      action_candidate_count: Number(item.action_candidate_count || 0),
    }
    if (item.pipeline_status === 'READY') {
      readyCount++
    } else if (item.pipeline_status === 'PROCESSING') {
      processingCount++
    } else if (item.pipeline_status === 'NO_HAND' || item.pipeline_status === 'FAILED') {
      invalidCount++
      if (item.pipeline_status === 'NO_HAND') noHandCount++
      if (item.pipeline_status === 'FAILED') failedCount++
    } else {
      pendingCount++
    }
  }

  return {
    stats: {
      total: rawItems.length,
      ready: readyCount,
      processing: processingCount,
      invalid: invalidCount,
      noHand: noHandCount,
      failed: failedCount,
      pending: pendingCount,
    },
    items: itemsMap,
  }
}

export function cleanInvalidCaptureGroups(options = {}) {
  let targetIds = options.ids
  if (!targetIds || !targetIds.length) {
    const pipeline = getCaptureGroupsPipelineStatus(options.projectId)
    targetIds = Object.entries(pipeline.items)
      .filter(([_, item]) => item.pipeline_status === 'NO_HAND' || item.pipeline_status === 'FAILED')
      .map(([id]) => id)
  }
  if (!targetIds.length) return { ok: true, deletedCount: 0 }
  const deletedCount = deleteCaptureGroupsBatch(targetIds)
  return { ok: true, deletedCount }
}

export function listFailedCaptureGroups(projectId) {
  const whereClause = projectId ? `WHERE cs.project_id = '${projectId}'` : ''
  const sql = `
SELECT 
  cs.capture_session_id,
  cs.capture_group_key,
  cs.created_at
FROM capture_session cs
JOIN camera_stream s ON cs.capture_session_id = s.capture_session_id
JOIN ai_job mp ON s.clip_asset_id = mp.clip_asset_id AND mp.algorithm_key = 'MEDIAPIPE_HAND_LANDMARKER'
${whereClause ? whereClause + ' AND' : 'WHERE'} (mp.status = 'FAILED' OR mp.hand_result = 'NO_HAND_DETECTED')
ORDER BY cs.created_at DESC;
`
  return runPsqlJson(sql)
}

export async function retryHandPoseJob(clipAssetId, projectId) {
  let effectiveProjectId = projectId
  if (!effectiveProjectId) {
    try {
      const projectLookup = runPsql(`
        SELECT cs.project_id FROM camera_stream s 
        JOIN capture_session cs ON s.capture_session_id = cs.capture_session_id 
        WHERE s.clip_asset_id = '${clipAssetId}' LIMIT 1;
      `)
      if (projectLookup && projectLookup.trim()) {
        effectiveProjectId = projectLookup.trim()
      }
    } catch {}
  }
  if (!effectiveProjectId) {
    effectiveProjectId = '944a19eb-03b1-4b55-98fb-f8e108e23d92'
  }

  // Core API 原生支持 FAILED 状态合法重试与重新排队，不再需要通过原始 SQL 暴力删表
  const url = `http://localhost:8080/api/v1/projects/${effectiveProjectId}/videos/${clipAssetId}/hand-pose-detections`
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      Authorization: 'Bearer synthetic-local-admin-bearer-token-do-not-reuse',
      'Content-Type': 'application/json',
      'Idempotency-Key': `retry-hand-${clipAssetId}-${Date.now()}`,
    },
  })
  return { ok: res.ok, status: res.status }
}

export async function retryFailedHandPoseBatch(limit = 20, projectId) {
  const whereClause = projectId ? `WHERE cs.project_id = '${projectId}' AND mp.status = 'FAILED'` : `WHERE mp.status = 'FAILED'`
  const sql = `
SELECT DISTINCT s.clip_asset_id, cs.project_id
FROM capture_session cs
JOIN camera_stream s ON cs.capture_session_id = s.capture_session_id
JOIN ai_job mp ON s.clip_asset_id = mp.clip_asset_id AND mp.algorithm_key = 'MEDIAPIPE_HAND_LANDMARKER'
${whereClause}
LIMIT ${limit};
`
  const raw = runPsqlJson(sql)
  let count = 0
  for (const item of raw) {
    try {
      const res = await retryHandPoseJob(item.clip_asset_id, item.project_id)
      if (res.ok) count++
    } catch {}
  }
  return { ok: true, retriedCount: count }
}

// CLI 执行入口
async function main() {
  const args = process.argv.slice(2)
  if (args.length === 0 || args.includes('--help') || args.includes('-h')) {
    console.log(`
使用方法:
  node tools/clean-invalid-videos.mjs <视频标识或ID>
    例如: node tools/clean-invalid-videos.mjs douyin-7445710436827172096

  node tools/clean-invalid-videos.mjs --pipeline
    查看当前所有视频的 AI 处理进度看板与统计

  node tools/clean-invalid-videos.mjs --list-failed
    列出所有 AI 分析全部失败且无成功的废弃视频清单

  node tools/clean-invalid-videos.mjs --clean-invalid
    一键批量彻底清理所有废弃视频（未检出人手 + 处理失败）
`)
    process.exit(0)
  }

  if (args.includes('--pipeline')) {
    const status = getCaptureGroupsPipelineStatus()
    console.log('\n📊 AI 数据流水线看板统计:')
    console.log(` - 全部视频总数: ${status.stats.total}`)
    console.log(` - 🟢 AI 已就绪: ${status.stats.ready}`)
    console.log(` - 🟡 后台分析中: ${status.stats.processing}`)
    console.log(` - 🔴 异常废片: ${status.stats.invalid} (未检出人手: ${status.stats.noHand}, 处理失败: ${status.stats.failed})`)
    console.log(` - ⚪ 待处理/待调度: ${status.stats.pending}\n`)
    return
  }

  if (args.includes('--list-failed')) {
    console.log('正在扫描全部 AI 分析失败的废弃视频...')
    const failedList = listFailedCaptureGroups()
    console.log(`共发现 ${failedList.length} 条全失败的废弃视频:`)
    for (const item of failedList) {
      console.log(` - [${item.capture_group_key}] ID: ${item.capture_session_id} (创建时间: ${item.created_at})`)
    }
    return
  }

  if (args.includes('--clean-invalid') || args.includes('--delete-failed')) {
    console.log('正在执行一键批量清理全部废弃视频（无手 + 失败）...')
    const result = cleanInvalidCaptureGroups()
    console.log(`\n🎉 清理成功！已彻底删除 ${result.deletedCount} 条废弃视频及其关联数据。`)
    return
  }

  const target = args[0].replace(/^--key=/, '')
  console.log(`正在清理指定采集记录: ${target} ...`)
  const out = deleteCaptureGroup(target)
  console.log(out || '✓ 清理完成')
}

if (process.argv[1]?.endsWith('clean-invalid-videos.mjs')) {
  main().catch((err) => {
    console.error('清理失败:', err)
    process.exit(1)
  })
}

