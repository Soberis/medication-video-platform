import { readFile, access, readdir } from 'node:fs/promises'
import { constants } from 'node:fs'
import { basename, resolve, join } from 'node:path'
import { homedir } from 'node:os'
import { parseArgs } from 'node:util'
import { randomUUID } from 'node:crypto'
import { execSync } from 'node:child_process'

import { SyntheticUploadClient, normalizeCameraNumbers, PlatformApiError } from './synthetic-upload-client/upload-client.mjs'
import { SyntheticCaptureGroupWorkflowClient } from './synthetic-capture-group-client/workflow-client.mjs'

const DEFAULT_BASE_URL = 'http://localhost:8080'
const DEFAULT_WEB_URL = 'http://localhost:5173'
const DEFAULT_PROJECT_ID = '00000000-0000-0000-0000-000000000001'
const DEFAULT_DEV_TOKEN = 'synthetic-local-bearer-token-do-not-reuse'
const DEFAULT_ADMIN_TOKEN = 'synthetic-local-admin-bearer-token-do-not-reuse'

function syncLocalSyntheticMemberships() {
  try {
    execSync(
      'docker exec medication-video-platform-postgres-1 psql -U medication_local -d medication_platform -c "' +
        'INSERT INTO membership (membership_id, project_id, actor_id, role, status, created_by, created_at, updated_by, updated_at, row_version) ' +
        "SELECT gen_random_uuid(), p.project_id, 'local-synthetic-admin', 'ADMIN', 'ACTIVE', 'local-synthetic-bootstrap', now(), 'local-synthetic-bootstrap', now(), 0 " +
        'FROM project p ' +
        "WHERE p.status = 'ACTIVE' AND NOT EXISTS ( " +
        "  SELECT 1 FROM membership m WHERE m.project_id = p.project_id AND m.actor_id = 'local-synthetic-admin' " +
        '); ' +
        'INSERT INTO membership (membership_id, project_id, actor_id, role, status, created_by, created_at, updated_by, updated_at, row_version) ' +
        "SELECT gen_random_uuid(), p.project_id, 'local-synthetic-uploader', 'MEMBER', 'ACTIVE', 'local-synthetic-bootstrap', now(), 'local-synthetic-bootstrap', now(), 0 " +
        'FROM project p ' +
        "WHERE p.status = 'ACTIVE' AND NOT EXISTS ( " +
        "  SELECT 1 FROM membership m WHERE m.project_id = p.project_id AND m.actor_id = 'local-synthetic-uploader' " +
        ');"',
      { stdio: 'ignore', windowsHide: true },
    )
  } catch {
    // 忽略 Docker 不可用或非本地容器运行的情况
  }
}

async function autoDetectVideos(customDir) {
  const dir = customDir ? resolve(customDir) : join(homedir(), 'Downloads')
  try {
    const entries = await readdir(dir)
    // 1. 优先匹配 video1.mp4, video2.mp4 ... video5.mp4
    const numbered = []
    for (let i = 1; i <= 5; i++) {
      const match = entries.find((name) => name.toLowerCase() === `video${i}.mp4`)
      if (match) {
        numbered.push({ cameraNumber: i, path: join(dir, match) })
      }
    }
    if (numbered.length > 0) {
      return { dir, videos: numbered }
    }

    // 2. 匹配以 video 开头的 .mp4 视频
    const anyVideo = entries
      .filter((name) => /^video.*\.mp4$/i.test(name))
      .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
      .slice(0, 5)
      .map((name, idx) => ({ cameraNumber: idx + 1, path: join(dir, name) }))
    if (anyVideo.length > 0) {
      return { dir, videos: anyVideo }
    }

    // 3. 如果是用户显式指定的目录，支持该目录下的所有 .mp4
    if (customDir) {
      const allMp4 = entries
        .filter((name) => /\.mp4$/i.test(name))
        .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
        .slice(0, 5)
        .map((name, idx) => ({ cameraNumber: idx + 1, path: join(dir, name) }))
      if (allMp4.length > 0) {
        return { dir, videos: allMp4 }
      }
    }
  } catch {
    // 目录不可读时直接返回空
  }
  return { dir, videos: [] }
}

function openBrowser(url) {
  try {
    const platform = process.platform
    if (platform === 'win32') {
      execSync(`start "" "${url}"`)
    } else if (platform === 'darwin') {
      execSync(`open "${url}"`)
    } else {
      execSync(`xdg-open "${url}"`)
    }
  } catch {
    // 忽略打开浏览器失败
  }
}

async function checkFileReadable(path, label) {
  try {
    await access(path, constants.R_OK)
  } catch {
    throw new Error(`无法读取${label}文件: "${path}"，请确认文件路径正确且具有读取权限。`)
  }
}

