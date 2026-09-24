import { readdir, readFile, access, stat } from 'node:fs/promises'
import { constants } from 'node:fs'
import { resolve, join } from 'node:path'
import { homedir } from 'node:os'
import { parseArgs } from 'node:util'
import { randomUUID } from 'node:crypto'
import { execFile } from 'node:child_process'
import { pathToFileURL } from 'node:url'

import { SyntheticUploadClient, PlatformApiError } from './synthetic-upload-client/upload-client.mjs'
import { SyntheticCaptureGroupWorkflowClient } from './synthetic-capture-group-client/workflow-client.mjs'
import { resolveProject, requestMediaProbes } from './import-local-multicam.mjs'

const DEFAULT_BASE_URL = 'http://localhost:8080'
const DEFAULT_WEB_URL = 'http://localhost:5173'
const DEFAULT_DEV_TOKEN = 'synthetic-local-bearer-token-do-not-reuse'
const DEFAULT_ADMIN_TOKEN = 'synthetic-local-admin-bearer-token-do-not-reuse'
const DEFAULT_CAMERA_SET_KEY = 'web-single-camera'
const DEFAULT_LAYOUT_VERSION = 'web-v1'

function openBrowser(url) {
  const parsed = new URL(url)
  if (!['http:', 'https:'].includes(parsed.protocol)) {
    throw new Error('浏览器链接必须使用 HTTP 或 HTTPS。')
  }
  const command = process.platform === 'win32'
    ? ['rundll32.exe', ['url.dll,FileProtocolHandler', parsed.href]]
    : process.platform === 'darwin' ? ['open', [parsed.href]] : ['xdg-open', [parsed.href]]
  execFile(command[0], command[1], { windowsHide: true }, (error) => {
    if (error) console.warn('未能自动打开浏览器，请使用上方链接。')
  })
}

export const FOLDER_PATTERNS = [
  { platform: 'bilibili', regex: /^(?:bilibili_)?(BV[0-9A-Za-z]{10})(?:\s+-\s+.*|\s*[-_]\s+.*)?$/ },
  { platform: 'douyin', regex: /^(?:douyin_)?([0-9]{15,22})(?:\s+-\s+.*|\s*[-_]\s+.*)?$/ },
  { platform: 'xhs', regex: /^(?:xhs_)?([0-9a-f]{24})(?:\s+-\s+.*|\s*[-_]\s+.*)?$/ },
  { platform: 'youtube', regex: /^(?:youtube_([0-9A-Za-z_-]{11})(?:\s+-\s+.*|\s*[-_]\s+.*)?|([0-9A-Za-z_-]{11}))$/ },
]

export function matchFolderPlatform(folderName) {
  for (const pat of FOLDER_PATTERNS) {
    const match = folderName.match(pat.regex)
    if (match) {
      const videoId = match[1] || match[2]
      return { platform: pat.platform, videoId }
    }
  }
  return null
}

