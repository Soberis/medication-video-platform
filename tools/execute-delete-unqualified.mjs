import { readdir, rm, stat } from 'node:fs/promises'
import { join } from 'node:path'
import { runPsqlJson } from './lib/db.mjs'
import { matchFolderPlatform } from './import-web-video-batch.mjs'
import { deleteCaptureGroup } from './clean-invalid-videos.mjs'

const TARGET_DIR = 'D:\\下载\\第一人称配药实操视频'

async function getFolderSize(dirPath) {
  let total = 0
  try {
    const files = await readdir(dirPath, { withFileTypes: true })
    for (const file of files) {
      const fullPath = join(dirPath, file.name)
      if (file.isDirectory()) {
        total += await getFolderSize(fullPath)
      } else {
        const s = await stat(fullPath).catch(() => null)
        if (s) total += s.size
      }
    }
  } catch {}
  return total
}

function formatBytes(bytes) {
  if (!bytes) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${(bytes / Math.pow(k, i)).toFixed(2)} ${sizes[i]}`
}

async function main() {
  console.log(`🚀 开始安全执行磁盘视频清理: ${TARGET_DIR}`)

  // 1. 获取所有子文件夹
  const entries = await readdir(TARGET_DIR, { withFileTypes: true })
  const folders = entries.filter((e) => e.isDirectory()).map((e) => e.name)
  console.log(`📂 当前扫描到子文件夹: ${folders.length} 个`)

  // 2. 查询数据库中在役合法的采集记录
  const dbRows = runPsqlJson(`
    SELECT 
      cs.capture_session_id,
      cs.capture_group_key,
      cs.quality_status,
      s.stream_status,
      mp.status as mp_status,
      mp.hand_result as mp_hand_result
    FROM capture_session cs
    LEFT JOIN camera_stream s ON cs.capture_session_id = s.capture_session_id
    LEFT JOIN ai_job mp ON s.clip_asset_id = mp.clip_asset_id AND mp.algorithm_key = 'MEDIAPIPE_HAND_LANDMARKER';
  `)

  const dbMap = new Map()
  for (const row of dbRows) {
    if (row.capture_group_key) {
      dbMap.set(row.capture_group_key, row)
    }
  }

  // 3. 严格分类
  const toDelete = []
  const protectedFolders = []

  for (const folder of folders) {
    const match = matchFolderPlatform(folder)
    const groupKey = match ? `${match.platform}-${match.videoId}` : null
    const dbRec = groupKey ? dbMap.get(groupKey) : null

    if (dbRec) {
      const isFailed =
        dbRec.stream_status === 'INGEST_FAILED' ||
        dbRec.quality_status === 'REJECT' ||
        dbRec.mp_status === 'FAILED' ||
        dbRec.mp_hand_result === 'NO_HAND_DETECTED'

      if (isFailed) {
        toDelete.push({
          folder,
          reason: `平台检查未通过 (${dbRec.stream_status || dbRec.mp_status || dbRec.quality_status})`,
          groupKey,
          isDbFailed: true,
        })
      } else {
        protectedFolders.push(folder)
      }
    } else {
      toDelete.push({
        folder,
        reason: '未入库/历史已剔除废弃视频',
        groupKey,
        isDbFailed: false,
      })
    }
  }

  console.log(`🛡️  受保护的合格在役视频: ${protectedFolders.length} 个`)
  console.log(`🗑️  确认拟清理的废弃文件夹: ${toDelete.length} 个`)

  if (toDelete.length === 0) {
    console.log('✨ 没有需要清理的文件，目录已是最优状态！')
    return
  }

  // 4. 执行删除与空间统计
  let totalDeletedBytes = 0
  let successCount = 0
  let failCount = 0

  for (let i = 0; i < toDelete.length; i++) {
    const item = toDelete[i]
    const folderPath = join(TARGET_DIR, item.folder)
    try {
      const size = await getFolderSize(folderPath)
      await rm(folderPath, { recursive: true, force: true })
      totalDeletedBytes += size
      successCount++

      // 如果属于在 DB 中失败的，同时清理 DB 残留记录
      if (item.isDbFailed && item.groupKey) {
        try {
          deleteCaptureGroup(item.groupKey)
        } catch (e) {
          // 忽略 DB 清理小异常
        }
      }

      if ((i + 1) % 20 === 0 || i === toDelete.length - 1) {
        console.log(`进度: [${i + 1}/${toDelete.length}] 已删除 ${successCount} 个文件夹...`)
      }
    } catch (err) {
      console.error(`❌ 删除失败 [${item.folder}]: ${err.message}`)
      failCount++
    }
  }

  console.log('\n================== 🎉 清理完成报告 ==================')
  console.log(`✅ 成功删除文件夹: ${successCount} 个`)
  if (failCount > 0) {
    console.log(`⚠️  删除失败文件夹: ${failCount} 个`)
  }
  console.log(`💾 释放磁盘空间: ${formatBytes(totalDeletedBytes)}`)

  const remaining = await readdir(TARGET_DIR, { withFileTypes: true })
  const remainingFolders = remaining.filter((e) => e.isDirectory())
  console.log(`📁 剩余合格在役文件夹: ${remainingFolders.length} 个 (全部安全保留)`)
  console.log('=====================================================\n')
}

main().catch(console.error)