async function resolveProject(baseUrl, token, requested) {
  syncLocalSyntheticMemberships()
  let items = []
  try {
    const res = await fetch(`${baseUrl.replace(/\/$/, '')}/api/v1/projects`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (res.ok) {
      const data = await res.json()
      items = Array.isArray(data.items) ? data.items : []
    }
  } catch (err) {
    throw new Error(`无法连接到平台 API (${baseUrl}): ${err.message}`)
  }

  if (items.length === 0) {
    return { project_id: DEFAULT_PROJECT_ID, display_name: '默认测试项目' }
  }

  if (requested) {
    // 1. Exact UUID match
    const byId = items.find((p) => p.project_id.toLowerCase() === requested.toLowerCase())
    if (byId) return byId

    // 2. Exact name match (case-insensitive)
    const byName = items.find((p) => p.display_name.trim().toLowerCase() === requested.trim().toLowerCase())
    if (byName) return byName

    // 3. Partial name match
    const byPartial = items.find((p) => p.display_name.toLowerCase().includes(requested.toLowerCase()))
    if (byPartial) return byPartial

    const available = items.map((p) => `"${p.display_name}" (ID: ${p.project_id})`).join('\n  - ')
    throw new Error(`未找到项目 "${requested}"。\n当前可用项目列表:\n  - ${available}`)
  }

  // 默认选择最新活跃项目（按创建时间倒序排）
  const nonSynthetic = items.filter((p) => p.display_name !== '本地合成上传验证')
  nonSynthetic.sort((a, b) => new Date(b.created_at || 0) - new Date(a.created_at || 0))
  return nonSynthetic.length > 0 ? nonSynthetic[0] : items[0]
}

async function main() {
  const { values, positionals } = parseArgs({
    options: {
      cam1: { type: 'string' },
      cam2: { type: 'string' },
      cam3: { type: 'string' },
      cam4: { type: 'string' },
      cam5: { type: 'string' },
      dir: { type: 'string' },
      project: { type: 'string' },
      baseUrl: { type: 'string', default: process.env.PLATFORM_BASE_URL || DEFAULT_BASE_URL },
      webUrl: { type: 'string', default: process.env.PLATFORM_WEB_URL || DEFAULT_WEB_URL },
      token: { type: 'string', default: process.env.DEV_BEARER_TOKEN || DEFAULT_DEV_TOKEN },
      adminToken: { type: 'string', default: process.env.DEV_ADMIN_BEARER_TOKEN || DEFAULT_ADMIN_TOKEN },
      key: { type: 'string' },
      open: { type: 'boolean', default: true },
      'no-open': { type: 'boolean', default: false },
    },
    allowPositionals: true,
  })

  // 优先从 --project 或第 1 个位置参数获取项目名/UUID
  const requestedProject = values.project || positionals[0] || process.env.DEV_PROJECT_ID

  let cameraInputs = [
    { cameraNumber: 1, path: values.cam1, role: '主视角 (Primary View)' },
    { cameraNumber: 2, path: values.cam2, role: '辅助机位 2 (Supporting View)' },
    { cameraNumber: 3, path: values.cam3, role: '辅助机位 3 (Supporting View)' },
    { cameraNumber: 4, path: values.cam4, role: '辅助机位 4 (Supporting View)' },
    { cameraNumber: 5, path: values.cam5, role: '辅助机位 5 (Supporting View)' },
  ].filter((cam) => Boolean(cam.path))

  // 如果未手动指定 --cam1，则自动从 Downloads 或 --dir 检测视频
  if (cameraInputs.length === 0) {
    const { dir, videos } = await autoDetectVideos(values.dir)
    if (videos.length > 0) {
      console.log(`\n🔍 从目录自动检测到 ${videos.length} 个机位视频 (${dir}):`)
      cameraInputs = videos.map((v) => ({
        cameraNumber: v.cameraNumber,
        path: v.path,
        role: v.cameraNumber === 1 ? '主视角 (Primary View)' : `辅助机位 ${v.cameraNumber} (Supporting View)`,
      }))
      for (const cam of cameraInputs) {
        console.log(`  - 机位 ${cam.cameraNumber}: ${basename(cam.path)}`)
      }
    } else {
      console.error(`
用法:
  pnpm import-multicam [项目名]                    # 极简模式：自动使用 Downloads 下的 video1~4.mp4
  node tools/import-local-multicam.mjs [项目名]     # 自动检测下载目录视频并导入最新或指定项目

选项:
  --dir <目录路径>                                # 从指定目录自动扫描并导入视频
  --project <项目名或ID>                          # 指定导入的目标项目 (默认: 最新活跃项目)
  --cam1 <视频1> [--cam2 <视频2>] ...             # 手动指定各个机位的视频文件
  --no-open                                      # 导入完成后不自动唤起浏览器

未在下载目录中找到 video1.mp4 ~ video4.mp4。请传入 --cam1 或将视频放入 Downloads 目录。
`)
      process.exitCode = 1
      return
    }
  }

  const targetProject = await resolveProject(values.baseUrl, values.token, requestedProject)
  const targetProjectId = targetProject.project_id

  console.log('\n========================================')
  console.log('   配药平台本地多相机视频合组导入工具')
  console.log('========================================')
  console.log(`目标项目     : ${targetProject.display_name} (ID: ${targetProjectId})`)
  console.log(`API 端点     : ${values.baseUrl}`)
  console.log(`包含机位数量 : ${cameraInputs.length} 个机位`)

  // 校验并加载各视频
  const videoBytesByCamera = new Map()
  const expectedCameraNumbers = []

  for (const cam of cameraInputs) {
    const absPath = resolve(cam.path)
    console.log(`\n正在读取 [机位 ${cam.cameraNumber}] (${cam.role}):`)
    console.log(`  路径: ${absPath}`)
    await checkFileReadable(absPath, `机位 ${cam.cameraNumber}`)
    const bytes = await readFile(absPath)
    if (bytes.length === 0) {
      throw new Error(`机位 ${cam.cameraNumber} 视频文件为空: ${absPath}`)
    }
    const sizeMb = (bytes.length / (1024 * 1024)).toFixed(2)
    console.log(`  大小: ${sizeMb} MB (${bytes.length} 字节)`)
    videoBytesByCamera.set(cam.cameraNumber, bytes)
    expectedCameraNumbers.push(cam.cameraNumber)
  }

  const captureGroupKey = values.key || `local-group-${Date.now().toString(36)}`
  const sourceBatchId = `local-batch-${randomUUID()}`

  const adminUploadClient = new SyntheticUploadClient({
    baseUrl: values.baseUrl,
    projectId: targetProjectId,
    bearerToken: values.adminToken,
  })

  const uploadClient = new SyntheticUploadClient({
    baseUrl: values.baseUrl,
    projectId: targetProjectId,
    bearerToken: values.token,
  })

  const workflow = new SyntheticCaptureGroupWorkflowClient({
    baseUrl: values.baseUrl,
    projectId: targetProjectId,
    bearerToken: values.token,
    adminBearerToken: values.adminToken,
  })

  console.log('\n1. 正在初始化相机通道与机位配置...')
  const cameraSetKey = `local-camset-${expectedCameraNumbers.join('-')}-${Date.now().toString(36)}`
  const cameraSet = await adminUploadClient.provisionSyntheticCameraSet({
    cameraNumbers: expectedCameraNumbers,
    cameraSetKey,
    layoutVersion: 'local-multicam-v1',
  })
  console.log(`   机位配置就绪，配置键: ${cameraSetKey}`)

  console.log('\n2. 正在登记外部采集清单并分片上传视频...')
  const uploaded = await uploadClient.uploadSyntheticCaptureGroup({
    cameraSet,
    captureGroupKey,
    expectedCameraNumbers,
    presentCameraNumbers: expectedCameraNumbers,
    videoBytesByCamera,
    capturedAt: new Date().toISOString(),
    deviceId: 'local-multicam-importer',
    sourceBatchId,
  })
  console.log(`   上传完成，生成批次 ID: ${uploaded.batchId}`)
  console.log(`   生成采集组 ID: ${uploaded.captureGroupId}`)

  console.log('\n3. 正在显式确认采集组接收状态 (Finalize Receipt)...')
  await workflow.finalizeCaptureGroup(uploaded.captureGroupId)
  console.log('   采集组已进入正式状态 (COMPLETE)')

  console.log('\n4. 正在触发媒体探测 (Media Probe) 与手部 AI (MediaPipe)...')
  for (const view of uploaded.views) {
    try {
      await workflow.requestMediaProbe(view.clipAssetId)
      console.log(`   机位 ${view.cameraNumber} (Asset: ${view.clipAssetId}) 媒体探测已触发`)
    } catch (err) {
      console.log(`   机位 ${view.cameraNumber} 媒体探测已在队列中`)
    }
  }

  console.log('\n========================================')
  console.log('            多相机合组导入成功！')
  console.log('========================================')
  console.log(`采集组 ID (CaptureGroup ID) : ${uploaded.captureGroupId}`)
  console.log(`包含机位                    : [${expectedCameraNumbers.join(', ')}]`)
  for (const view of uploaded.views) {
    console.log(`  - 机位 ${view.cameraNumber} (${view.cameraNumber === 1 ? '主视角' : '辅助视角'}): ClipAsset ${view.clipAssetId}`)
  }

  const webDetailUrl = `${values.webUrl.replace(/\/$/, '')}/projects/${targetProjectId}/capture-groups/${uploaded.captureGroupId}`
  console.log('\n👉 请在浏览器中直接打开下方链接查看多相机视图与标注：')
  console.log(`   ${webDetailUrl}\n`)

  if (values.open && !values['no-open']) {
    openBrowser(webDetailUrl)
    console.log('🚀 已在默认浏览器中自动打开采集组页面！\n')
  }
}

main().catch((error) => {
  console.error('\n❌ 导入失败:')
  if (error instanceof PlatformApiError) {
    console.error(`HTTP ${error.status} (${error.errorCode}): ${error.message}`)
    if (error.requestId) console.error(`Request ID: ${error.requestId}`)
  } else {
    console.error(error.message || String(error))
  }
  process.exitCode = 1
})
