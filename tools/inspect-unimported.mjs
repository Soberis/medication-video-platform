import { readdir, stat } from 'node:fs/promises'
import { join } from 'node:path'
import { runPsqlJson } from './lib/db.mjs'
import { matchFolderPlatform } from './import-web-video-batch.mjs'

const TARGET_DIR = 'D:\\下载\\第一人称配药实操视频'

async function main() {
  const entries = await readdir(TARGET_DIR, { withFileTypes: true })
  const folders = entries.filter((e) => e.isDirectory()).map((e) => e.name)

  const dbRows = runPsqlJson(`SELECT capture_group_key FROM capture_session;`)
  const dbKeys = new Set(dbRows.map((r) => r.capture_group_key).filter(Boolean))

  const unimported = []
  for (const f of folders) {
    const match = matchFolderPlatform(f)
    const key = match ? `${match.platform}-${match.videoId}` : null
    if (!key || !dbKeys.has(key)) {
      unimported.push({ folder: f, key, match })
    }
  }

  console.log(`未在系统中的文件夹共有: ${unimported.length} 个`)
  console.log('前 40 个未入库文件夹:')
  unimported.slice(0, 40).forEach((u, i) => {
    console.log(` ${i + 1}. [${u.key || '未识别'}] ${u.folder}`)
  })
}

main().catch(console.error)
