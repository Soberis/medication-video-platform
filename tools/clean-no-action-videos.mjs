import { readdir, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { deleteCaptureGroupsBatch } from './clean-invalid-videos.mjs'

const TARGET_DIR = 'D:\\下载\\第一人称配药实操视频'
const TARGET_IDS = [
  '7554035270186962210',
  '7585166680914857253',
  '7661238670120917934',
  '7680169447429129651',
  '7680868299093699557',
]

const TARGET_SESSION_IDS = [
  'bfd65e3b-8412-4159-b363-2579ca96838a',
  'bf35a7a6-d8e2-40e2-a4f1-e79fb2088140',
  'a2db7f11-f0fe-41e4-8ad9-b6dfd802e102',
  'bee21cb8-b1a1-4620-9c06-7fedcfaa78f9',
  'd814b8bb-217d-40e2-a36e-6490fa821c4a',
]

async function main() {
  console.log('🧹 正在从 Postgres 数据库级联彻底清退 5 条非配药废品视频...')
  deleteCaptureGroupsBatch(TARGET_SESSION_IDS)
  console.log('✅ 数据库级联清理完毕！')

  console.log('🗑️ 正在物理清理磁盘对应的文件夹...')
  try {
    const entries = await readdir(TARGET_DIR, { withFileTypes: true })
    for (const entry of entries) {
      if (entry.isDirectory() && TARGET_IDS.some((id) => entry.name.includes(id))) {
        const fullPath = join(TARGET_DIR, entry.name)
        console.log(`  物理删除文件夹: ${entry.name}`)
        await rm(fullPath, { recursive: true, force: true })
      }
    }
    console.log('✅ 磁盘物理清理完毕！')
  } catch (err) {
    console.error('磁盘清理提示:', err.message)
  }
}

main().catch(console.error)
