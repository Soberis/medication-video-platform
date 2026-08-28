import { randomUUID } from 'node:crypto'

import { PlatformApiError } from '../synthetic-upload-client/upload-client.mjs'

const TERMINAL_MEDIA_STATUSES = new Set(['SUCCEEDED', 'FAILED'])
const TERMINAL_CANDIDATE_STATUSES = new Set(['SUCCEEDED', 'FAILED'])

function pathSegment(value, name) {
  if (value === undefined || value === null || value === '') {
    throw new TypeError(`${name} is required`)
  }
  return encodeURIComponent(String(value))
}

function operationKey(prefix) {
  return `synthetic-acceptance-${prefix}-${randomUUID()}`
}

function requestId(prefix) {
  return operationKey(prefix)
}

export class SyntheticCaptureGroupWorkflowClient {
  constructor({ baseUrl, projectId, bearerToken, adminBearerToken, fetchImpl = globalThis.fetch }) {
    const parsedBaseUrl = new URL(baseUrl)
    if (!['http:', 'https:'].includes(parsedBaseUrl.protocol)) {
      throw new TypeError('PLATFORM_BASE_URL must use HTTP or HTTPS')
    }
    if (typeof fetchImpl !== 'function') {
      throw new TypeError('A fetch implementation is required')
    }
    if (!bearerToken) {
      throw new TypeError('DEV_BEARER_TOKEN is required')
    }
    this.baseUrl = parsedBaseUrl.toString().replace(/\/$/, '')
    this.projectId = projectId
    this.bearerToken = bearerToken
    this.adminBearerToken = adminBearerToken
    this.fetchImpl = fetchImpl
  }

  async getCaptureGroup(captureGroupId) {
    return this.#requestJson('read CaptureGroup', `/projects/${pathSegment(this.projectId, 'project')}/capture-groups/${pathSegment(captureGroupId, 'capture group')}`)
  }

