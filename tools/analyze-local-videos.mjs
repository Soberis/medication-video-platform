import { readdir, stat, readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { runPsqlJson } from './lib/db.mjs'
import { FOLDER_PATTERNS, matchFolderPlatform } from './import-web-video-batch.mjs'

const TARGET_DIR = 'D:\\下载\\第一人称配药实操视频'

// 快速计算文件部分哈希（前 1MB + 后 1MB + 大小，用于秒级快速精准排重）
async function getQuickFileFingerprint(filePath, fileSize) {
  if (fileSize === 0) return 'empty_file'
  if (fileSize < 2 * 1024 * 1024) {
    const buf = await readFile(filePath)
    return createHash('sha256').update(buf).digest('hex')
  }

  return new Promise((resolve, reject) => {
    const hash = createHash('sha256')
    hash.update(String(fileSize))
    const stream = createReadStream(filePath, { start: 0, end: 1024 * 1024 - 1 })
    stream.on('data', (chunk) => hash.update(chunk))
    stream.on('end', () => {
      const tailStream = createReadStream(filePath, { start: fileSize - 1024 * 1024, end: fileSize - 1 })
      tailStream.on('data', (chunk) => hash.update(chunk))
      tailStream.on('end', () => resolve(hash.digest('hex')))
      tailStream.on('error', reject)
    })
    stream.on('error', reject)
  })
}

async function main() {
  console.log(`🔍 正在扫描目标目录: ${TARGET_DIR} ...`)
  const entries = await readdir(TARGET_DIR, { withFileTypes: true })
  const folders = entries.filter((e) => e.isDirectory()).map((e) => e.name)
  console.log(`📂 找到 ${folders.length} 个子文件夹。`)

  // 1. 查询数据库中已存在的采集记录
  console.log('📡 正在查询数据库中已记录的视频状态...')
  const dbRows = runPsqlJson(`
    SELECT 
      cs.capture_session_id,
      cs.capture_group_key,
      cs.quality_status,
      cs.assembly_status,
      s.stream_status,
      mp.status as mp_status,
      mp.hand_result as mp_hand_result,
      (SELECT count(*) FROM action_candidate ac WHERE ac.capture_group_id = cs.capture_session_id) as action_count
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
  console.log(`🗄️  数据库中共有 ${dbRows.length} 条采集记录。`)

  // 2. 分析每一个文件夹
  const parsedItems = []
  const videoIdGroups = new Map()

  for (const folderName of folders) {
    const folderPath = join(TARGET_DIR, folderName)
    const match = matchFolderPlatform(folderName)
    const platform = match ? match.platform : 'unknown'
    const videoId = match ? match.videoId : folderName
    const groupKey = match ? `${platform}-${videoId}` : null

    // 查找文件夹内的 mp4 文件
    let mp4Name = null
    let mp4Size = 0
    let mp4Path = null
    try {
      const files = await readdir(folderPath)
      const mp4s = files.filter((f) => f.toLowerCase().endsWith('.mp4'))
      if (mp4s.length > 0) {
        // 优先取名字匹配的，否则取最大的
        let bestMp4 = mp4s[0]
        let maxSize = 0
        for (const f of mp4s) {
          const s = await stat(join(folderPath, f)).catch(() => null)
          if (s && s.size > maxSize) {
            maxSize = s.size
            bestMp4 = f
          }
        }
        mp4Name = bestMp4
        mp4Path = join(folderPath, bestMp4)
        mp4Size = maxSize
      }
    } catch {}

    const item = {
      folderName,
      folderPath,
      platform,
      videoId,
      groupKey,
      mp4Name,
      mp4Path,
      mp4Size,
      fingerprint: null,
      dbRecord: groupKey ? dbMap.get(groupKey) : null,
    }

    parsedItems.push(item)

    if (groupKey) {
      if (!videoIdGroups.has(groupKey)) {
        videoIdGroups.set(groupKey, [])
      }
      videoIdGroups.get(groupKey).push(item)
    }
  }

  // 3. 计算指纹进行内容查重（针对有 mp4 的项）
  console.log('⚡ 正在计算视频指纹以精确排重...')
  for (const item of parsedItems) {
    if (item.mp4Path && item.mp4Size > 0) {
      try {
        item.fingerprint = await getQuickFileFingerprint(item.mp4Path, item.mp4Size)
      } catch (err) {
        item.fingerprint = 'error'
      }
    }
  }

  const fingerprintGroups = new Map()
  for (const item of parsedItems) {
    if (item.fingerprint && item.fingerprint !== 'error' && item.fingerprint !== 'empty_file') {
      if (!fingerprintGroups.has(item.fingerprint)) {
        fingerprintGroups.set(item.fingerprint, [])
      }
      fingerprintGroups.get(item.fingerprint).push(item)
    }
  }

  // 4. 分类统计
  const missingOrEmpty = []
  const duplicates = []
  const failedInDb = []
  const notInDb = []
  const qualifiedInDb = []

  const processedFolders = new Set()

  // 4.1 缺失或空文件
  for (const item of parsedItems) {
    if (!item.mp4Name || item.mp4Size === 0) {
      missingOrEmpty.push({
        item,
        reason: !item.mp4Name ? '缺少 MP4 视频文件' : 'MP4 文件为 0 字节损坏',
      })
      processedFolders.add(item.folderName)
    }
  }

  // 4.2 基于 videoId 或内容指纹的重复项
  for (const [key, group] of videoIdGroups.entries()) {
    if (group.length > 1) {
      // 保持最大的一个作为主文件，其余作为重复项
      group.sort((a, b) => b.mp4Size - a.mp4Size)
      const primary = group[0]
      for (let i = 1; i < group.length; i++) {
        if (!processedFolders.has(group[i].folderName)) {
          duplicates.push({
            item: group[i],
            duplicateOf: primary,
            reason: `与主文件夹 [${primary.folderName}] 的视频ID [${key}] 重复`,
          })
          processedFolders.add(group[i].folderName)
        }
      }
    }
  }

  for (const [fp, group] of fingerprintGroups.entries()) {
    if (group.length > 1) {
      group.sort((a, b) => b.mp4Size - a.mp4Size)
      const primary = group[0]
      for (let i = 1; i < group.length; i++) {
        if (!processedFolders.has(group[i].folderName)) {
          duplicates.push({
            item: group[i],
            duplicateOf: primary,
            reason: `文件内容与 [${primary.folderName}] 100% 相同 (指纹哈希相同)`,
          })
          processedFolders.add(group[i].folderName)
        }
      }
    }
  }

  // 4.3 数据库中检查失败的视频
  for (const item of parsedItems) {
    if (processedFolders.has(item.folderName)) continue
    if (item.dbRecord) {
      const rec = item.dbRecord
      const isFailed =
        rec.stream_status === 'INGEST_FAILED' ||
        rec.quality_status === 'REJECT' ||
        rec.mp_status === 'FAILED' ||
        rec.mp_hand_result === 'NO_HAND_DETECTED'

      if (isFailed) {
        let reason = '未通过系统检查'
        if (rec.stream_status === 'INGEST_FAILED') reason = '媒体导入失败 (INGEST_FAILED)'
        else if (rec.mp_hand_result === 'NO_HAND_DETECTED') reason = '未检出人手 (NO_HAND_DETECTED)'
        else if (rec.mp_status === 'FAILED') reason = 'AI检测失败 (FAILED)'
        else if (rec.quality_status === 'REJECT') reason = '质检不合格 (REJECT)'

        failedInDb.push({ item, reason, dbRecord: rec })
        processedFolders.add(item.folderName)
      } else {
        qualifiedInDb.push(item)
      }
    } else {
      notInDb.push(item)
    }
  }

  // 计算大小
  const formatBytes = (bytes) => {
    if (!bytes) return '0 B'
    const k = 1024
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
    const i = Math.floor(Math.log(bytes) / Math.log(k))
    return `${(bytes / Math.pow(k, i)).toFixed(2)} ${sizes[i]}`
  }

  const sumSize = (list) => list.reduce((acc, curr) => acc + (curr.item?.mp4Size || curr.mp4Size || 0), 0)

  console.log('\n================== 📊 本地视频目录分析报告 ==================')
  console.log(`📁 扫描总文件夹数: ${parsedItems.length}`)
  console.log(`🟢 合格且已在平台就绪: ${qualifiedInDb.length} 个 (占用空间: ${formatBytes(sumSize(qualifiedInDb))})`)
  console.log(`⚪ 本地存在但未导入平台: ${notInDb.length} 个 (占用空间: ${formatBytes(sumSize(notInDb))})`)
  console.log(`------------------------------------------------------------`)
  console.log(`❌ 拟清理候选 (可安全删除):`)
  console.log(`  1. 损坏/缺失视频: ${missingOrEmpty.length} 个 (占用空间: ${formatBytes(sumSize(missingOrEmpty))})`)
  console.log(`  2. 完全重复的视频: ${duplicates.length} 个 (占用空间: ${formatBytes(sumSize(duplicates))})`)
  console.log(`  3. 平台检查未通过: ${failedInDb.length} 个 (占用空间: ${formatBytes(sumSize(failedInDb))})`)
  const totalToDelete = missingOrEmpty.length + duplicates.length + failedInDb.length
  const totalDeleteSize = sumSize(missingOrEmpty) + sumSize(duplicates) + sumSize(failedInDb)
  console.log(`  👉 拟清理总数: ${totalToDelete} 个文件夹`)
  console.log(`  👉 预计可释放空间: ${formatBytes(totalDeleteSize)}`)
  console.log('============================================================\n')

  // 输出清单供人工审查
  if (missingOrEmpty.length > 0) {
    console.log('【1. 损坏/缺失视频清单】:')
    for (const d of missingOrEmpty) {
      console.log(`  - ${d.item.folderName} (${d.reason})`)
    }
    console.log('')
  }

  if (duplicates.length > 0) {
    console.log('【2. 重复视频清单】:')
    for (const d of duplicates) {
      console.log(`  - ${d.item.folderName} (${formatBytes(d.item.mp4Size)}) -> ${d.reason}`)
    }
    console.log('')
  }

  if (failedInDb.length > 0) {
    console.log('【3. 平台检查未通过视频清单】:')
    for (const d of failedInDb) {
      console.log(`  - ${d.item.folderName} (${formatBytes(d.item.mp4Size)}) -> 原因: ${d.reason}`)
    }
    console.log('')
  }
}

main().catch(console.error)
