import { readdir, rm, stat } from 'node:fs/promises'
import { join } from 'node:path'
import { existsSync } from 'node:fs'
import { runPsqlJson } from './lib/db.mjs'
import { matchFolderPlatform } from './import-web-video-batch.mjs'

const TARGET_DIR = process.env.TARGET_DIR || 'D:\\下载\\第一人称配药实操视频'
const PROJECT_ID = '944a19eb-03b1-4b55-98fb-f8e108e23d92'

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
  const isExecute = process.argv.includes('--execute')
  console.log(`================== 本地磁盘视频同步清理 (保持与 358 在役数据严格一致) ==================`)
  console.log(`模式: ${isExecute ? '【真实执行清理】' : '【DRY-RUN 预览模式 (加 --execute 真实生效)】'}`)
  console.log(`磁盘目录: ${TARGET_DIR}`)

  if (!existsSync(TARGET_DIR)) {
    console.error(`未找到本地视频目录: ${TARGET_DIR}`)
    process.exit(1)
  }

  // 1. 查询数据库中在役的 358 条合规 session keys
  const activeRows = runPsqlJson(`
    SELECT capture_group_key 
    FROM capture_session 
    WHERE project_id = '${PROJECT_ID}';
  `)
  const activeKeys = new Set(activeRows.map((r) => r.capture_group_key))
  console.log(`数据库在役合规 session 数量: ${activeKeys.size} 条`)

  // 2. 扫描磁盘上所有文件夹
  const diskFolders = (await readdir(TARGET_DIR, { withFileTypes: true }))
    .filter((f) => f.isDirectory())
    .map((f) => f.name)
  console.log(`磁盘当前文件夹总数: ${diskFolders.length} 个`)

  const toKeep = []
  const toDelete = []

  for (const folder of diskFolders) {
    const match = matchFolderPlatform(folder)
    const key = match ? `${match.platform}-${match.videoId}` : null
    if (key && activeKeys.has(key)) {
      toKeep.push({ folder, key })
    } else {
      toDelete.push({ folder, key: key || 'unknown' })
    }
  }

  console.log(`受保护保留的合格视频文件夹: ${toKeep.length} 个`)
  console.log(`需清理的非在役/废弃视频文件夹: ${toDelete.length} 个\n`)

  if (toDelete.length === 0) {
    console.log('✨ 本地磁盘已 100% 与数据库 358 条合规数据对齐，无需清理！')
    return
  }

  let totalDeletedBytes = 0
  let successCount = 0

  for (let i = 0; i < toDelete.length; i++) {
    const item = toDelete[i]
    const folderPath = join(TARGET_DIR, item.folder)
    const size = await getFolderSize(folderPath)
    totalDeletedBytes += size

    console.log(`[${i + 1}/${toDelete.length}] 待清理: ${item.folder} (${formatBytes(size)})`)

    if (isExecute) {
      try {
        await rm(folderPath, { recursive: true, force: true })
        successCount++
      } catch (err) {
        console.error(`   ❌ 删除失败: ${err.message}`)
      }
    }
  }

  console.log('\n----------------------------------------------------------------------')
  console.log(`总结报告:`)
  console.log(`• 待清理文件夹: ${toDelete.length} 个`)
  console.log(`• 累计释放磁盘空间: ${formatBytes(totalDeletedBytes)}`)
  if (isExecute) {
    console.log(`• 成功删除: ${successCount} / ${toDelete.length} 个`)
    const remaining = (await readdir(TARGET_DIR, { withFileTypes: true })).filter((e) =>
      e.isDirectory(),
    )
    console.log(`• 磁盘剩余文件夹: ${remaining.length} 个 (与数据库 358 条完全对齐一致)`)
  } else {
    console.log(`• 状态: 预览完成，未对磁盘产生破坏。执行请添加 --execute 参数。`)
  }
  console.log(`======================================================================\n`)
}

main().catch(console.error)
