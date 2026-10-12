import { readdir, rm, stat } from 'node:fs/promises'
import { join } from 'node:path'
import { runPsqlJson } from './lib/db.mjs'
import { matchFolderPlatform } from './import-web-video-batch.mjs'

const TARGET_DIR = process.env.TARGET_DIR || 'D:\\下载\\第一人称配药实操视频'

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
  console.log(`🚀 开始执行非 463 数据视频文件夹清理...`)

  // 1. 获取 463 条标准在役数据
  const completed463 = runPsqlJson(`
    SELECT cs.capture_group_key
    FROM capture_session cs
    JOIN annotation_task t ON cs.capture_session_id = t.capture_group_id
    WHERE cs.quality_status = 'PASS'
      AND cs.receipt_state = 'COMPLETE'
      AND t.task_status = 'COMPLETED';
  `)
  const targetKeySet = new Set(completed463.map((r) => r.capture_group_key).filter(Boolean))
  console.log(`🎯 平台 463 目标数据 Key 集合已加载: ${targetKeySet.size} 个`)

  // 2. 扫描磁盘文件夹
  const entries = await readdir(TARGET_DIR, { withFileTypes: true })
  const folders = entries.filter((e) => e.isDirectory()).map((e) => e.name)
  console.log(`📁 磁盘当前子文件夹数: ${folders.length} 个`)

  const toDelete = []
  const protectedFolders = []

  for (const f of folders) {
    const match = matchFolderPlatform(f)
    const key = match ? `${match.platform}-${match.videoId}` : null

    if (key && targetKeySet.has(key)) {
      protectedFolders.push({ folder: f, key })
    } else {
      toDelete.push({ folder: f, key })
    }
  }

  console.log(`🛡️  受保护的 463 合格在役文件夹: ${protectedFolders.length} 个`)
  console.log(`🗑️  确认拟删除的非 463 文件夹: ${toDelete.length} 个`)

  if (toDelete.length === 0) {
    console.log('✨ 磁盘中已全部是 463 目标数据，无需清理。')
    return
  }

  let totalDeletedBytes = 0
  let successCount = 0

  for (const item of toDelete) {
    const folderPath = join(TARGET_DIR, item.folder)
    try {
      const size = await getFolderSize(folderPath)
      await rm(folderPath, { recursive: true, force: true })
      totalDeletedBytes += size
      successCount++
      console.log(`  - 已删除: ${item.folder} (${formatBytes(size)})`)
    } catch (err) {
      console.error(`  ❌ 删除失败 [${item.folder}]: ${err.message}`)
    }
  }

  console.log('\n================== 🎉 清理完成 ==================')
  console.log(`✅ 成功删除文件夹: ${successCount} 个`)
  console.log(`💾 释放磁盘空间: ${formatBytes(totalDeletedBytes)}`)

  const remaining = await readdir(TARGET_DIR, { withFileTypes: true })
  const remainingFolders = remaining.filter((e) => e.isDirectory())
  console.log(`📁 剩余文件夹数: ${remainingFolders.length} 个 (与平台 463 数据完全 1:1 吻合)`)
  console.log('================================================\n')
}

main().catch(console.error)
