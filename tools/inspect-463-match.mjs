import { readdir, stat } from 'node:fs/promises'
import { join } from 'node:path'
import { runPsqlJson } from './lib/db.mjs'
import { matchFolderPlatform } from './import-web-video-batch.mjs'

const TARGET_DIR = process.env.TARGET_DIR || 'D:\\下载\\第一人称配药实操视频'

async function inspect() {
  const entries = await readdir(TARGET_DIR, { withFileTypes: true })
  const folders = entries.filter((e) => e.isDirectory()).map((e) => e.name)
  console.log(`📁 磁盘中总文件夹数: ${folders.length}`)

  // 463 条合格且 COMPLETED 的 capture_session
  const completed463 = runPsqlJson(`
    SELECT cs.capture_group_key, cs.capture_session_id
    FROM capture_session cs
    JOIN annotation_task t ON cs.capture_session_id = t.capture_group_id
    WHERE cs.quality_status = 'PASS'
      AND cs.receipt_state = 'COMPLETE'
      AND t.task_status = 'COMPLETED';
  `)
  console.log(`🎯 463 目标数据记录数: ${completed463.length}`)
  const targetKeySet = new Set(completed463.map((r) => r.capture_group_key))

  const matching463 = []
  const not463 = []

  for (const f of folders) {
    const match = matchFolderPlatform(f)
    const key = match ? `${match.platform}-${match.videoId}` : null
    if (key && targetKeySet.has(key)) {
      matching463.push({ folder: f, key })
    } else {
      not463.push({ folder: f, key })
    }
  }

  console.log(`🟢 匹配 463 数据的文件夹数: ${matching463.length}`)
  console.log(`❌ 不是 463 数据的文件夹数: ${not463.length}`)
  console.log('\n=== 不是 463 数据的文件夹详情 ===')
  let totalNon463Bytes = 0
  for (const item of not463) {
    let size = 0
    try {
      const folderPath = join(TARGET_DIR, item.folder)
      const files = await readdir(folderPath)
      for (const f of files) {
        const s = await stat(join(folderPath, f)).catch(() => null)
        if (s) size += s.size
      }
    } catch {}
    totalNon463Bytes += size

    let dbInfo = '未在数据库中找到记录'
    if (item.key) {
      const rows = runPsqlJson(
        `SELECT cs.quality_status, cs.receipt_state, t.task_status, mp.hand_result, qw.status as qw_status ` +
        `FROM capture_session cs ` +
        `LEFT JOIN annotation_task t ON cs.capture_session_id = t.capture_group_id ` +
        `LEFT JOIN camera_stream s ON cs.capture_session_id = s.capture_session_id ` +
        `LEFT JOIN ai_job mp ON s.clip_asset_id = mp.clip_asset_id AND mp.algorithm_key = 'MEDIAPIPE_HAND_LANDMARKER' ` +
        `LEFT JOIN ai_job qw ON s.clip_asset_id = qw.clip_asset_id AND qw.algorithm_key = 'QWEN3_5_ACTION' ` +
        `WHERE cs.capture_group_key = '${item.key}';`
      )
      if (rows.length > 0) {
        dbInfo = `DB状态: quality=${rows[0].quality_status}, receipt=${rows[0].receipt_state}, task=${rows[0].task_status || '无任务'}, hand=${rows[0].hand_result || '无'}, qwen=${rows[0].qw_status || '无'}`
      }
    }

    const sizeMb = (size / 1024 / 1024).toFixed(2)
    console.log(` - [${item.key || '未知'}] (${sizeMb} MB)`)
    console.log(`   文件夹: ${item.folder}`)
    console.log(`   ${dbInfo}`)
  }

  console.log(`\n💾 不是 463 数据的 8 个文件夹总占用: ${(totalNon463Bytes / 1024 / 1024).toFixed(2)} MB`)
}

inspect().catch(console.error)