export function formatDuration(duration) {
  if (duration === null || duration === undefined || duration === '') return ''
  if (typeof duration === 'string' && duration.includes(':')) return duration
  const num = Number.parseFloat(duration)
  if (Number.isNaN(num) || num <= 0) return typeof duration === 'string' ? duration : ''
  const mins = Math.floor(num / 60)
  const secs = Math.floor(num % 60)
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`
}

export function extractAuthorFromFolderName(folderName) {
  const parts = folderName.split(/\s+-\s+/)
  if (parts.length >= 3) {
    const candidate = parts[parts.length - 1].trim()
    if (candidate && candidate !== 'untitled') {
      return candidate
    }
  }
  return null
}

export async function scanWebVideos(targetDir) {
  const searchDirs = targetDir
    ? [resolve(targetDir)]
    : [
        process.env.WEB_VIDEO_DIR ? resolve(process.env.WEB_VIDEO_DIR) : null,
        'D:\\下载\\第一人称配药实操视频',
        'D:\\Downloads\\第一人称配药实操视频',
        'D:\\第一人称配药实操视频',
        join(homedir(), 'Downloads', '第一人称配药实操视频'),
        join(homedir(), 'Downloads', '配药实操视频'),
        join(homedir(), 'Downloads', 'B站视频'),
      ].filter(Boolean)

  let baseDir = null
  for (const dir of searchDirs) {
    try {
      await access(dir, constants.R_OK)
      // 若不是显式指定的 targetDir，需进一步确认目录下确实存在子目录，避免被残留的空目录拦截
      if (!targetDir) {
        const testEntries = await readdir(dir, { withFileTypes: true })
        if (testEntries.filter((e) => e.isDirectory()).length === 0) {
          continue
        }
      }
      baseDir = dir
      break
    } catch {
      // 检查下一个备选目录
    }
  }

  if (!baseDir) {
    throw new Error(`找不到视频目录，请确认 "D:\\下载\\第一人称配药实操视频" 或 "Downloads/第一人称配药实操视频" 存在或通过 --dir 指定。`)
  }

  const entries = await readdir(baseDir, { withFileTypes: true })
  const folders = entries.filter((e) => e.isDirectory())

  const videos = []
  const skipped = []

  for (const folder of folders) {
    const matched = matchFolderPlatform(folder.name)
    if (!matched) {
      skipped.push({ folderName: folder.name, reason: '未匹配到支持的平台命名规范' })
      continue
    }

    const { platform, videoId } = matched
    const folderPath = join(baseDir, folder.name)
    let folderFiles = []
    try {
      folderFiles = await readdir(folderPath)
    } catch {
      skipped.push({ folderName: folder.name, platform, videoId, reason: '无法读取文件夹内容' })
      continue
    }

    // 过滤有效 mp4 文件（排除 .part.mp4、.temp.mp4 等临时未完成文件）
    const validMp4s = folderFiles.filter(
      (f) => f.endsWith('.mp4') && !f.endsWith('.part') && !f.endsWith('.temp') && !f.includes('.part.'),
    )

    if (validMp4s.length === 0) {
      const hasPart = folderFiles.some((f) => f.endsWith('.part') || f.includes('.part.'))
      skipped.push({
        folderName: folder.name,
        platform,
        videoId,
        reason: hasPart ? '下载未完成 (仅存在 .part 分片文件)' : '文件夹内无 MP4 视频文件',
      })
      continue
    }

    // 寻找优先 mp4 文件：优先 videoId.mp4，次之首个有效 mp4
    const mp4File = validMp4s.find((f) => f === `${videoId}.mp4`) || validMp4s[0]
    const videoPath = join(folderPath, mp4File)

    try {
      const fileStat = await stat(videoPath)
      if (fileStat.size === 0) {
        skipped.push({ folderName: folder.name, platform, videoId, reason: '视频文件大小为 0 字节 (损坏或空文件)' })
        continue
      }
    } catch {
      skipped.push({ folderName: folder.name, platform, videoId, reason: '无法获取视频文件元信息' })
      continue
    }

    // 寻找 info.json
    const infoFile = folderFiles.find((f) => f.endsWith('.info.json') || f.endsWith('info.json'))
    const infoPath = infoFile ? join(folderPath, infoFile) : null

    let meta = {
      title: folder.name,
      author: extractAuthorFromFolderName(folder.name) || platform,
      duration: '',
    }

    if (infoPath) {
      try {
        const rawInfo = await readFile(infoPath, 'utf-8')
        const parsed = JSON.parse(rawInfo)
        const parsedAuthor = parsed.owner?.name || parsed.uploader || parsed.author || parsed.channel || null
        meta = {
          title: parsed.title || folder.name,
          author: parsedAuthor || extractAuthorFromFolderName(folder.name) || platform,
          duration: parsed.duration_string || formatDuration(parsed.duration),
        }
      } catch {
        // 忽略解析失败，使用默认抽取元数据
      }
    }

    videos.push({
      platform,
      videoId,
      groupKey: `${platform}-${videoId}`,
      videoPath,
      folderName: folder.name,
      ...meta,
    })
  }

  return { baseDir, videos, skipped }
}

export async function fetchExistingKeys(baseUrl, token, projectId) {
  const existing = new Set()
  let cursor = null
  try {
    do {
      const url = new URL(`${baseUrl.replace(/\/$/, '')}/api/v1/projects/${projectId}/capture-groups`)
      url.searchParams.set('limit', '100')
      if (cursor) {
        url.searchParams.set('cursor', cursor)
      }
      const res = await fetch(url, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!res.ok) break
      const data = await res.json()
      for (const item of data.items || []) {
        if (item.capture_group_key) {
          existing.add(item.capture_group_key)
        }
      }
      cursor = data.next_cursor
    } while (cursor)
  } catch {
    // 读取失败不阻断流程
  }
  return existing
}

export async function main() {
  const { values, positionals } = parseArgs({
    options: {
      dir: { type: 'string' },
      project: { type: 'string' },
      limit: { type: 'string' },
      baseUrl: { type: 'string', default: process.env.PLATFORM_BASE_URL || DEFAULT_BASE_URL },
      webUrl: { type: 'string', default: process.env.PLATFORM_WEB_URL || DEFAULT_WEB_URL },
      token: { type: 'string', default: process.env.DEV_BEARER_TOKEN || DEFAULT_DEV_TOKEN },
      adminToken: { type: 'string', default: process.env.DEV_ADMIN_BEARER_TOKEN || DEFAULT_ADMIN_TOKEN },
      'camera-set-key': { type: 'string', default: DEFAULT_CAMERA_SET_KEY },
      cameraSetKey: { type: 'string' },
      'dry-run': { type: 'boolean', default: false },
      dryRun: { type: 'boolean', default: false },
      open: { type: 'boolean', default: true },
      'no-open': { type: 'boolean', default: false },
    },
    allowPositionals: true,
  })

  const isDryRun = Boolean(values['dry-run'] || values.dryRun)
  const cameraSetKey = values['camera-set-key'] || values.cameraSetKey || DEFAULT_CAMERA_SET_KEY
  const requestedProject = positionals[0] || values.project || null
  const limit = values.limit ? parseInt(values.limit, 10) : Infinity

  console.log('\n======================================================')
  console.log('   多源网络公开视频批量导入工具 (Web Video Batch Importer)')
  console.log('   支持平台: Bilibili · 抖音 · 小红书 · YouTube')
  console.log('======================================================')

  const { baseDir, videos, skipped } = await scanWebVideos(values.dir)
  console.log(`视频扫描目录 : ${baseDir}`)

  const platformCounts = videos.reduce((acc, v) => {
    acc[v.platform] = (acc[v.platform] || 0) + 1
    return acc
  }, {})

  console.log(`有效视频总数 : 共识别到 ${videos.length} 部可导入视频:`)
  console.log(`  - 抖音 (Douyin)    : ${platformCounts.douyin || 0} 部`)
  console.log(`  - YouTube          : ${platformCounts.youtube || 0} 部`)
  console.log(`  - 小红书 (XHS)     : ${platformCounts.xhs || 0} 部`)
  console.log(`  - 哔哩哔哩 (Bilibili): ${platformCounts.bilibili || 0} 部`)

  if (skipped.length > 0) {
    console.log(`跳过/暂未就绪: 共 ${skipped.length} 个目录 (其中未完成下载: ${skipped.filter((s) => s.reason.includes('未完成')).length} 个, 空目录: ${skipped.filter((s) => s.reason.includes('无 MP4')).length} 个)`)
  }

  if (videos.length === 0) {
    console.log('未检测到任何待上传视频，程序结束。')
    return
  }

  const targetProject = await resolveProject(values.baseUrl, values.adminToken, requestedProject)
  const targetProjectId = targetProject.project_id
  console.log(`目标项目     : ${targetProject.display_name} (ID: ${targetProjectId})`)
  console.log(`API 端点     : ${values.baseUrl}`)

  if (isDryRun) {
    console.log('\n[Dry Run 模式] 仅执行本地文件校验与平台去重检测，不向平台上传任何数据。')
  }

  const uploadClient = new SyntheticUploadClient({
    baseUrl: values.baseUrl,
    projectId: targetProjectId,
    bearerToken: values.adminToken,
  })

  const workflow = new SyntheticCaptureGroupWorkflowClient({
    baseUrl: values.baseUrl,
    projectId: targetProjectId,
    bearerToken: values.adminToken,
    adminBearerToken: values.adminToken,
  })

  console.log('\n1. 检查单机位通道配置...')
  let cameraSet = null
  if (!isDryRun) {
    cameraSet = await uploadClient.provisionSyntheticCameraSet({
      cameraNumbers: [1],
      cameraSetKey,
      layoutVersion: DEFAULT_LAYOUT_VERSION,
    })
    console.log(`   单机位通道配置就绪 (Key: ${cameraSetKey})`)
  } else {
    console.log(`   [Dry Run] 单机位通道配置略过 (预期 Key: ${cameraSetKey})`)
  }

  console.log('\n2. 检查平台中已上传的视频记录...')
  const existingKeys = await fetchExistingKeys(values.baseUrl, values.token, targetProjectId)
  console.log(`   平台现有已登记视频数: ${existingKeys.size} 个`)

  const toUpload = videos.filter((v) => !existingKeys.has(v.groupKey))
  console.log(`   本次待上传视频数    : ${toUpload.length} 个 (已跳过平台重复: ${videos.length - toUpload.length} 个)`)

  const batchList = toUpload.slice(0, limit)
  if (limit < toUpload.length) {
    console.log(`   [注意] 当前限制上传前 ${limit} 部视频用于快速验证`)
  }

  if (isDryRun) {
    console.log('\n======================================================')
    console.log('            Dry Run 演练完成 (未执行上传)')
    console.log('======================================================')
    console.log(`扫描就绪: ${videos.length} 部，平台已存在: ${videos.length - toUpload.length} 部，预计需上传: ${toUpload.length} 部`)
    return
  }

  let successCount = 0
  let failCount = 0
  let firstGroupUrl = null

  for (let i = 0; i < batchList.length; i++) {
    const item = batchList[i]
    const idxStr = `[${i + 1}/${batchList.length}]`
    const groupKey = item.groupKey
    const sourceBatchId = `batch-${item.platform}-${randomUUID()}`

    console.log(`\n${idxStr} 正在读取并上传 [${item.platform}]: ${item.videoId}`)
    console.log(`  标题: ${item.title}`)
    console.log(`  作者: ${item.author} | 时长: ${item.duration || '未知'}`)

    try {
      const bytes = await readFile(item.videoPath)
      const sizeMb = (bytes.length / (1024 * 1024)).toFixed(2)
      console.log(`  体积: ${sizeMb} MB，正在执行分片上传...`)

      const uploaded = await uploadClient.uploadSyntheticCaptureGroup({
        cameraSet,
        captureGroupKey: groupKey,
        expectedCameraNumbers: [1],
        presentCameraNumbers: [1],
        videoBytesByCamera: new Map([[1, bytes]]),
        capturedAt: new Date().toISOString(),
        deviceId: groupKey,
        sourceBatchId,
      })

      await workflow.finalizeCaptureGroup(uploaded.captureGroupId)
      await requestMediaProbes(workflow, uploaded.views)

      successCount++
      const detailUrl = `${values.webUrl.replace(/\/$/, '')}/projects/${targetProjectId}/capture-groups/${uploaded.captureGroupId}`
      if (!firstGroupUrl) firstGroupUrl = detailUrl
      console.log(`  ✅ 成功入库！CaptureGroup ID: ${uploaded.captureGroupId}`)
    } catch (err) {
      if (err.status === 409 || String(err.message).includes('409') || String(err.message).includes('CONFLICT')) {
        console.log(`  ℹ️  平台已存在该视频记录 (跳过重复上传)`)
        successCount++
      } else {
        failCount++
        console.error(`  ❌ 上传失败: ${err.message || String(err)}`)
      }
    }
  }

  console.log('\n======================================================')
  console.log('            多源网络视频批量导入完成！')
  console.log('======================================================')
  console.log(`本次执行结果 : 成功 ${successCount} 个，失败 ${failCount} 个`)
  console.log(`项目概览链接 : ${values.webUrl.replace(/\/$/, '')}/projects/${targetProjectId}`)

  if (firstGroupUrl && values.open && !values['no-open']) {
    openBrowser(firstGroupUrl)
    console.log('🚀 已自动在浏览器中打开首个导入视频的标注页面！\n')
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error) => {
    console.error('\n❌ 批量导入发生未捕获异常:')
    if (error instanceof PlatformApiError) {
      console.error(`HTTP ${error.status} (${error.errorCode}): ${error.message}`)
      if (error.requestId) console.error(`Request ID: ${error.requestId}`)
    } else {
      console.error(error.message || String(error))
    }
    process.exitCode = 1
  })
}
