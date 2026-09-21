import { readFile, access } from 'node:fs/promises'
import { constants } from 'node:fs'
import { basename, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { randomUUID } from 'node:crypto'

import { SyntheticUploadClient, normalizeCameraNumbers, PlatformApiError } from './synthetic-upload-client/upload-client.mjs'
import { SyntheticCaptureGroupWorkflowClient } from './synthetic-capture-group-client/workflow-client.mjs'

const DEFAULT_BASE_URL = 'http://localhost:8080'
const DEFAULT_WEB_URL = 'http://localhost:5173'
const DEFAULT_PROJECT_ID = '00000000-0000-0000-0000-000000000001'
const DEFAULT_DEV_TOKEN = 'synthetic-local-bearer-token-do-not-reuse'
const DEFAULT_ADMIN_TOKEN = 'synthetic-local-admin-bearer-token-do-not-reuse'

async function checkFileReadable(path, label) {
  try {
    await access(path, constants.R_OK)
  } catch {
    throw new Error(`无法读取${label}文件: "${path}"，请确认文件路径正确且具有读取权限。`)
  }
}

async function resolveProject(baseUrl, token, requested) {
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

  // 默认选择最新活跃项目（优先选择非 synthetic 测试项目，例如 C / A / ZZZ）
  const nonSynthetic = items.filter((p) => p.display_name !== '本地合成上传验证')
  return nonSynthetic.length > 0 ? nonSynthetic[0] : items[0]
}

async function main() {
  const { values } = parseArgs({
    options: {
      cam1: { type: 'string' },
      cam2: { type: 'string' },
      cam3: { type: 'string' },
      cam4: { type: 'string' },
      cam5: { type: 'string' },
      project: { type: 'string', default: process.env.DEV_PROJECT_ID },
      baseUrl: { type: 'string', default: process.env.PLATFORM_BASE_URL || DEFAULT_BASE_URL },
      webUrl: { type: 'string', default: process.env.PLATFORM_WEB_URL || DEFAULT_WEB_URL },
      token: { type: 'string', default: process.env.DEV_BEARER_TOKEN || DEFAULT_DEV_TOKEN },
      adminToken: { type: 'string', default: process.env.DEV_ADMIN_BEARER_TOKEN || DEFAULT_ADMIN_TOKEN },
      key: { type: 'string' },
    },
    allowPositionals: true,
  })

  if (!values.cam1) {
    console.error(`
用法:
  node tools/import-local-multicam.mjs [--project <项目名或ID>] --cam1 <视频1> [--cam2 <视频2>] ...

示例:
  # 导入到项目 C:
  node tools/import-local-multicam.mjs --project "C" --cam1 "C:\\Users\\ymh10\\Downloads\\video1.mp4" --cam2 "C:\\Users\\ymh10\\Downloads\\video2.mp4"

  # 导入到项目 A:
  node tools/import-local-multicam.mjs --project "A" --cam1 "C:\\Users\\ymh10\\Downloads\\video1.mp4" --cam2 "C:\\Users\\ymh10\\Downloads\\video2.mp4"
`)
    process.exitCode = 1
    return
  }

  const targetProject = await resolveProject(values.baseUrl, values.token, values.project)
  const targetProjectId = targetProject.project_id

  const cameraInputs = [
    { cameraNumber: 1, path: values.cam1, role: '主视角 (Primary View)' },
    { cameraNumber: 2, path: values.cam2, role: '辅助机位 2 (Supporting View)' },
    { cameraNumber: 3, path: values.cam3, role: '辅助机位 3 (Supporting View)' },
    { cameraNumber: 4, path: values.cam4, role: '辅助机位 4 (Supporting View)' },
    { cameraNumber: 5, path: values.cam5, role: '辅助机位 5 (Supporting View)' },
  ].filter((cam) => Boolean(cam.path))

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
