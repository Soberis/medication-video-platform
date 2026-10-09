#!/usr/bin/env node

/**
 * auto-adopt-qualified.mjs
 * 
 * 自动采纳合格视频（有手部检测 + 有 AI 动作候选）的批处理工具。
 * 
 * 用法:
 *   node tools/auto-adopt-qualified.mjs --scan
 *   node tools/auto-adopt-qualified.mjs --run
 *   node tools/auto-adopt-qualified.mjs --group <capture_group_key_or_id>
 */

import { runPsql, runPsqlJson } from './lib/db.mjs'

const API_BASE = process.env.PLATFORM_API_BASE || 'http://127.0.0.1:8080'
const ADMIN_TOKEN = process.env.DEV_ADMIN_BEARER_TOKEN || 'synthetic-local-admin-bearer-token-do-not-reuse'

/**
 * 扫描当前库内所有合格（手部检测 > 0 且 动作候选 > 0）且尚未完成复核的视频
 */
export function scanQualifiedCaptureGroups(projectId = null) {
  const projectFilter = projectId ? `AND cs.project_id = '${projectId}'` : ''
  const sql = `
SELECT 
  cs.capture_session_id as capture_group_id,
  cs.capture_group_key,
  cs.project_id,
  cs.created_at,
  count(distinct ca.candidate_annotation_id) as hand_count,
  count(distinct ac.action_candidate_id) as action_count,
  t.annotation_task_id,
  t.task_status
FROM capture_session cs
JOIN camera_stream s ON cs.capture_session_id = s.capture_session_id
LEFT JOIN candidate_annotation ca ON s.clip_asset_id = ca.clip_asset_id
LEFT JOIN ai_job j ON s.clip_asset_id = j.clip_asset_id AND j.algorithm_key = 'QWEN3_5_ACTION'
LEFT JOIN action_candidate ac ON j.ai_job_id = ac.ai_job_id
LEFT JOIN annotation_task t ON cs.capture_session_id = t.capture_group_id
WHERE cs.receipt_state = 'COMPLETE'
  ${projectFilter}
GROUP BY cs.capture_session_id, cs.capture_group_key, cs.project_id, cs.created_at, t.annotation_task_id, t.task_status
HAVING count(distinct ca.candidate_annotation_id) > 0 
   AND count(distinct ac.action_candidate_id) > 0
   AND (t.task_status IS NULL OR t.task_status != 'COMPLETED')
ORDER BY cs.created_at DESC;
`
  return runPsqlJson(sql)
}

/**
 * 自动采纳单个合格视频
 */
