import { readdir, rm, stat } from 'node:fs/promises'
import { join } from 'node:path'
import { readFileSync, existsSync } from 'node:fs'
import { matchFolderPlatform } from './import-web-video-batch.mjs'
import { deleteCaptureGroup } from './clean-invalid-videos.mjs'

const TARGET_DIR = process.env.TARGET_DIR || 'D:\\下载\\第一人称配药实操视频'
const JSON_PATH = 'exports/reports/unqualified_40_items.json'

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
  console.log(`================== 40 条不合规配药视频/数据清理任务 ==================`)
  console.log(`模式: ${isExecute ? '【真实执行清理】' : '【DRY-RUN 预览模式 (加 --execute 真实生效)】'}`)
  console.log(`本地视频目录: ${TARGET_DIR}`)

  if (!existsSync(JSON_PATH)) {
    console.error(`未找到清单文件: ${JSON_PATH}`)
    process.exit(1)
  }

  const items = JSON.parse(readFileSync(JSON_PATH, 'utf-8'))
  console.log(`待清理清单包含: ${items.length} 条不合规记录\n`)

  const itemKeys = new Set(items.map((i) => i.group_key))

  // 匹配磁盘目录
  let diskEntries = []
  if (existsSync(TARGET_DIR)) {
    const files = await readdir(TARGET_DIR, { withFileTypes: true })
    diskEntries = files.filter((e) => e.isDirectory()).map((e) => e.name)
  }

  const diskMatchMap = new Map()
  for (const folder of diskEntries) {
    const match = matchFolderPlatform(folder)
    const key = match ? `${match.platform}-${match.videoId}` : null
    if (key && itemKeys.has(key)) {
      diskMatchMap.set(key, folder)
    }
  }

  console.log(`磁盘匹配到对应实体视频文件夹: ${diskMatchMap.size} 个`)
  console.log('----------------------------------------------------------------------')

  let totalDiskBytes = 0
  let dbSuccess = 0
  let diskSuccess = 0

  for (let i = 0; i < items.length; i++) {
    const item = items[i]
    const matchedFolder = diskMatchMap.get(item.group_key)
    console.log(
      `[${i + 1}/${items.length}] ${item.demo} | ${item.group_key} | ${item.duration}s | 双手: ${item.both_pct}% | 原因: ${item.reasons.join(', ')}`,
    )
    if (matchedFolder) {
      const folderPath = join(TARGET_DIR, matchedFolder)
      const size = await getFolderSize(folderPath)
      totalDiskBytes += size
      console.log(`   └─ 对应磁盘目录: ${matchedFolder} (${formatBytes(size)})`)

      if (isExecute) {
        try {
          await rm(folderPath, { recursive: true, force: true })
          diskSuccess++
        } catch (err) {
          console.error(`      ❌ 磁盘删除失败: ${err.message}`)
        }
      }
    } else {
      console.log(`   └─ 磁盘目录: (未在本地检测到或此前已清理)`)
    }

    if (isExecute) {
      try {
        deleteCaptureGroup(item.group_key)
        dbSuccess++
      } catch (err) {
        console.error(`      ❌ 数据库记录清理失败: ${err.message}`)
      }
    }
  }

  console.log('----------------------------------------------------------------------')
  console.log(`总结报告:`)
  console.log(`• 不合规样本总数: ${items.length} 条`)
  console.log(`• 涉及磁盘文件大小: ${formatBytes(totalDiskBytes)}`)
  if (isExecute) {
    console.log(`• 数据库清理完成: ${dbSuccess} / ${items.length} 条`)
    console.log(`• 磁盘文件夹删除完成: ${diskSuccess} / ${diskMatchMap.size} 个`)
  } else {
    console.log(`• 状态: 预览完成，未对数据库或磁盘产生破坏。执行时请添加 --execute 参数。`)
  }
  console.log(`======================================================================\n`)
}

main().catch(console.error)
