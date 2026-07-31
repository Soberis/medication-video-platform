import { createHash, randomUUID } from 'node:crypto'
import { existsSync } from 'node:fs'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { loadEnvFile } from 'node:process'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

import { generateSyntheticVideo } from '../synthetic-upload-client/synthetic-video.mjs'
import { SyntheticUploadClient } from '../synthetic-upload-client/upload-client.mjs'

const UPLOADER_ACTOR_ID = 'local-synthetic-uploader'
const MEDIA_INGEST_TIMEOUT_MS = 120_000
const EXPORT_TIMEOUT_MS = 180_000
const POLL_INTERVAL_MS = 1_000

export function assertAcceptanceEnvironment({
  enabled,
  dataProfile,
  baseUrl,
  adminBearerToken,
  uploaderBearerToken,
}) {
  if (enabled !== 'true') {
    throw new Error('Set RUN_DATASET_EXPORT_ACCEPTANCE=true to authorize this synthetic export acceptance')
  }
  if (dataProfile !== 'synthetic') {
    throw new Error('Dataset export acceptance requires DATA_PROFILE=synthetic')
  }
  if (!adminBearerToken || !uploaderBearerToken) {
    throw new Error('DEV_ADMIN_BEARER_TOKEN and DEV_BEARER_TOKEN are required')
  }

  let parsedBaseUrl
  try {
    parsedBaseUrl = new URL(baseUrl)
  } catch {
    throw new Error('PLATFORM_BASE_URL must be a valid HTTP URL')
  }
  if (!['http:', 'https:'].includes(parsedBaseUrl.protocol) || !isLoopbackHost(parsedBaseUrl.hostname)) {
    throw new Error('Dataset export acceptance only permits a localhost Core API')
  }

  return {
    baseUrl: parsedBaseUrl.toString().replace(/\/$/, ''),
    adminBearerToken,
    uploaderBearerToken,
  }
}

export function requireAvailableArtifact(exportRequest) {
  if (exportRequest?.status !== 'AVAILABLE') {
    throw new Error('Dataset export did not reach AVAILABLE')
  }
  const artifact = exportRequest.artifact
  if (
    !artifact ||
    !Number.isSafeInteger(artifact.size_bytes) ||
    artifact.size_bytes <= 0 ||
    typeof artifact.sha256 !== 'string' ||
    !/^[a-f0-9]{64}$/.test(artifact.sha256)
  ) {
    throw new Error('AVAILABLE dataset export is missing a valid artifact')
  }
  return artifact
}

export function sha256Hex(bytes) {
  return createHash('sha256').update(bytes).digest('hex')
}

class CoreApiError extends Error {
  constructor(operation, status, payload) {
    const errorCode = typeof payload?.error_code === 'string' ? payload.error_code : 'HTTP_ERROR'
    super(`${operation} failed with HTTP ${status} (${errorCode})`)
    this.name = 'CoreApiError'
    this.status = status
    this.errorCode = errorCode
  }
}

class CoreApiClient {
  constructor({ baseUrl, bearerToken }) {
    this.baseUrl = baseUrl
    this.bearerToken = bearerToken
  }

  async request(operation, requestPath, { method = 'GET', body, idempotencyKey } = {}) {
    const headers = {
      Accept: 'application/json, application/problem+json',
      Authorization: `Bearer ${this.bearerToken}`,
      'X-Request-Id': `${operation.replaceAll(' ', '-').slice(0, 80)}-${randomUUID()}`,
    }
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json'
    }
    if (idempotencyKey) {
      headers['Idempotency-Key'] = idempotencyKey
    }