export async function adoptCaptureGroup(groupIdOrKey, options = {}) {
  // 1. 查库获取 session id 和 project id
  const sql = `
SELECT cs.capture_session_id, cs.capture_group_key, cs.project_id
FROM capture_session cs
WHERE cs.capture_session_id::text = '${groupIdOrKey}' OR cs.capture_group_key = '${groupIdOrKey}'
LIMIT 1;
`
  const rows = runPsqlJson(sql)
  if (rows.length === 0) {
    throw new Error(`未找到采集记录: ${groupIdOrKey}`)
  }
  const group = rows[0]
  const projectId = group.project_id
  const groupId = group.capture_session_id

  const authHeaders = {
    'Authorization': `Bearer ${ADMIN_TOKEN}`,
    'Content-Type': 'application/json',
  }

  // 2. 创建或准备 AnnotationTask
  const taskRes = await fetch(`${API_BASE}/api/v1/projects/${projectId}/capture-groups/${groupId}/annotation-tasks`, {
    method: 'POST',
    headers: { ...authHeaders, 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ assignee_actor_id: 'admin' }),
  })
  if (!taskRes.ok) {
    const errText = await taskRes.text()
    throw new Error(`创建标注任务失败 [${taskRes.status}]: ${errText}`)
  }
  const task = await taskRes.json()
  const taskId = task.annotation_task_id
  const inputVersion = task.group_input_version
  const views = task.camera_views ?? []

  // 3. 为每个机位拉取候选动作并提交该机位视图
  const allSubmittedSegmentIds = []
  for (const view of views) {
    const cameraNumber = view.camera_number
    const clipId = view.clip_asset_id
    if (!clipId) continue

    // 获取该机位的动作候选
    const candidatesRes = await fetch(
      `${API_BASE}/api/v1/projects/${projectId}/capture-groups/${groupId}/action-candidates?clip_asset_id=${clipId}`,
      { headers: authHeaders }
    )
    if (!candidatesRes.ok) {
      throw new Error(`获取机位 ${cameraNumber} 动作候选失败: ${await candidatesRes.text()}`)
    }
    const candidates = await candidatesRes.json()
    if (!candidates || candidates.length === 0) {
      continue
    }

    const durSql = `SELECT duration_microseconds FROM media_report WHERE clip_asset_id = '${clipId}' LIMIT 1;`
    const durRows = runPsqlJson(durSql)
    const durationMs = durRows[0]?.duration_microseconds ? Math.floor(Number(durRows[0].duration_microseconds) / 1000) : null

    const segments = candidates
      .map((c) => {
        const startMs = Math.max(0, c.start_ms)
        const endMs = durationMs ? Math.min(c.end_ms, durationMs) : c.end_ms
        return {
          segment_id: crypto.randomUUID(),
          start_ms: startMs,
          end_ms: endMs,
          label: c.action_label,
          source_candidate_ids: [c.action_candidate_id],
        }
      })
      .filter((s) => s.end_ms > s.start_ms)

    // 更新机位视图为 SUBMITTED
    const putViewRes = await fetch(
      `${API_BASE}/api/v1/projects/${projectId}/annotation-tasks/${taskId}/camera-views/${cameraNumber}`,
      {
        method: 'PUT',
        headers: { ...authHeaders, 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({
          expected_version: view.version,
          expected_input_version: inputVersion,
          state: 'SUBMITTED',
          segments,
          note: 'AI 自动采纳',
          exclusion_reason: null,
        }),
      }
    )
    if (!putViewRes.ok) {
      throw new Error(`提交机位 ${cameraNumber} 视图失败: ${await putViewRes.text()}`)
    }
    const updatedTask = await putViewRes.json()
    const updatedView = updatedTask.camera_views?.find((v) => v.camera_number === cameraNumber)
    for (const seg of updatedView?.segments ?? segments) {
      allSubmittedSegmentIds.push({ camera_number: cameraNumber, segment_id: seg.segment_id })
    }
  }

  if (allSubmittedSegmentIds.length === 0) {
    throw new Error(`视频 ${group.capture_group_key} 无有效动作片段，无法自动采纳`)
  }

  // 4. 提交整组标注修订
  // 重新获取最新 task version
  const latestTaskRes = await fetch(`${API_BASE}/api/v1/projects/${projectId}/annotation-tasks`, {
    headers: authHeaders,
  })
  const taskList = await latestTaskRes.json()
  const currentTask = taskList.items?.find((t) => t.annotation_task_id === taskId) ?? task

  const correspondences = allSubmittedSegmentIds.map((item) => ({
    correspondence_id: crypto.randomUUID(),
    status: 'UNCERTAIN',
    segments: [{ camera_number: item.camera_number, segment_id: item.segment_id }],
    reason: '单机位自动采纳',
  }))

  const revisionRes = await fetch(`${API_BASE}/api/v1/projects/${projectId}/annotation-tasks/${taskId}/revisions`, {
    method: 'POST',
    headers: { ...authHeaders, 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({
      expected_task_version: currentTask.version,
      expected_input_version: inputVersion,
      correspondences,
      note: 'AI 自动采纳',
    }),
  })
  if (!revisionRes.ok) {
    throw new Error(`提交整组标注修订失败: ${await revisionRes.text()}`)
  }
  const revision = await revisionRes.json()
  const revisionId = revision.annotation_revision_id

  // 5. 通过复核
  const reviewRes = await fetch(`${API_BASE}/api/v1/projects/${projectId}/annotation-revisions/${revisionId}/review`, {
    method: 'POST',
    headers: { ...authHeaders, 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({
      decision: 'APPROVED',
      review_note: 'AI 自动核验通过',
      reviewed_camera_numbers: views.map((v) => v.camera_number),
      return_camera_numbers: [],
    }),
  })
  if (!reviewRes.ok) {
    throw new Error(`复核通过失败: ${await reviewRes.text()}`)
  }

  return { ok: true, capture_group_key: group.capture_group_key, capture_group_id: groupId, status: 'COMPLETED' }
}

/**
 * 导出已批准演示数据集为 HDF5 v2
 */
export async function exportApprovedHdf5(outPath = 'dataset-export.hdf5', options = {}) {
  const opts = typeof options === 'string' ? { projectId: options } : (options || {})
  const projectId = opts.projectId || null
  const projectFilter = projectId ? `AND r.project_id = '${projectId}'` : ''
  const sql = `
SELECT 
  r.capture_group_id,
  r.project_id
FROM annotation_review r
JOIN camera_stream s ON s.capture_session_id = r.capture_group_id
JOIN ai_job j ON j.clip_asset_id = s.clip_asset_id
WHERE r.decision = 'APPROVED'
  AND j.algorithm_key = 'MEDIAPIPE_HAND_LANDMARKER'
  AND j.status = 'SUCCEEDED'
  AND EXISTS (
    SELECT 1 FROM candidate_annotation ca WHERE ca.clip_asset_id = s.clip_asset_id
  )
  ${projectFilter}
GROUP BY r.capture_group_id, r.project_id;
`
  const rows = runPsqlJson(sql)
  if (rows.length === 0) {
    console.log('当前没有符合导出要求的已批准演示数据。')
    return { ok: false, count: 0 }
  }

  const targetProjectId = projectId || rows[0].project_id
  const matchingIds = rows.filter((r) => r.project_id === targetProjectId).map((r) => r.capture_group_id)
  console.log(`\n=== 正在请求打包并导出 HDF5 v2 数据集 (项目: ${targetProjectId}, 包含 ${matchingIds.length} 部演示) ===`)

  // 当导出全项目时省略 capture_group_id 参数，避免百条以上 UUID 堆积触发 HTTP 400 (Tomcat 8KB Header Limit)
  const isSubset = Array.isArray(options?.captureGroupIds) && options.captureGroupIds.length > 0
  const q = isSubset ? '&' + options.captureGroupIds.map((id) => `capture_group_id=${id}`).join('&') : ''
  const exportUrl = `${API_BASE}/api/v1/projects/${targetProjectId}/human-demonstrations/hdf5/v2/download?tensor_layout=true${q}`
  const res = await fetch(exportUrl, {
    headers: { Authorization: `Bearer ${ADMIN_TOKEN}`, Accept: 'application/x-hdf5' },
  })

  if (!res.ok) {
    const err = await res.text()
    throw new Error(`导出失败 (${res.status}): ${err}`)
  }

  const arrayBuffer = await res.arrayBuffer()
  const buffer = Buffer.from(arrayBuffer)
  const { dirname, resolve } = await import('node:path')
  const { mkdirSync, writeFileSync } = await import('node:fs')
  mkdirSync(dirname(resolve(outPath)), { recursive: true })
  writeFileSync(outPath, buffer)
  const sizeMb = (buffer.length / 1024 / 1024).toFixed(2)
  console.log(`🎉 导出成功！文件保存至: ${outPath} [文件大小: ${buffer.length} 字节 / ${sizeMb} MB, 包含 ${matchingIds.length} 部完整演示]`)
  return { ok: true, count: matchingIds.length, size: buffer.length, outPath }
}

/**
 * 批量采纳所有合格视频
 */
export async function adoptAllQualifiedCaptureGroups(projectId = null) {
  const qualified = scanQualifiedCaptureGroups(projectId)
  const results = { total: qualified.length, succeeded: 0, failed: 0, errors: [] }

  for (const item of qualified) {
    try {
      await adoptCaptureGroup(item.capture_group_id)
      results.succeeded++
    } catch (err) {
      results.failed++
      results.errors.push({ key: item.capture_group_key, error: String(err) })
    }
  }

  return results
}

// CLI 入口
async function main() {
  const args = process.argv.slice(2)
  if (args.length === 0 || args.includes('--help') || args.includes('-h')) {
    console.log(`
使用方法:
  node tools/auto-adopt-qualified.mjs --scan
    扫描当前库内所有合格（有手部 + 有动作候选）且待采纳的视频

  node tools/auto-adopt-qualified.mjs --run
    批量自动采纳所有合格视频并直接复核通过（可直接导出 HDF5）

  node tools/auto-adopt-qualified.mjs --export [outPath]
    一键打包并导出所有已通过复核的视频为 HDF5 v2 数据集 (默认 dataset-export.hdf5)

  node tools/auto-adopt-qualified.mjs --all [outPath]
    一键全自动：采纳所有合格视频 + 立即导出 HDF5 数据集

  node tools/auto-adopt-qualified.mjs --group <标识或ID>
    自动采纳指定的单个合格视频
`)
    process.exit(0)
  }

  const shouldExport = args.includes('--export') || args.includes('-e')
  const shouldAll = args.includes('--all')
  const outIdx = args.indexOf('--out')
  let outPath = outIdx !== -1 ? args[outIdx + 1] : 'dataset-export.hdf5'
  const exportArgIdx = args.indexOf('--export')
  if (exportArgIdx !== -1 && args[exportArgIdx + 1] && !args[exportArgIdx + 1].startsWith('-')) {
    outPath = args[exportArgIdx + 1]
  }
  const allArgIdx = args.indexOf('--all')
  if (allArgIdx !== -1 && args[allArgIdx + 1] && !args[allArgIdx + 1].startsWith('-')) {
    outPath = args[allArgIdx + 1]
  }

  if (args.includes('--scan')) {
    console.log('正在扫描合格且未通过的视频...')
    const list = scanQualifiedCaptureGroups()
    console.log(`\n共发现 ${list.length} 条待采纳合格视频:`)
    for (const item of list) {
      console.log(` - [${item.capture_group_key}] 手部样本: ${item.hand_count}帧, 动作候选: ${item.action_count}段`)
    }
    return
  }

  if (args.includes('--export') && !args.includes('--run') && !shouldAll) {
    await exportApprovedHdf5(outPath)
    return
  }

  if (args.includes('--run') || shouldAll) {
    console.log('正在扫描合格视频...')
    const list = scanQualifiedCaptureGroups()
    console.log(`共发现 ${list.length} 条合格未采纳视频，开始批量自动采纳...`)
    let success = 0
    let failed = 0
    for (const [index, item] of list.entries()) {
      process.stdout.write(`[${index + 1}/${list.length}] 正在采纳 ${item.capture_group_key}... `)
      try {
        await adoptCaptureGroup(item.capture_group_id)
        console.log('✓ 已通过复核')
        success++
      } catch (err) {
        console.log(`✗ 失败: ${err.message}`)
        failed++
      }
    }
    console.log(`\n🎉 处理完成！成功: ${success} 条，失败: ${failed} 条。已通过视频均可直接导出 HDF5！`)
    if (shouldExport || shouldAll) {
      await exportApprovedHdf5(outPath)
    }
    return
  }

  const groupArg = args.find((a) => a.startsWith('--group='))?.replace('--group=', '') ||
    (args[args.indexOf('--group') + 1]) ||
    args[0]

  if (groupArg) {
    console.log(`正在采纳视频: ${groupArg} ...`)
    const res = await adoptCaptureGroup(groupArg)
    console.log(`✓ 视频 ${res.capture_group_key} 已自动采纳并完成复核通过！`)
  }
}

if (process.argv[1]?.endsWith('auto-adopt-qualified.mjs')) {
  main().catch((err) => {
    console.error('执行失败:', err)
    process.exit(1)
  })
}