  async finalizeCaptureGroup(captureGroupId) {
    return this.#requestJson('finalize CaptureGroup receipt', `/projects/${pathSegment(this.projectId, 'project')}/capture-groups/${pathSegment(captureGroupId, 'capture group')}/receipt/finalize`, {
      method: 'POST',
    })
  }

  async getClipAsset(clipAssetId) {
    return this.#requestJson('read ClipAsset', `/projects/${pathSegment(this.projectId, 'project')}/clip-assets/${pathSegment(clipAssetId, 'clip asset')}`)
  }

  async requestMediaProbe(clipAssetId) {
    return this.#requestJson('request Media Probe', `/projects/${pathSegment(this.projectId, 'project')}/clip-assets/${pathSegment(clipAssetId, 'clip asset')}/media-probe-jobs`, {
      method: 'POST',
      idempotencyKey: operationKey('media-probe'),
    })
  }

  async getMediaJob(mediaJobId) {
    return this.#requestJson('read Media Probe job', `/media-jobs/${pathSegment(mediaJobId, 'media job')}`)
  }

  async getMediaReport(clipAssetId) {
    return this.#requestJson('read Media Probe report', `/projects/${pathSegment(this.projectId, 'project')}/clip-assets/${pathSegment(clipAssetId, 'clip asset')}/media-report`)
  }

  async recordViewQuality(clipAssetId, decision, reasonCode, expectedVersion = 0) {
    return this.#requestJson('record human View Quality', `/projects/${pathSegment(this.projectId, 'project')}/clip-assets/${pathSegment(clipAssetId, 'clip asset')}/quality-review`, {
      method: 'PUT',
      body: { decision, reason_code: reasonCode, expected_version: expectedVersion },
      requestId: requestId('view-quality'),
    })
  }

  async getViewQuality(clipAssetId) {
    return this.#requestJson('read human View Quality', `/projects/${pathSegment(this.projectId, 'project')}/clip-assets/${pathSegment(clipAssetId, 'clip asset')}/quality-review`)
  }

  async recordGroupQuality(captureGroupId, result, reason, expectedRevision = 0) {
    return this.#requestJson('record CaptureGroup Quality', `/projects/${pathSegment(this.projectId, 'project')}/capture-groups/${pathSegment(captureGroupId, 'capture group')}/quality-review`, {
      method: 'PUT',
      body: { result, reason, expected_revision: expectedRevision },
      idempotencyKey: operationKey('group-quality'),
      requestId: requestId('group-quality'),
    })
  }

  async listAnnotationTasks(role = 'admin') {
    return this.#requestJson('list Annotation Tasks', `/projects/${pathSegment(this.projectId, 'project')}/annotation-tasks`, { role })
  }

  async selectPrimaryView(annotationTaskId, clipAssetId) {
    return this.#requestJson('select Primary View', `/projects/${pathSegment(this.projectId, 'project')}/annotation-tasks/${pathSegment(annotationTaskId, 'annotation task')}/primary-view`, {
      method: 'PUT',
      body: { clip_asset_id: clipAssetId },
      requestId: requestId('primary-view'),
    })
  }

  async submitRevision(annotationTaskId, segments, note = 'Synthetic acceptance annotation') {
    return this.#requestJson('submit Annotation Revision', `/projects/${pathSegment(this.projectId, 'project')}/annotation-tasks/${pathSegment(annotationTaskId, 'annotation task')}/revisions`, {
      method: 'POST',
      body: {
        segments: segments.map((segment) => ({ start_ms: segment.startMs, end_ms: segment.endMs, label: segment.label })),
        note,
      },
      idempotencyKey: operationKey('annotation-revision'),
      requestId: requestId('annotation-revision'),
    })
  }

  async reviewRevision(annotationRevisionId, decision, reviewNote) {
    return this.#requestJson('record human Annotation Review', `/projects/${pathSegment(this.projectId, 'project')}/annotation-revisions/${pathSegment(annotationRevisionId, 'annotation revision')}/review`, {
      method: 'POST',
      role: 'admin',
      body: { decision, review_note: reviewNote },
      idempotencyKey: operationKey('annotation-review'),
      requestId: requestId('annotation-review'),
    })
  }

  async recheckCaptureGroupCleaning(annotationRevisionId) {
    return this.#requestJson('recheck CaptureGroup event Cleaning', `/projects/${pathSegment(this.projectId, 'project')}/annotation-revisions/${pathSegment(annotationRevisionId, 'annotation revision')}/capture-group-cleaning-rechecks`, {
      method: 'POST',
      role: 'admin',
      requestId: requestId('cleaning-recheck'),
    })
  }

  async previewTrainingDataV3() {
    return this.#requestJson('preview training-data.v3', `/projects/${pathSegment(this.projectId, 'project')}/training-data/v3/preview`, { role: 'admin' })
  }

  async downloadTrainingDataV3() {
    const response = await this.#request('download training-data.v3', `/projects/${pathSegment(this.projectId, 'project')}/training-data/v3/download`, {
      role: 'admin',
      accept: 'application/vnd.medication.training-data.v3+json',
    })
    const text = await response.text()
    if (!text) {
      throw new Error('training-data.v3 download returned an empty body')
    }
    let document
    try {
      document = JSON.parse(text)
    }
    catch {
      throw new Error('training-data.v3 download returned non-JSON content')
    }
    return { document, contentType: response.headers.get('content-type'), fileName: 'training_data_v3.json' }
  }

  async validateTrainingDataV3Splits(assignments) {
    return this.#requestJson('validate training-data.v3 split assignments', `/projects/${pathSegment(this.projectId, 'project')}/training-data/v3/validate-splits`, {
      method: 'POST',
      role: 'admin',
      body: { assignments },
      requestId: requestId('split-validation'),
    })
  }

  async requestHandPose(clipAssetId) {
    return this.#requestJson('request AI Candidate hand pose', `/projects/${pathSegment(this.projectId, 'project')}/videos/${pathSegment(clipAssetId, 'clip asset')}/hand-pose-detections`, {
      method: 'POST',
      idempotencyKey: operationKey('hand-pose'),
      requestId: requestId('hand-pose'),
    })
  }

  async getAiJob(aiJobId) {
    return this.#requestJson('read AI Candidate job', `/ai-jobs/${pathSegment(aiJobId, 'AI job')}`, {
      headers: { 'X-Project-Id': this.projectId },
    })
  }

  async getHandPoseCandidates(clipAssetId) {
    return this.#requestJson('read AI Candidate evidence', `/projects/${pathSegment(this.projectId, 'project')}/videos/${pathSegment(clipAssetId, 'clip asset')}/hand-pose-candidates`)
  }

  async waitForMediaProbe(clipAssetId, { timeoutMs = 120_000, intervalMs = 1_000 } = {}) {
    const existingAsset = await this.getClipAsset(clipAssetId)
    const existingTerminalResult = await this.#readCompletedMediaProbe(clipAssetId, existingAsset)
    if (existingTerminalResult) {
      return existingTerminalResult
    }

    let queuedJob
    try {
      queuedJob = await this.requestMediaProbe(clipAssetId)
    }
    catch (error) {
      // Local service profiles may automatically enqueue a probe as upload
      // completion commits. A multi-view upload can finish that probe before
      // this acceptance client reaches the explicit request, so observe the
      // authoritative asset state instead of treating it as a client failure.
      if (!(error instanceof PlatformApiError) || error.errorCode !== 'CLIP_ASSET_NOT_PROBEABLE') {
        throw error
      }
      const terminalAsset = await waitFor(
        () => this.getClipAsset(clipAssetId),
        (value) => value?.status === 'INGESTED' || value?.status === 'INGEST_FAILED',
        { timeoutMs, intervalMs, description: `automatic Media Probe for ${clipAssetId}` },
      )
      return this.#readCompletedMediaProbe(clipAssetId, terminalAsset)
    }
    if (!queuedJob?.media_job_id) {
      throw new Error('Media Probe response did not contain media_job_id')
    }
    const job = await waitFor(
      () => this.getMediaJob(queuedJob.media_job_id),
      (value) => TERMINAL_MEDIA_STATUSES.has(value?.status),
      { timeoutMs, intervalMs, description: `Media Probe ${queuedJob.media_job_id}` },
    )
    const asset = await this.getClipAsset(clipAssetId)
    if (job.status === 'SUCCEEDED') {
      await this.getMediaReport(clipAssetId)
      if (asset.status !== 'INGESTED') {
        throw new Error(`Media Probe succeeded but ClipAsset status is ${asset.status}`)
      }
    }
    else if (asset.status !== 'INGEST_FAILED' && asset.status !== 'INGESTING') {
      throw new Error(`Media Probe failed but ClipAsset status is ${asset.status}`)
    }
    return { job, asset }
  }

  async #readCompletedMediaProbe(clipAssetId, asset) {
    if (asset?.status === 'INGESTED') {
      await this.getMediaReport(clipAssetId)
      return { job: { status: 'SUCCEEDED' }, asset }
    }
    if (asset?.status === 'INGEST_FAILED') {
      return { job: { status: 'FAILED' }, asset }
    }
    return null
  }

  async waitForHandPose(clipAssetId, expectedStatus, {
    timeoutMs = 120_000,
    intervalMs = 1_000,
    expectedHandResult = null,
  } = {}) {
    const queuedJob = await this.requestHandPose(clipAssetId)
    if (!queuedJob?.ai_job_id) {
      throw new Error('AI Candidate response did not contain ai_job_id')
    }
    const job = await waitFor(
      () => this.getAiJob(queuedJob.ai_job_id),
      (value) => TERMINAL_CANDIDATE_STATUSES.has(value?.status),
      { timeoutMs, intervalMs, description: `AI Candidate ${queuedJob.ai_job_id}` },
    )
    if (job.status !== expectedStatus) {
      throw new Error(`AI Candidate status mismatch: expected ${expectedStatus}, received ${job.status}`)
    }
    if (expectedHandResult !== null && job.hand_result !== expectedHandResult) {
      throw new Error(`AI Candidate hand result mismatch: expected ${expectedHandResult}, received ${job.hand_result}`)
    }
    if (expectedStatus === 'SUCCEEDED') {
      const candidates = await this.getHandPoseCandidates(clipAssetId)
      if (expectedStatus === 'SUCCEEDED' && candidates?.job?.status !== 'SUCCEEDED') {
        throw new Error('AI Candidate readback did not expose the succeeded job')
      }
      return { job, candidates }
    }
    return { job, candidates: null }
  }

  async #requestJson(operation, path, options = {}) {
    const response = await this.#request(operation, path, options)
    const text = await response.text()
    if (!text) {
      return null
    }
    try {
      return JSON.parse(text)
    }
    catch {
      throw new Error(`${operation} returned non-JSON content`)
    }
  }

  async #request(operation, path, {
    method = 'GET',
    role = 'member',
    body,
    idempotencyKey,
    requestId: correlationId,
    headers: extraHeaders = {},
    accept = 'application/json, application/problem+json',
  } = {}) {
    const token = role === 'admin' ? this.adminBearerToken : this.bearerToken
    if (!token) {
      throw new TypeError('DEV_ADMIN_BEARER_TOKEN is required for administrator operations')
    }
    const headers = {
      Accept: accept,
      Authorization: `Bearer ${token}`,
      ...extraHeaders,
    }
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json'
    }
    if (idempotencyKey) {
      headers['Idempotency-Key'] = idempotencyKey
    }
    if (correlationId) {
      headers['X-Request-Id'] = correlationId
    }
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (response.ok) {
      return response
    }
    const text = await response.text()
    let problem = null
    if (text) {
      try {
        problem = JSON.parse(text)
      }
      catch {
        problem = null
      }
    }
    throw new PlatformApiError(operation, response.status, problem)
  }
}

export async function waitFor(read, isComplete, {
  timeoutMs = 120_000,
  intervalMs = 1_000,
  description = 'operation',
} = {}) {
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0 || !Number.isFinite(intervalMs) || intervalMs <= 0) {
    throw new RangeError('waitFor timeout and interval must be positive numbers')
  }
  const deadline = Date.now() + timeoutMs
  let current = await read()
  while (!isComplete(current)) {
    const remaining = deadline - Date.now()
    if (remaining <= 0) {
      throw new Error(`${description} did not reach a terminal state within ${timeoutMs}ms`)
    }
    await new Promise((resolve) => setTimeout(resolve, Math.min(intervalMs, remaining)))
    current = await read()
  }
  return current
}