    const response = await fetch(`${this.baseUrl}/api/v1${requestPath}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const payload = await readJson(response, operation)
    if (!response.ok) {
      throw new CoreApiError(operation, response.status, payload)
    }
    return payload
  }
}

async function main() {
  loadLocalEnvironment()
  const environment = assertAcceptanceEnvironment({
    enabled: process.env.RUN_DATASET_EXPORT_ACCEPTANCE,
    dataProfile: process.env.DATA_PROFILE,
    baseUrl: process.env.PLATFORM_BASE_URL || 'http://localhost:8080',
    adminBearerToken: process.env.DEV_ADMIN_BEARER_TOKEN,
    uploaderBearerToken: process.env.DEV_BEARER_TOKEN,
  })
  await assertHealthy(environment.baseUrl)

  const startedAt = Date.now()
  const administrator = new CoreApiClient({
    baseUrl: environment.baseUrl,
    bearerToken: environment.adminBearerToken,
  })
  const uploader = new CoreApiClient({
    baseUrl: environment.baseUrl,
    bearerToken: environment.uploaderBearerToken,
  })
  const project = await administrator.request('create synthetic acceptance project', '/projects', {
    method: 'POST',
    idempotencyKey: key('dataset-export-acceptance-project'),
    body: { display_name: `Synthetic dataset export acceptance ${randomUUID()}` },
  })
  const projectId = requireUuid(project.project_id, 'Synthetic acceptance project')
  await administrator.request(
    'grant synthetic uploader membership',
    `/projects/${projectId}/memberships/${UPLOADER_ACTOR_ID}`,
    {
      method: 'PUT',
      idempotencyKey: key('dataset-export-acceptance-membership'),
      body: { role: 'MEMBER' },
    },
  )

  const workDirectory = await mkdtemp(path.join(tmpdir(), 'medication-dataset-export-'))
  try {
    const videoPath = path.join(workDirectory, 'synthetic-medication-preparation.mp4')
    await generateSyntheticVideo(videoPath)
    const videoBytes = await readFile(videoPath)
    const uploadAdministrator = new SyntheticUploadClient({
      baseUrl: environment.baseUrl,
      projectId,
      bearerToken: environment.adminBearerToken,
    })
    const uploadClient = new SyntheticUploadClient({
      baseUrl: environment.baseUrl,
      projectId,
      bearerToken: environment.uploaderBearerToken,
    })
    const camera = await uploadAdministrator.provisionSyntheticCamera()
    const uploadedSessions = [
      await uploadClient.uploadSyntheticSession(videoBytes, camera),
      await uploadClient.uploadSyntheticSession(videoBytes, camera),
    ]

    for (const session of uploadedSessions) {
      await prepareEligibleSession({ administrator, uploader, projectId, session })
    }

    const datasetVersion = await administrator.request(
      'create dataset version draft',
      `/projects/${projectId}/dataset-versions`,
      {
        method: 'POST',
        idempotencyKey: key('dataset-export-acceptance-draft'),
        body: {
          display_name: 'Synthetic export acceptance dataset',
          capture_session_ids: uploadedSessions.map((session) => session.capture_session_id),
        },
      },
    )
    const datasetVersionId = requireUuid(datasetVersion.dataset_version_id, 'Dataset version draft')
    if (datasetVersion.status !== 'DRAFT') {
      throw new Error('Dataset version creation did not enter DRAFT')
    }
    await administrator.request(
      'create dataset split plan',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}/split-plans`,
      {
        method: 'POST',
        idempotencyKey: key('dataset-export-acceptance-split'),
        body: { holdout_count: 1 },
      },
    )
    await administrator.request(
      'create dataset build specification',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}/build-specifications`,
      { method: 'POST', idempotencyKey: key('dataset-export-acceptance-specification') },
    )
    await administrator.request(
      'start dataset build',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}/build`,
      { method: 'POST', idempotencyKey: key('dataset-export-acceptance-build') },
    )
    await administrator.request(
      'seal dataset build snapshot',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}/build-snapshots`,
      { method: 'POST', idempotencyKey: key('dataset-export-acceptance-snapshot') },
    )
    const validation = await administrator.request(
      'validate dataset version',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}/validations`,
      { method: 'POST', idempotencyKey: key('dataset-export-acceptance-validation') },
    )
    if (validation.outcome !== 'PASS') {
      throw new Error('Synthetic dataset version did not pass freeze validation')
    }

    await administrator.request(
      'promote independent synthetic approver',
      `/projects/${projectId}/memberships/${UPLOADER_ACTOR_ID}`,
      {
        method: 'PUT',
        idempotencyKey: key('dataset-export-acceptance-approver'),
        body: { role: 'ADMIN' },
      },
    )
    const freeze = await uploader.request(
      'approve dataset freeze',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}/freezes`,
      { method: 'POST', idempotencyKey: key('dataset-export-acceptance-freeze') },
    )
    if (freeze.status !== 'FROZEN') {
      throw new Error('Independent approval did not freeze the dataset version')
    }

    const exportRequest = await administrator.request(
      'request dataset export',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}/exports`,
      {
        method: 'POST',
        idempotencyKey: key('dataset-export-acceptance-export'),
        body: {
          purpose: 'MODEL_TRAINING',
          expires_at: new Date(Date.now() + 60 * 60 * 1_000).toISOString(),
        },
      },
    )
    if (exportRequest.status !== 'REQUESTED') {
      throw new Error('Dataset export request did not enter REQUESTED')
    }
    const readiness = await uploader.request(
      'read dataset export approval readiness',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}/exports/approval-readiness`,
    )
    if (readiness.ready_for_approval !== true || readiness.blocking_reasons?.length !== 0) {
      throw new Error('Independent synthetic approver is not ready to approve the export')
    }
    const approvedExport = await uploader.request(
      'approve dataset export',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}/exports/approvals`,
      { method: 'POST', idempotencyKey: key('dataset-export-acceptance-export-approval') },
    )
    if (approvedExport.status !== 'QUEUED' || approvedExport.attempt?.attempt !== 1) {
      throw new Error('Dataset export approval did not queue its first Worker attempt')
    }

    const detail = await waitFor(
      'dataset export Worker completion',
      async () => administrator.request(
        'read dataset export status',
        `/projects/${projectId}/dataset-versions/${datasetVersionId}`,
      ),
      (response) => {
        if (response.export_request?.status === 'FAILED') {
          throw new Error('Dataset export Worker reported FAILED')
        }
        return response.export_request?.status === 'AVAILABLE'
      },
      EXPORT_TIMEOUT_MS,
    )
    const artifact = requireAvailableArtifact(detail.export_request)
    const authorization = await uploader.request(
      'authorize synthetic export download',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}/exports/download-authorizations`,
      { method: 'POST' },
    )
    const downloaded = await downloadAndDigest(authorization.download_url)
    if (downloaded.sizeBytes !== artifact.size_bytes || downloaded.sha256 !== artifact.sha256) {
      throw new Error('Short-lived export download does not match the registered artifact')
    }

    process.stdout.write(`ACCEPTANCE_RESULT ${JSON.stringify({
      schema_version: 'dataset-export-cross-process-acceptance-v1',
      project_id: projectId,
      dataset_version_id: datasetVersionId,
      dataset_export_id: detail.export_request.dataset_export_id,
      capture_session_count: uploadedSessions.length,
      independent_approvals: true,
      worker_attempt: detail.export_request.attempt?.attempt,
      export_status: detail.export_request.status,
      artifact_size_bytes: artifact.size_bytes,
      artifact_sha256: artifact.sha256,
      downloaded_sha256: downloaded.sha256,
      duration_ms: Date.now() - startedAt,
    })}\n`)
  } finally {
    await rm(workDirectory, { recursive: true, force: true })
  }
}

async function prepareEligibleSession({ administrator, uploader, projectId, session }) {
  const captureSessionId = requireUuid(session.capture_session_id, 'Synthetic capture session')
  const [clipAssetId] = session.clip_asset_ids
  if (typeof clipAssetId !== 'string') {
    throw new Error('Synthetic upload did not return a clip asset')
  }
  await waitFor(
    'media ingestion',
    async () => administrator.request(
      'read capture-session admission preview',
      `/projects/${projectId}/capture-sessions/${captureSessionId}/dataset-admission-preview`,
    ),
    (preview) => preview.streams?.length === 1
      && preview.streams.every((stream) => stream.asset_status === 'INGESTED'),
    MEDIA_INGEST_TIMEOUT_MS,
  )
  await administrator.request(
    'record synthetic quality review',
    `/projects/${projectId}/clip-assets/${clipAssetId}/quality-review`,
    {
      method: 'PUT',
      body: {
        decision: 'QUALIFIED',
        reason_code: 'MEETS_REQUIREMENTS',
        expected_version: 0,
      },
    },
  )
  const task = await administrator.request(
    'create synthetic annotation task',
    `/projects/${projectId}/clip-assets/${clipAssetId}/annotation-tasks`,
    {
      method: 'POST',
      idempotencyKey: key('dataset-export-acceptance-task'),
      body: { assignee_actor_id: UPLOADER_ACTOR_ID },
    },
  )
  const annotationTaskId = requireUuid(task.annotation_task_id, 'Synthetic annotation task')
  const revision = await uploader.request(
    'submit synthetic annotation revision',
    `/projects/${projectId}/annotation-tasks/${annotationTaskId}/revisions`,
    {
      method: 'POST',
      idempotencyKey: key('dataset-export-acceptance-revision'),
      body: {
        segments: [{ start_ms: 120, end_ms: 780, label: 'retrieve_medicine' }],
        note: 'Synthetic dataset export acceptance annotation',
      },
    },
  )
  const annotationRevisionId = requireUuid(revision.annotation_revision_id, 'Synthetic annotation revision')
  await administrator.request(
    'independently review synthetic annotation',
    `/projects/${projectId}/annotation-revisions/${annotationRevisionId}/review`,
    {
      method: 'POST',
      idempotencyKey: key('dataset-export-acceptance-review'),
      body: { decision: 'APPROVED', review_note: 'Synthetic acceptance review approved' },
    },
  )
  await waitFor(
    'dataset admission eligibility',
    async () => administrator.request(
      'read eligible capture-session admission',
      `/projects/${projectId}/capture-sessions/${captureSessionId}/dataset-admission-preview`,
    ),
    (preview) => preview.eligible === true,
    MEDIA_INGEST_TIMEOUT_MS,
  )
}

async function assertHealthy(baseUrl) {
  const response = await fetch(`${baseUrl}/actuator/health`)
  const payload = await readJson(response, 'Core API health check')
  if (!response.ok || payload?.status !== 'UP') {
    throw new Error('Core API must be healthy before dataset export acceptance')
  }
}

async function waitFor(label, read, matches, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  do {
    const value = await read()
    if (matches(value)) {
      return value
    }
    await delay(POLL_INTERVAL_MS)
  } while (Date.now() < deadline)
  throw new Error(`${label} did not finish within ${Math.ceil(timeoutMs / 1_000)} seconds`)
}

async function downloadAndDigest(downloadUrl) {
  if (typeof downloadUrl !== 'string') {
    throw new Error('Export download authorization is missing its URL')
  }
  let parsedDownloadUrl
  try {
    parsedDownloadUrl = new URL(downloadUrl)
  } catch {
    throw new Error('Export download authorization contains an invalid URL')
  }
  if (!['http:', 'https:'].includes(parsedDownloadUrl.protocol) || !isLoopbackHost(parsedDownloadUrl.hostname)) {
    throw new Error('Synthetic acceptance refuses a non-local export download URL')
  }
  const response = await fetch(downloadUrl)
  if (!response.ok) {
    throw new Error(`Authorized export download failed with HTTP ${response.status}`)
  }
  const bytes = Buffer.from(await response.arrayBuffer())
  return { sizeBytes: bytes.length, sha256: sha256Hex(bytes) }
}

async function readJson(response, operation) {
  const text = await response.text()
  if (!text) {
    return null
  }
  try {
    return JSON.parse(text)
  } catch {
    throw new Error(`${operation} returned non-JSON content`)
  }
}

function loadLocalEnvironment() {
  const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
  const environmentFile = selectEnvironmentFile(repositoryRoot)
  if (existsSync(environmentFile)) {
    loadEnvFile(environmentFile)
  }
}

export function selectEnvironmentFile(repositoryRoot, fileExists = existsSync) {
  const localEnvironmentFile = path.join(repositoryRoot, '.env')
  return fileExists(localEnvironmentFile)
    ? localEnvironmentFile
    : path.join(repositoryRoot, '.env.example')
}

function key(prefix) {
  return `${prefix}-${randomUUID()}`
}

function requireUuid(value, label) {
  if (typeof value !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)) {
    throw new Error(`${label} is missing a valid UUID`)
  }
  return value
}

function isLoopbackHost(hostname) {
  const normalized = hostname.toLowerCase().replace(/^\[|\]$/g, '')
  return normalized === 'localhost' || normalized === '127.0.0.1' || normalized === '::1'
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

const isEntryPoint = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (isEntryPoint) {
  main().catch((error) => {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 1
  })
}
