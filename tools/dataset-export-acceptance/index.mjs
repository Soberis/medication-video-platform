import { createHash, randomUUID } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { isDeepStrictEqual, parseEnv } from 'node:util'
import { fileURLToPath } from 'node:url'

import { generateSyntheticVideo } from '../synthetic-upload-client/synthetic-video.mjs'
import { SyntheticUploadClient } from '../synthetic-upload-client/upload-client.mjs'

const UPLOADER_ACTOR_ID = 'local-synthetic-uploader'
const ADMINISTRATOR_ACTOR_ID = 'local-synthetic-admin'
const MEDIA_INGEST_TIMEOUT_MS = 120_000
const EXPORT_TIMEOUT_MS = 180_000
const POLL_INTERVAL_MS = 1_000
const DATASET_EXPORT_DEAD_LETTER_SCHEMA = 'dataset-export-dead-letter.v1'
const DATASET_EXPORT_DEAD_LETTER_EXCHANGE = 'platform.dead-letter.v1'
const DATASET_EXPORT_DEAD_LETTER_ROUTING_KEY = 'dataset-export.dead-letter.v1'
const DATASET_EXPORT_DEAD_LETTER_QUEUE = 'platform.dataset-export.dead-letter.v1'
const DLQ_REPLAYABLE_REASONS = new Set([
  'DATASET_EXPORT_JOB_PROFILE_UNSUPPORTED',
  'DATASET_EXPORT_JOB_ACQUIRE_REJECTED',
  'DATASET_EXPORT_JOB_LEASE_REJECTED',
  'DATASET_EXPORT_JOB_RESULT_REJECTED',
])
const DLQ_SAFE_SUMMARIES = {
  DATASET_EXPORT_JOB_PROFILE_UNSUPPORTED: 'Message requested an export profile without a producer',
  DATASET_EXPORT_JOB_ACQUIRE_REJECTED: 'Worker authorization or export lease was rejected',
  DATASET_EXPORT_JOB_LEASE_REJECTED: 'Current dataset export lease was rejected',
  DATASET_EXPORT_JOB_RESULT_REJECTED: 'Dataset export result submission was rejected',
}
const DLQ_DIAGNOSTIC_FIELDS = new Set([
  'schema_version',
  'dead_letter_id',
  'observed_at',
  'reason_code',
  'safe_summary',
  'body_sha256',
  'body_size_bytes',
  'message_id',
  'export_id',
  'attempt',
])
const DLQ_OPERATOR_REASONS = new Set([
  'CONFIGURATION_FIXED',
  'WORKER_DEPLOYMENT_FIXED',
  'AUTHORIZATION_FIXED',
  'OTHER_APPROVED',
])
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const CANONICAL_UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const SHA256_PATTERN = /^[a-f0-9]{64}$/
const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9._:-]{16,128}$/
const NAMESPACE_URL_UUID = '6ba7b811-9dad-11d1-80b4-00c04fd430c8'

export function parseAcceptanceMode(argv) {
  if (argv.length === 0) return { recovery: false, leaseLoss: false }
  if (argv.length === 1 && argv[0] === '--recovery') {
    return { recovery: true, leaseLoss: false }
  }
  if (argv.length === 1 && argv[0] === '--lease-loss-recovery') {
    return { recovery: true, leaseLoss: true }
  }
  if (argv.length === 1 && argv[0] === '--dlq-replay') {
    return { recovery: false, leaseLoss: false, dlqReplay: true }
  }
  if (argv.length === 1 && argv[0] === '--dlq-prepare') {
    return { recovery: false, leaseLoss: false, dlqPrepare: true }
  }
  throw new Error(
    'Usage: dataset-export-acceptance [--recovery|--lease-loss-recovery|--dlq-prepare|--dlq-replay]',
  )
}

export function assertDlqPrepareEnvironment({
  enabled,
  normalAcceptanceEnabled,
  dataProfile,
  baseUrl,
  rabbitmqUrl = 'amqp://localhost:5672/medication',
  adminBearerToken,
  uploaderBearerToken,
  projectId,
}) {
  if (enabled !== 'true') {
    throw new Error(
      'Set RUN_DATASET_EXPORT_DLQ_REPLAY_ACCEPTANCE=true to authorize this synthetic DLQ preparation',
    )
  }
  if (normalAcceptanceEnabled !== 'true') {
    throw new Error(
      'Set RUN_DATASET_EXPORT_ACCEPTANCE=true before preparing the synthetic DLQ replay fixture',
    )
  }
  const environment = assertAcceptanceEnvironment({
    enabled: 'true',
    dataProfile,
    baseUrl,
    rabbitmqUrl,
    adminBearerToken,
    uploaderBearerToken,
  })
  return {
    ...environment,
    projectId: requireCanonicalUuid(projectId, 'DEV_PROJECT_ID'),
  }
}

export function assertDlqReplayEnvironment({
  enabled,
  normalAcceptanceEnabled,
  dataProfile,
  baseUrl,
  rabbitmqUrl = 'amqp://localhost:5672/medication',
  rabbitmqManagementUrl = 'http://localhost:15672',
  rabbitmqManagementUsername,
  rabbitmqManagementPassword,
  adminBearerToken,
  exportId,
  projectId,
  datasetVersionId,
  operatorReasonCode,
  changeReference,
  idempotencyKey,
  deadLetterQueue = DATASET_EXPORT_DEAD_LETTER_QUEUE,
}) {
  if (enabled !== 'true') {
    throw new Error(
      'Set RUN_DATASET_EXPORT_DLQ_REPLAY_ACCEPTANCE=true to authorize this synthetic DLQ replay acceptance',
    )
  }
  if (normalAcceptanceEnabled !== 'true') {
    throw new Error(
      'Set RUN_DATASET_EXPORT_ACCEPTANCE=true before running the synthetic DLQ replay acceptance',
    )
  }
  if (dataProfile !== 'synthetic') {
    throw new Error('Dataset export DLQ replay acceptance requires DATA_PROFILE=synthetic')
  }
  if (!adminBearerToken) {
    throw new Error('DEV_ADMIN_BEARER_TOKEN is required')
  }
  const parsedBaseUrl = parseLoopbackUrl(baseUrl, ['http:', 'https:'], 'PLATFORM_BASE_URL')
  const parsedRabbitmqUrl = parseLoopbackUrl(rabbitmqUrl, ['amqp:', 'amqps:'], 'RABBITMQ_URL')
  const parsedManagementUrl = parseLoopbackUrl(
    rabbitmqManagementUrl,
    ['http:', 'https:'],
    'RABBITMQ_MANAGEMENT_URL',
  )
  if (!rabbitmqManagementUsername || !rabbitmqManagementPassword) {
    throw new Error('RABBITMQ_USER and RABBITMQ_PASSWORD are required for the local management API')
  }
  requireCanonicalUuid(projectId, 'DLQ replay project')
  requireUuid(datasetVersionId, 'DLQ replay dataset version')
  requireUuid(exportId, 'DLQ replay export')
  if (!DLQ_OPERATOR_REASONS.has(operatorReasonCode)) {
    throw new Error(
      'DATASET_EXPORT_DLQ_REPLAY_REASON_CODE must be CONFIGURATION_FIXED, WORKER_DEPLOYMENT_FIXED, AUTHORIZATION_FIXED, or OTHER_APPROVED',
    )
  }
  if (
    typeof changeReference !== 'string' ||
    !/^[A-Za-z0-9][A-Za-z0-9._:/#-]{2,127}$/.test(changeReference)
  ) {
    throw new Error(
      'DATASET_EXPORT_DLQ_REPLAY_CHANGE_REFERENCE must be 3 to 128 safe non-sensitive characters',
    )
  }
  if (typeof idempotencyKey !== 'string' || !IDEMPOTENCY_KEY_PATTERN.test(idempotencyKey)) {
    throw new Error(
      'DATASET_EXPORT_DLQ_REPLAY_IDEMPOTENCY_KEY must match ^[A-Za-z0-9._:-]{16,128}$',
    )
  }
  requireQueueName(deadLetterQueue, 'DATASET_EXPORT_DLQ_QUEUE')
  return {
    baseUrl: parsedBaseUrl.toString().replace(/\/$/, ''),
    rabbitmqUrl: parsedRabbitmqUrl.toString(),
    rabbitmqManagementUrl: parsedManagementUrl.toString().replace(/\/$/, ''),
    rabbitmqManagementUsername,
    rabbitmqManagementPassword,
    adminBearerToken,
    exportId,
    projectId,
    datasetVersionId,
    operatorReasonCode,
    changeReference,
    idempotencyKey,
    deadLetterQueue,
  }
}

export function assertAcceptanceEnvironment({
  enabled,
  dataProfile,
  baseUrl,
  rabbitmqUrl = 'amqp://localhost:5672/medication',
  adminBearerToken,
  uploaderBearerToken,
  leaseLoss = false,
  leaseLossAuthorized,
}) {
  if (enabled !== 'true') {
    throw new Error(
      'Set RUN_DATASET_EXPORT_ACCEPTANCE=true to authorize this synthetic export acceptance',
    )
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
  if (
    !['http:', 'https:'].includes(parsedBaseUrl.protocol) ||
    !isLoopbackHost(parsedBaseUrl.hostname)
  ) {
    throw new Error('Dataset export acceptance only permits a localhost Core API')
  }

  let parsedRabbitmqUrl
  try {
    parsedRabbitmqUrl = new URL(rabbitmqUrl)
  } catch {
    throw new Error('RABBITMQ_URL must be a valid AMQP URL')
  }
  if (
    !['amqp:', 'amqps:'].includes(parsedRabbitmqUrl.protocol) ||
    !isLoopbackHost(parsedRabbitmqUrl.hostname)
  ) {
    throw new Error('Dataset export acceptance only permits a localhost RabbitMQ')
  }
  if (leaseLoss && leaseLossAuthorized !== 'true') {
    throw new Error('Lease-loss acceptance requires RUN_DATASET_EXPORT_LEASE_LOSS_ACCEPTANCE=true')
  }
  if (!leaseLoss && leaseLossAuthorized === 'true') {
    throw new Error('Lease-loss authorization requires --lease-loss-recovery')
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

export function requireMediaProbeJob(mediaProbe) {
  const mediaJobId = requireUuid(mediaProbe?.media_job_id, 'Synthetic media probe')
  if (!['REQUESTED', 'QUEUED', 'RUNNING', 'SUCCEEDED'].includes(mediaProbe.status)) {
    throw new Error('Synthetic media probe did not enter an executable state')
  }
  return mediaJobId
}

export function requireActorIdentity(value, expectedActorId, label) {
  if (value !== expectedActorId) {
    throw new Error(`${label} does not identify the expected administrator`)
  }
  return value
}

export function requireMemberPermissionDenied(error, label) {
  if (error?.status !== 403 || error?.errorCode !== 'PROJECT_ACCESS_DENIED') {
    throw new Error(`${label} did not fail with HTTP 403 (PROJECT_ACCESS_DENIED)`)
  }
  return true
}

export function requireUnchangedState(before, after, label) {
  if (!isDeepStrictEqual(before, after)) {
    throw new Error(`${label} changed state after the rejected member command`)
  }
  return true
}

export function matchesExpectedExportCompletion(exportRequest, recovery, observedStatuses) {
  if (exportRequest?.status === 'FAILED') {
    throw new Error('Dataset export Worker reported FAILED')
  }
  if (exportRequest?.status !== 'AVAILABLE') return false
  if (!recovery) return true
  if (!observedStatuses.includes('RETRY_WAIT')) {
    throw new Error('Recovery acceptance did not observe RETRY_WAIT')
  }
  if (exportRequest.attempt?.attempt !== 2) {
    throw new Error('Recovery acceptance did not complete on the second Worker attempt')
  }
  return true
}

export function requireLeaseLossRecovery(exportRequest) {
  if (exportRequest?.status !== 'AVAILABLE') {
    throw new Error('Lease-loss recovery did not reach AVAILABLE')
  }
  if (exportRequest.attempt?.attempt !== 2) {
    throw new Error('Lease-loss recovery did not complete on the second Worker attempt')
  }
  return true
}

export function sha256Hex(bytes) {
  return createHash('sha256').update(bytes).digest('hex')
}

export function uuidV5(namespace, name) {
  const namespaceBytes = Buffer.from(namespace.replaceAll('-', ''), 'hex')
  if (namespaceBytes.length !== 16 || typeof name !== 'string') {
    throw new Error('UUIDv5 requires a UUID namespace and string name')
  }
  const digest = createHash('sha1').update(namespaceBytes).update(name, 'utf8').digest()
  digest[6] = (digest[6] & 0x0f) | 0x50
  digest[8] = (digest[8] & 0x3f) | 0x80
  return formatUuid(digest.subarray(0, 16))
}

export function requireDatasetExportDeadLetterDiagnostic(message) {
  if (!message || typeof message !== 'object') {
    throw new Error('RabbitMQ DLQ response did not contain a diagnostic message')
  }
  if (message.exchange !== DATASET_EXPORT_DEAD_LETTER_EXCHANGE) {
    throw new Error('RabbitMQ message came from an unexpected dataset export DLQ exchange')
  }
  if (message.routing_key !== DATASET_EXPORT_DEAD_LETTER_ROUTING_KEY) {
    throw new Error('RabbitMQ message used an unexpected dataset export DLQ routing key')
  }
  if (message.properties?.type !== DATASET_EXPORT_DEAD_LETTER_SCHEMA) {
    throw new Error('RabbitMQ message type is not dataset-export-dead-letter.v1')
  }
  if (message.properties?.content_type !== 'application/json') {
    throw new Error('RabbitMQ DLQ diagnostic must use application/json')
  }
  if (typeof message.payload !== 'string') {
    throw new Error('RabbitMQ DLQ diagnostic payload is not text JSON')
  }
  let diagnostic
  try {
    diagnostic = JSON.parse(message.payload)
  } catch {
    throw new Error('RabbitMQ DLQ diagnostic is not valid JSON')
  }
  if (!diagnostic || typeof diagnostic !== 'object' || Array.isArray(diagnostic)) {
    throw new Error('RabbitMQ DLQ diagnostic must be a JSON object')
  }
  const fields = Object.keys(diagnostic)
  if (fields.some((field) => !DLQ_DIAGNOSTIC_FIELDS.has(field))) {
    throw new Error('RabbitMQ DLQ diagnostic contains a forbidden or unknown field')
  }
  const requiredFields = [
    'schema_version',
    'dead_letter_id',
    'observed_at',
    'reason_code',
    'safe_summary',
    'body_sha256',
    'body_size_bytes',
    'message_id',
    'export_id',
    'attempt',
  ]
  if (requiredFields.some((field) => !Object.hasOwn(diagnostic, field))) {
    throw new Error('RabbitMQ DLQ diagnostic is missing a required safe field')
  }
  if (diagnostic.schema_version !== DATASET_EXPORT_DEAD_LETTER_SCHEMA) {
    throw new Error('RabbitMQ DLQ diagnostic schema is not dataset-export-dead-letter.v1')
  }
  if (!DLQ_REPLAYABLE_REASONS.has(diagnostic.reason_code)) {
    throw new Error('RabbitMQ DLQ diagnostic reason is not replayable')
  }
  requireUuid(diagnostic.dead_letter_id, 'DLQ dead_letter_id')
  requireUuid(diagnostic.message_id, 'DLQ message_id')
  requireUuid(diagnostic.export_id, 'DLQ export_id')
  if (typeof diagnostic.safe_summary !== 'string' || diagnostic.safe_summary.length === 0) {
    throw new Error('RabbitMQ DLQ diagnostic safe_summary is invalid')
  }
  if (diagnostic.safe_summary !== DLQ_SAFE_SUMMARIES[diagnostic.reason_code]) {
    throw new Error('RabbitMQ DLQ diagnostic safe_summary does not match its stable reason')
  }
  if (!SHA256_PATTERN.test(diagnostic.body_sha256)) {
    throw new Error('RabbitMQ DLQ diagnostic body_sha256 is invalid')
  }
  if (!Number.isSafeInteger(diagnostic.body_size_bytes) || diagnostic.body_size_bytes <= 0) {
    throw new Error('RabbitMQ DLQ diagnostic body_size_bytes is invalid')
  }
  if (!Number.isSafeInteger(diagnostic.attempt) || diagnostic.attempt < 1) {
    throw new Error('RabbitMQ DLQ diagnostic attempt is invalid')
  }
  if (typeof diagnostic.observed_at !== 'string' || !isRfc3339(diagnostic.observed_at)) {
    throw new Error('RabbitMQ DLQ diagnostic observed_at is invalid')
  }
  const expectedDeadLetterId = uuidV5(
    NAMESPACE_URL_UUID,
    `${DATASET_EXPORT_DEAD_LETTER_SCHEMA}:${diagnostic.reason_code}:${diagnostic.body_sha256}`,
  )
  if (diagnostic.dead_letter_id.toLowerCase() !== expectedDeadLetterId.toLowerCase()) {
    throw new Error('RabbitMQ DLQ diagnostic dead_letter_id does not match UUIDv5 identity')
  }
  if (
    typeof message.properties?.message_id !== 'string' ||
    message.properties.message_id.toLowerCase() !== diagnostic.dead_letter_id.toLowerCase()
  ) {
    throw new Error('RabbitMQ DLQ message properties do not match dead_letter_id')
  }
  return {
    deadLetterId: diagnostic.dead_letter_id,
    reasonCode: diagnostic.reason_code,
    bodySha256: diagnostic.body_sha256,
    sourceMessageId: diagnostic.message_id,
    exportId: diagnostic.export_id,
    sourceAttempt: diagnostic.attempt,
  }
}

export function buildDatasetExportReplayRequest(diagnostic, operatorReasonCode, changeReference) {
  const safeDiagnostic = requireSafeReplayDiagnostic(diagnostic)
  if (!DLQ_OPERATOR_REASONS.has(operatorReasonCode)) {
    throw new Error('DLQ replay operator reason code is invalid')
  }
  if (
    typeof changeReference !== 'string' ||
    !/^[A-Za-z0-9][A-Za-z0-9._:/#-]{2,127}$/.test(changeReference)
  ) {
    throw new Error('DLQ replay change reference is invalid')
  }
  return {
    dead_letter_id: safeDiagnostic.deadLetterId,
    source_message_id: safeDiagnostic.sourceMessageId,
    source_attempt: safeDiagnostic.sourceAttempt,
    dead_letter_reason: safeDiagnostic.reasonCode,
    body_sha256: safeDiagnostic.bodySha256,
    operator_reason_code: operatorReasonCode,
    change_reference: changeReference,
  }
}

export function requireDatasetExportReplayResponse(response, diagnostic) {
  const safeDiagnostic = requireSafeReplayDiagnostic(diagnostic)
  if (!response || typeof response !== 'object') {
    throw new Error('Core API replay response is invalid')
  }
  requireUuid(response.replay_id, 'Dataset export replay')
  requireUuid(response.dataset_export_id, 'Dataset export replay export')
  if (response.dataset_export_id.toLowerCase() !== safeDiagnostic.exportId.toLowerCase()) {
    throw new Error('Core API replay response export does not match the DLQ diagnostic')
  }
  if (response.dead_letter_id.toLowerCase() !== safeDiagnostic.deadLetterId.toLowerCase()) {
    throw new Error('Core API replay response dead_letter_id does not match the DLQ diagnostic')
  }
  if (response.source_attempt !== safeDiagnostic.sourceAttempt) {
    throw new Error('Core API replay response source attempt does not match the DLQ diagnostic')
  }
  if (response.new_attempt !== safeDiagnostic.sourceAttempt + 1) {
    throw new Error('Core API replay did not queue exactly the next attempt')
  }
  if (response.resulting_export_status !== 'QUEUED') {
    throw new Error('Core API replay did not leave the export QUEUED')
  }
  return response
}

export function requireDatasetExportReplayPreflight(
  response,
  expectedExportId,
  expectedAttempt = 1,
) {
  requireUuid(expectedExportId, 'Dataset export preflight expected export')
  const exportRequest = response?.export_request
  if (!exportRequest || typeof exportRequest !== 'object') {
    throw new Error('Dataset export preflight response is missing export_request')
  }
  requireUuid(exportRequest.dataset_export_id, 'Dataset export preflight export')
  if (exportRequest.dataset_export_id.toLowerCase() !== expectedExportId.toLowerCase()) {
    throw new Error('Dataset export preflight export does not match the requested replay export')
  }
  if (!['QUEUED', 'RUNNING'].includes(exportRequest.status)) {
    throw new Error('Dataset export preflight requires a queued or running export')
  }
  if (exportRequest.attempt?.attempt !== expectedAttempt) {
    throw new Error('Dataset export preflight requires the first Worker attempt')
  }
  if (!['QUEUED', 'RUNNING'].includes(exportRequest.attempt?.status)) {
    throw new Error('Dataset export preflight requires a queued or running first attempt')
  }
  if (exportRequest.artifact != null) {
    throw new Error('Dataset export preflight refuses an export that already has an artifact')
  }
  return exportRequest
}

export function requireDatasetExportReplayCompletion(response, expectedExportId, expectedAttempt) {
  requireUuid(expectedExportId, 'Dataset export completion expected export')
  const exportRequest = response?.export_request
  if (!exportRequest || typeof exportRequest !== 'object') {
    throw new Error('Dataset export status response is missing export_request')
  }
  if (
    typeof exportRequest.dataset_export_id !== 'string' ||
    exportRequest.dataset_export_id.toLowerCase() !== expectedExportId.toLowerCase()
  ) {
    throw new Error('Dataset export status response does not match the replayed export')
  }
  if (exportRequest.status === 'FAILED') {
    throw new Error('Dataset export replay Worker reported FAILED')
  }
  if (exportRequest.status !== 'AVAILABLE') return false
  if (exportRequest.attempt?.attempt !== expectedAttempt) {
    throw new Error('Dataset export replay did not complete on the next Worker attempt')
  }
  if (exportRequest.attempt?.status !== 'SUCCEEDED') {
    throw new Error('Dataset export replay current attempt is not SUCCEEDED')
  }
  const artifact = requireAvailableArtifact(exportRequest)
  return { exportRequest, artifact }
}

export function requireDatasetExportDlqReplayReason(reasonCode) {
  if (reasonCode !== 'DATASET_EXPORT_JOB_ACQUIRE_REJECTED') {
    throw new Error(
      'Dataset export DLQ replay acceptance requires DATASET_EXPORT_JOB_ACQUIRE_REJECTED',
    )
  }
  return reasonCode
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

class RabbitManagementClient {
  constructor({ baseUrl, username, password, vhost, deadLetterQueue }) {
    this.baseUrl = baseUrl
    this.username = username
    this.password = password
    this.vhost = vhost
    this.deadLetterQueue = deadLetterQueue
  }

  async getRetainedDeadLetter() {
    const messages = await this.getQueueMessages(this.deadLetterQueue)
    if (messages.length !== 1 || messages[0].message_count !== 0) {
      throw new Error(
        'Dataset export DLQ must contain exactly one retained diagnostic and no additional messages',
      )
    }
    return requireDatasetExportDeadLetterDiagnostic(messages[0])
  }

  async getQueueMessages(queue) {
    const encodedVhost = encodeURIComponent(this.vhost)
    const encodedQueue = encodeURIComponent(queue)
    const response = await fetch(`${this.baseUrl}/api/queues/${encodedVhost}/${encodedQueue}/get`, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        Authorization: `Basic ${Buffer.from(`${this.username}:${this.password}`).toString('base64')}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ count: 1, ackmode: 'ack_requeue_true', encoding: 'auto' }),
      signal: AbortSignal.timeout(10_000),
    })
    const payload = await readJson(response, 'RabbitMQ management queue read')
    if (!response.ok) {
      throw new Error(`RabbitMQ management queue read failed with HTTP ${response.status}`)
    }
    if (!Array.isArray(payload)) {
      throw new Error('RabbitMQ management queue read returned an invalid payload')
    }
    return payload
  }
}

async function runDlqReplayAcceptance(environment) {
  const startedAt = Date.now()
  const evidence = {
    schema_version: 'dataset-export-dlq-replay-acceptance-v1',
    project_id: environment.projectId,
    dataset_version_id: environment.datasetVersionId,
    source_attempt: null,
    new_attempt: null,
    dead_letter_id: null,
    dataset_export_id: null,
    old_attempt_status: 'NOT_OBSERVED_BY_PUBLIC_API',
    export_status: null,
    artifact_count: null,
    artifact_size_bytes: null,
    artifact_sha256: null,
    outcome: 'FAILED',
  }
  const administrator = new CoreApiClient({
    baseUrl: environment.baseUrl,
    bearerToken: environment.adminBearerToken,
  })
  const management = new RabbitManagementClient({
    baseUrl: environment.rabbitmqManagementUrl,
    username: environment.rabbitmqManagementUsername,
    password: environment.rabbitmqManagementPassword,
    vhost: decodeURIComponent(new URL(environment.rabbitmqUrl).pathname.replace(/^\//, '')) || '/',
    deadLetterQueue: environment.deadLetterQueue,
  })
  try {
    const diagnostic = await management.getRetainedDeadLetter()
    evidence.dead_letter_id = diagnostic.deadLetterId
    evidence.dataset_export_id = diagnostic.exportId
    evidence.source_attempt = diagnostic.sourceAttempt
    if (diagnostic.exportId.toLowerCase() !== environment.exportId.toLowerCase()) {
      throw new Error(
        'Retained dataset export DLQ diagnostic does not match DATASET_EXPORT_DLQ_REPLAY_EXPORT_ID',
      )
    }
    if (diagnostic.sourceAttempt !== 1) {
      throw new Error('Dataset export DLQ replay acceptance only supports source attempt 1')
    }
    requireDatasetExportDlqReplayReason(diagnostic.reasonCode)

    const preflight = await administrator.request(
      'preflight retained dataset export dead letter',
      `/projects/${environment.projectId}/dataset-versions/${environment.datasetVersionId}`,
    )
    const alreadyCompleted = requireDatasetExportReplayCompletion(
      preflight,
      diagnostic.exportId,
      diagnostic.sourceAttempt + 1,
    )
    if (alreadyCompleted === false) {
      requireDatasetExportReplayPreflight(preflight, environment.exportId, diagnostic.sourceAttempt)
    }

    const replayRequest = buildDatasetExportReplayRequest(
      diagnostic,
      environment.operatorReasonCode,
      environment.changeReference,
    )
    const replayResponse = await administrator.request(
      'replay retained dataset export dead letter',
      `/dataset-exports/${diagnostic.exportId}/replays`,
      {
        method: 'POST',
        idempotencyKey: environment.idempotencyKey,
        body: replayRequest,
      },
    )
    requireDatasetExportReplayResponse(replayResponse, diagnostic)
    evidence.new_attempt = replayResponse.new_attempt

    const completed = alreadyCompleted || requireDatasetExportReplayCompletion(
      await waitFor(
        'dataset export DLQ replay completion',
        async () =>
          administrator.request(
            'read replayed dataset export status',
            `/projects/${environment.projectId}/dataset-versions/${environment.datasetVersionId}`,
          ),
        (response) =>
          requireDatasetExportReplayCompletion(
            response,
            diagnostic.exportId,
            diagnostic.sourceAttempt + 1,
          ),
        EXPORT_TIMEOUT_MS,
      ),
      diagnostic.exportId,
      diagnostic.sourceAttempt + 1,
    )
    evidence.export_status = completed.exportRequest.status
    evidence.artifact_count = completed.exportRequest.artifact ? 1 : 0
    evidence.artifact_size_bytes = completed.artifact.size_bytes
    evidence.artifact_sha256 = completed.artifact.sha256
    if (completed.artifact.size_bytes <= 0 || !SHA256_PATTERN.test(completed.artifact.sha256)) {
      throw new Error('Dataset export DLQ replay artifact is invalid')
    }
    if (evidence.artifact_count !== 1) {
      throw new Error('Dataset export DLQ replay did not leave exactly one artifact')
    }
    evidence.outcome = 'PASSED'
    evidence.duration_ms = Date.now() - startedAt
    process.stdout.write(`DLQ_REPLAY_RESULT ${JSON.stringify(evidence)}\n`)
  } finally {
    if (evidence.outcome !== 'PASSED') {
      evidence.duration_ms = Date.now() - startedAt
      process.stdout.write(`DLQ_REPLAY_EVIDENCE ${JSON.stringify(evidence)}\n`)
    }
  }
}

function requireSafeReplayDiagnostic(diagnostic) {
  if (
    !diagnostic ||
    typeof diagnostic !== 'object' ||
    typeof diagnostic.deadLetterId !== 'string' ||
    typeof diagnostic.reasonCode !== 'string' ||
    typeof diagnostic.bodySha256 !== 'string' ||
    typeof diagnostic.sourceMessageId !== 'string' ||
    typeof diagnostic.exportId !== 'string' ||
    !Number.isSafeInteger(diagnostic.sourceAttempt)
  ) {
    throw new Error('DLQ replay diagnostic is incomplete')
  }
  if (!DLQ_REPLAYABLE_REASONS.has(diagnostic.reasonCode)) {
    throw new Error('DLQ replay diagnostic reason is not replayable')
  }
  if (
    !UUID_PATTERN.test(diagnostic.deadLetterId) ||
    !UUID_PATTERN.test(diagnostic.sourceMessageId)
  ) {
    throw new Error('DLQ replay diagnostic UUID is invalid')
  }
  if (!UUID_PATTERN.test(diagnostic.exportId) || diagnostic.sourceAttempt < 1) {
    throw new Error('DLQ replay diagnostic export identity is invalid')
  }
  if (!SHA256_PATTERN.test(diagnostic.bodySha256)) {
    throw new Error('DLQ replay diagnostic body_sha256 is invalid')
  }
  return diagnostic
}

async function main() {
  loadLocalEnvironment()
  const mode = parseAcceptanceMode(process.argv.slice(2))
  if (mode.dlqReplay) {
    const environment = assertDlqReplayEnvironment({
      enabled: process.env.RUN_DATASET_EXPORT_DLQ_REPLAY_ACCEPTANCE,
      normalAcceptanceEnabled: process.env.RUN_DATASET_EXPORT_ACCEPTANCE,
      dataProfile: process.env.DATA_PROFILE,
      baseUrl: process.env.PLATFORM_BASE_URL || 'http://localhost:8080',
      rabbitmqUrl: process.env.RABBITMQ_URL || 'amqp://localhost:5672/medication',
      rabbitmqManagementUrl:
        process.env.RABBITMQ_MANAGEMENT_URL ||
        `http://localhost:${process.env.RABBITMQ_MANAGEMENT_PORT || '15672'}`,
      rabbitmqManagementUsername: process.env.RABBITMQ_USER,
      rabbitmqManagementPassword: process.env.RABBITMQ_PASSWORD,
      adminBearerToken: process.env.DEV_ADMIN_BEARER_TOKEN,
      projectId: process.env.DATASET_EXPORT_DLQ_REPLAY_PROJECT_ID,
      datasetVersionId: process.env.DATASET_EXPORT_DLQ_REPLAY_DATASET_VERSION_ID,
      exportId: process.env.DATASET_EXPORT_DLQ_REPLAY_EXPORT_ID,
      operatorReasonCode: process.env.DATASET_EXPORT_DLQ_REPLAY_REASON_CODE,
      changeReference: process.env.DATASET_EXPORT_DLQ_REPLAY_CHANGE_REFERENCE,
      idempotencyKey: process.env.DATASET_EXPORT_DLQ_REPLAY_IDEMPOTENCY_KEY,
      deadLetterQueue: process.env.DATASET_EXPORT_DLQ_QUEUE || DATASET_EXPORT_DEAD_LETTER_QUEUE,
    })
    await assertHealthy(environment.baseUrl)
    await runDlqReplayAcceptance(environment)
    return
  }
  const environment = mode.dlqPrepare
    ? assertDlqPrepareEnvironment({
        enabled: process.env.RUN_DATASET_EXPORT_DLQ_REPLAY_ACCEPTANCE,
        normalAcceptanceEnabled: process.env.RUN_DATASET_EXPORT_ACCEPTANCE,
        dataProfile: process.env.DATA_PROFILE,
        baseUrl: process.env.PLATFORM_BASE_URL || 'http://localhost:8080',
        rabbitmqUrl: process.env.RABBITMQ_URL || 'amqp://localhost:5672/medication',
        adminBearerToken: process.env.DEV_ADMIN_BEARER_TOKEN,
        uploaderBearerToken: process.env.DEV_BEARER_TOKEN,
        projectId: process.env.DEV_PROJECT_ID,
      })
    : assertAcceptanceEnvironment({
        enabled: process.env.RUN_DATASET_EXPORT_ACCEPTANCE,
        dataProfile: process.env.DATA_PROFILE,
        baseUrl: process.env.PLATFORM_BASE_URL || 'http://localhost:8080',
        rabbitmqUrl: process.env.RABBITMQ_URL || 'amqp://localhost:5672/medication',
        adminBearerToken: process.env.DEV_ADMIN_BEARER_TOKEN,
        uploaderBearerToken: process.env.DEV_BEARER_TOKEN,
        leaseLoss: mode.leaseLoss,
        leaseLossAuthorized: process.env.RUN_DATASET_EXPORT_LEASE_LOSS_ACCEPTANCE,
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
  const projectId = mode.dlqPrepare
    ? environment.projectId
    : requireUuid(
        (
          await administrator.request('create synthetic acceptance project', '/projects', {
            method: 'POST',
            idempotencyKey: key('dataset-export-acceptance-project'),
            body: { display_name: `Synthetic dataset export acceptance ${randomUUID()}` },
          })
        ).project_id,
        'Synthetic acceptance project',
      )
  const uploaderMembership = await administrator.request(
    'grant synthetic uploader membership',
    `/projects/${projectId}/memberships/${UPLOADER_ACTOR_ID}`,
    {
      method: 'PUT',
      idempotencyKey: key('dataset-export-acceptance-membership'),
      body: { role: 'MEMBER' },
    },
  )
  if (
    uploaderMembership.actor_id !== UPLOADER_ACTOR_ID ||
    uploaderMembership.role !== 'MEMBER' ||
    uploaderMembership.status !== 'ACTIVE'
  ) {
    throw new Error('Synthetic uploader must remain an active project member')
  }

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

    const preparedSessions = []
    for (const [index, session] of uploadedSessions.entries()) {
      preparedSessions.push(
        await prepareEligibleSession({
          administrator,
          uploader,
          projectId,
          session,
          selfAdminApproval: index === 0,
        }),
      )
    }
    const selfAdminAnnotation = preparedSessions.find((session) => session.selfAdminApproval)
    if (!selfAdminAnnotation?.memberReviewPermissionChecked) {
      throw new Error('Synthetic acceptance did not prove the self-admin annotation path')
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
    requireActorIdentity(
      validation.validated_by,
      ADMINISTRATOR_ACTOR_ID,
      'Dataset freeze validation',
    )

    const pendingFreezeDetail = await administrator.request(
      'read pending dataset freeze approval',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}`,
    )
    if (
      pendingFreezeDetail.status !== 'VALIDATING' ||
      pendingFreezeDetail.freeze_approval?.status !== 'PENDING' ||
      pendingFreezeDetail.freeze_approval?.approval_decision_id != null ||
      pendingFreezeDetail.freeze_approval?.approved_by != null
    ) {
      throw new Error('Dataset freeze request did not remain pending before administrator approval')
    }
    const freezeRequestedBy = requireActorIdentity(
      pendingFreezeDetail.freeze_approval.requested_by,
      ADMINISTRATOR_ACTOR_ID,
      'Dataset freeze request',
    )
    const freezeStateBeforeMemberCommand = datasetFreezeDecisionState(pendingFreezeDetail)
    await assertMemberCommandDenied('ordinary member dataset freeze', () =>
      uploader.request(
        'ordinary member dataset freeze',
        `/projects/${projectId}/dataset-versions/${datasetVersionId}/freezes`,
        { method: 'POST', idempotencyKey: key('dataset-export-acceptance-member-freeze') },
      ),
    )
    const freezeDetailAfterMemberCommand = await administrator.request(
      'read dataset freeze state after rejected member command',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}`,
    )
    requireUnchangedState(
      freezeStateBeforeMemberCommand,
      datasetFreezeDecisionState(freezeDetailAfterMemberCommand),
      'Dataset freeze',
    )

    const freeze = await administrator.request(
      'self-approve dataset freeze',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}/freezes`,
      { method: 'POST', idempotencyKey: key('dataset-export-acceptance-freeze') },
    )
    if (freeze.status !== 'FROZEN' || freeze.dataset_version_id !== datasetVersionId) {
      throw new Error('Administrator self-approval did not freeze the dataset version')
    }
    requireActorIdentity(freeze.frozen_by, ADMINISTRATOR_ACTOR_ID, 'Dataset freeze decision')
    const frozenDetail = await administrator.request(
      'read approved dataset freeze',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}`,
    )
    if (
      frozenDetail.status !== 'FROZEN' ||
      frozenDetail.freeze_approval?.status !== 'APPROVED' ||
      frozenDetail.freeze_approval?.approval_request_id !== freeze.approval_request_id ||
      frozenDetail.freeze_approval?.approval_decision_id !== freeze.approval_decision_id
    ) {
      throw new Error('Dataset freeze detail does not expose the approved decision')
    }
    requireActorIdentity(
      frozenDetail.freeze_approval.requested_by,
      ADMINISTRATOR_ACTOR_ID,
      'Approved dataset freeze request',
    )
    const freezeApprovedBy = requireActorIdentity(
      frozenDetail.freeze_approval.approved_by,
      ADMINISTRATOR_ACTOR_ID,
      'Dataset freeze approval',
    )
    if (freezeRequestedBy !== freezeApprovedBy) {
      throw new Error('Dataset freeze was not requested and approved by the same administrator')
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
    const datasetExportId = requireUuid(exportRequest.dataset_export_id, 'Synthetic dataset export')
    const exportRequestedBy = requireActorIdentity(
      exportRequest.requested_by,
      ADMINISTRATOR_ACTOR_ID,
      'Dataset export request',
    )
    requireActorIdentity(
      exportRequest.approval?.requested_by,
      ADMINISTRATOR_ACTOR_ID,
      'Dataset export approval request',
    )
    const exportApprovalRequestId = requireUuid(
      exportRequest.approval?.approval_request_id,
      'Dataset export approval request',
    )
    if (
      exportRequest.approval?.status !== 'PENDING' ||
      exportRequest.approved_by != null ||
      exportRequest.approval?.decided_by != null
    ) {
      throw new Error('Dataset export approval did not remain pending before administrator approval')
    }
    const memberReadiness = await uploader.request(
      'read ordinary member dataset export approval readiness',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}/exports/approval-readiness`,
    )
    if (
      memberReadiness.ready_for_approval !== false ||
      !memberReadiness.blocking_reasons?.includes('APPROVER_ADMIN_REQUIRED')
    ) {
      throw new Error('Ordinary member export approval readiness did not remain blocked')
    }
    const readiness = await administrator.request(
      'read dataset export approval readiness',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}/exports/approval-readiness`,
    )
    if (readiness.ready_for_approval !== true || readiness.blocking_reasons?.length !== 0) {
      throw new Error('Synthetic administrator is not ready to approve the export')
    }
    const exportDetailBeforeMemberCommand = await administrator.request(
      'read dataset export state before rejected member command',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}`,
    )
    const exportStateBeforeMemberCommand = datasetExportDecisionState(exportDetailBeforeMemberCommand)
    await assertMemberCommandDenied('ordinary member dataset export approval', () =>
      uploader.request(
        'ordinary member dataset export approval',
        `/projects/${projectId}/dataset-versions/${datasetVersionId}/exports/approvals`,
        {
          method: 'POST',
          idempotencyKey: key('dataset-export-acceptance-member-export-approval'),
        },
      ),
    )
    const exportDetailAfterMemberCommand = await administrator.request(
      'read dataset export state after rejected member command',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}`,
    )
    requireUnchangedState(
      exportStateBeforeMemberCommand,
      datasetExportDecisionState(exportDetailAfterMemberCommand),
      'Dataset export approval',
    )

    const approvedExport = await administrator.request(
      'self-approve dataset export',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}/exports/approvals`,
      { method: 'POST', idempotencyKey: key('dataset-export-acceptance-export-approval') },
    )
    if (
      approvedExport.status !== 'QUEUED' ||
      approvedExport.dataset_export_id !== datasetExportId ||
      approvedExport.approval?.status !== 'APPROVED' ||
      approvedExport.approval?.approval_request_id !== exportApprovalRequestId ||
      approvedExport.attempt?.attempt !== 1
    ) {
      throw new Error('Dataset export approval did not queue its first Worker attempt')
    }
    const exportApprovedBy = requireActorIdentity(
      approvedExport.approved_by,
      ADMINISTRATOR_ACTOR_ID,
      'Dataset export approval',
    )
    requireActorIdentity(
      approvedExport.approval?.decided_by,
      ADMINISTRATOR_ACTOR_ID,
      'Dataset export approval decision',
    )
    if (
      exportRequestedBy !== exportApprovedBy ||
      exportRequest.approval.requested_by !== approvedExport.approval.decided_by
    ) {
      throw new Error('Dataset export was not requested and approved by the same administrator')
    }

    if (mode.dlqPrepare) {
      process.stdout.write(
        `ACCEPTANCE_SETUP ${JSON.stringify({
          schema_version: 'dataset-export-cross-process-acceptance-setup-v1',
          project_id: projectId,
          dataset_version_id: datasetVersionId,
          dataset_export_id: requireUuid(
            approvedExport.dataset_export_id,
            'Synthetic dataset export',
          ),
          first_attempt: approvedExport.attempt.attempt,
        })}\n`,
      )
      return
    }

    const observedExportStatuses = [approvedExport.status]
    const detail = await waitFor(
      'dataset export Worker completion',
      async () =>
        administrator.request(
          'read dataset export status',
          `/projects/${projectId}/dataset-versions/${datasetVersionId}`,
        ),
      (response) => {
        const status = response.export_request?.status
        if (typeof status === 'string' && !observedExportStatuses.includes(status)) {
          observedExportStatuses.push(status)
        }
        const completed = matchesExpectedExportCompletion(
          response.export_request,
          mode.recovery && !mode.leaseLoss,
          observedExportStatuses,
        )
        if (completed && mode.leaseLoss) {
          return requireLeaseLossRecovery(response.export_request)
        }
        return completed
      },
      EXPORT_TIMEOUT_MS,
    )
    const artifact = requireAvailableArtifact(detail.export_request)
    const authorization = await administrator.request(
      'authorize synthetic export download',
      `/projects/${projectId}/dataset-versions/${datasetVersionId}/exports/download-authorizations`,
      { method: 'POST' },
    )
    const downloaded = await downloadAndDigest(authorization.download_url)
    if (downloaded.sizeBytes !== artifact.size_bytes || downloaded.sha256 !== artifact.sha256) {
      throw new Error('Short-lived export download does not match the registered artifact')
    }

    process.stdout.write(
      `ACCEPTANCE_RESULT ${JSON.stringify({
        schema_version: 'dataset-export-cross-process-acceptance-v2',
        project_id: projectId,
        dataset_version_id: datasetVersionId,
        dataset_export_id: detail.export_request.dataset_export_id,
        capture_session_count: uploadedSessions.length,
        approval_mode: 'SELF_ADMIN_APPROVAL',
        self_admin_approval: {
          administrator_actor_id: ADMINISTRATOR_ACTOR_ID,
          annotation_revision_submitted_by: selfAdminAnnotation.submittedBy,
          annotation_revision_reviewed_by: selfAdminAnnotation.reviewedBy,
          dataset_freeze_requested_by: freezeRequestedBy,
          dataset_freeze_approved_by: freezeApprovedBy,
          dataset_export_requested_by: exportRequestedBy,
          dataset_export_approved_by: exportApprovedBy,
        },
        member_permission_checks: {
          actor_id: uploaderMembership.actor_id,
          role: uploaderMembership.role,
          status: uploaderMembership.status,
          annotation_review_forbidden_without_state_change:
            selfAdminAnnotation.memberReviewPermissionChecked,
          dataset_freeze_forbidden_without_state_change: true,
          dataset_export_approval_forbidden_without_state_change: true,
        },
        recovery_mode: mode.recovery,
        lease_loss_mode: mode.leaseLoss,
        observed_export_statuses: observedExportStatuses,
        worker_attempt: detail.export_request.attempt?.attempt,
        export_status: detail.export_request.status,
        artifact_size_bytes: artifact.size_bytes,
        artifact_sha256: artifact.sha256,
        downloaded_sha256: downloaded.sha256,
        duration_ms: Date.now() - startedAt,
      })}\n`,
    )
  } finally {
    await rm(workDirectory, { recursive: true, force: true })
  }
}

async function prepareEligibleSession({
  administrator,
  uploader,
  projectId,
  session,
  selfAdminApproval,
}) {
  const captureSessionId = requireUuid(session.capture_session_id, 'Synthetic capture session')
  const [clipAssetId] = session.clip_asset_ids
  if (typeof clipAssetId !== 'string') {
    throw new Error('Synthetic upload did not return a clip asset')
  }
  const mediaProbe = await administrator.request(
    'request synthetic media probe',
    `/projects/${projectId}/clip-assets/${clipAssetId}/media-probe-jobs`,
    { method: 'POST', idempotencyKey: key('dataset-export-acceptance-media-probe') },
  )
  requireMediaProbeJob(mediaProbe)
  await waitFor(
    'media ingestion',
    async () =>
      administrator.request(
        'read capture-session admission preview',
        `/projects/${projectId}/capture-sessions/${captureSessionId}/dataset-admission-preview`,
      ),
    (preview) => {
      const streams = preview.streams ?? []
      if (streams.some((stream) => stream.asset_status === 'INGEST_FAILED')) {
        throw new Error('Synthetic media probe reported INGEST_FAILED')
      }
      return streams.length === 1 && streams.every((stream) => stream.asset_status === 'INGESTED')
    },
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
      body: {
        assignee_actor_id: selfAdminApproval ? ADMINISTRATOR_ACTOR_ID : UPLOADER_ACTOR_ID,
      },
    },
  )
  const annotationTaskId = requireUuid(task.annotation_task_id, 'Synthetic annotation task')
  const submitter = selfAdminApproval ? administrator : uploader
  const submittedBy = selfAdminApproval ? ADMINISTRATOR_ACTOR_ID : UPLOADER_ACTOR_ID
  const revision = await submitter.request(
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
  requireActorIdentity(revision.submitted_by, submittedBy, 'Synthetic annotation submission')
  const annotationRevisionId = requireUuid(
    revision.annotation_revision_id,
    'Synthetic annotation revision',
  )
  if (revision.annotation_task_id !== annotationTaskId) {
    throw new Error('Synthetic annotation revision does not match its assigned task')
  }
  let memberReviewPermissionChecked = false
  if (selfAdminApproval) {
    const taskListBeforeMemberCommand = await administrator.request(
      'read annotation task before rejected member review',
      `/projects/${projectId}/annotation-tasks`,
    )
    const reviewStateBeforeMemberCommand = annotationTaskDecisionState(
      taskListBeforeMemberCommand,
      annotationTaskId,
    )
    await assertMemberCommandDenied('ordinary member annotation review', () =>
      uploader.request(
        'ordinary member annotation review',
        `/projects/${projectId}/annotation-revisions/${annotationRevisionId}/review`,
        {
          method: 'POST',
          idempotencyKey: key('dataset-export-acceptance-member-review'),
          body: { decision: 'APPROVED', review_note: 'Rejected member permission check' },
        },
      ),
    )
    const taskListAfterMemberCommand = await administrator.request(
      'read annotation task after rejected member review',
      `/projects/${projectId}/annotation-tasks`,
    )
    requireUnchangedState(
      reviewStateBeforeMemberCommand,
      annotationTaskDecisionState(taskListAfterMemberCommand, annotationTaskId),
      'Annotation review',
    )
    memberReviewPermissionChecked = true
  }

  const review = await administrator.request(
    selfAdminApproval ? 'self-review synthetic annotation' : 'review synthetic annotation',
    `/projects/${projectId}/annotation-revisions/${annotationRevisionId}/review`,
    {
      method: 'POST',
      idempotencyKey: key('dataset-export-acceptance-review'),
      body: { decision: 'APPROVED', review_note: 'Synthetic acceptance review approved' },
    },
  )
  const reviewedBy = requireActorIdentity(
    review.reviewed_by,
    ADMINISTRATOR_ACTOR_ID,
    'Synthetic annotation review',
  )
  if (review.annotation_revision_id !== annotationRevisionId || review.decision !== 'APPROVED') {
    throw new Error('Synthetic annotation review does not approve the submitted revision')
  }
  if (selfAdminApproval && revision.submitted_by !== review.reviewed_by) {
    throw new Error('Synthetic annotation was not submitted and reviewed by the same administrator')
  }
  await waitFor(
    'dataset admission eligibility',
    async () =>
      administrator.request(
        'read eligible capture-session admission',
        `/projects/${projectId}/capture-sessions/${captureSessionId}/dataset-admission-preview`,
      ),
    (preview) => preview.eligible === true,
    MEDIA_INGEST_TIMEOUT_MS,
  )
  return { captureSessionId, selfAdminApproval, submittedBy, reviewedBy, memberReviewPermissionChecked }
}

async function assertMemberCommandDenied(label, command) {
  try {
    await command()
  } catch (error) {
    requireMemberPermissionDenied(error, label)
    return
  }
  throw new Error(`${label} unexpectedly succeeded`)
}

function annotationTaskDecisionState(response, annotationTaskId) {
  const task = response.items?.find((item) => item.annotation_task_id === annotationTaskId)
  if (!task) {
    throw new Error('Synthetic annotation task is missing from the administrator queue')
  }
  return {
    annotation_task_id: task.annotation_task_id,
    status: task.status,
    version: task.version,
    annotation_revision: task.annotation_revision
      ? {
          annotation_revision_id: task.annotation_revision.annotation_revision_id,
          submitted_by: task.annotation_revision.submitted_by,
          review_id: task.annotation_revision.review?.annotation_review_id ?? null,
          review_decision: task.annotation_revision.review?.decision ?? null,
          reviewed_by: task.annotation_revision.review?.reviewed_by ?? null,
          post_cleaning_result_id:
            task.annotation_revision.post_cleaning_result?.cleaning_result_id ?? null,
        }
      : null,
  }
}

function datasetFreezeDecisionState(detail) {
  return {
    status: detail.status,
    validation_id: detail.validation?.dataset_version_validation_id ?? null,
    validation_outcome: detail.validation?.outcome ?? null,
    approval_request_id: detail.freeze_approval?.approval_request_id ?? null,
    approval_status: detail.freeze_approval?.status ?? null,
    approval_decision_id: detail.freeze_approval?.approval_decision_id ?? null,
    requested_by: detail.freeze_approval?.requested_by ?? null,
    approved_by: detail.freeze_approval?.approved_by ?? null,
    can_freeze: detail.can_freeze,
  }
}

function datasetExportDecisionState(detail) {
  const exportRequest = detail.export_request
  return {
    status: detail.status,
    dataset_export_id: exportRequest?.dataset_export_id ?? null,
    export_status: exportRequest?.status ?? null,
    export_requested_by: exportRequest?.requested_by ?? null,
    export_approved_by: exportRequest?.approved_by ?? null,
    approval_request_id: exportRequest?.approval?.approval_request_id ?? null,
    approval_status: exportRequest?.approval?.status ?? null,
    approval_decision_id: exportRequest?.approval?.approval_decision_id ?? null,
    approval_requested_by: exportRequest?.approval?.requested_by ?? null,
    approval_decided_by: exportRequest?.approval?.decided_by ?? null,
    attempt_id: exportRequest?.attempt?.dataset_export_attempt_id ?? null,
    attempt_number: exportRequest?.attempt?.attempt ?? null,
    attempt_status: exportRequest?.attempt?.status ?? null,
    can_request_export: detail.can_request_export,
  }
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
  if (
    !['http:', 'https:'].includes(parsedDownloadUrl.protocol) ||
    !isLoopbackHost(parsedDownloadUrl.hostname)
  ) {
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
  const exampleEnvironment = readEnvironmentFile(path.join(repositoryRoot, '.env.example'))
  const localEnvironment = readEnvironmentFile(path.join(repositoryRoot, '.env'))
  const resolvedEnvironment = mergeEnvironment(exampleEnvironment, localEnvironment, process.env)
  for (const name of new Set([
    ...Object.keys(exampleEnvironment),
    ...Object.keys(localEnvironment),
  ])) {
    process.env[name] = resolvedEnvironment[name]
  }
}

function readEnvironmentFile(environmentFile) {
  return existsSync(environmentFile) ? parseEnv(readFileSync(environmentFile, 'utf8')) : {}
}

export function mergeEnvironment(exampleEnvironment, localEnvironment, explicitEnvironment) {
  return {
    ...exampleEnvironment,
    ...localEnvironment,
    ...explicitEnvironment,
  }
}

function key(prefix) {
  return `${prefix}-${randomUUID()}`
}

function requireUuid(value, label) {
  if (typeof value !== 'string' || !UUID_PATTERN.test(value)) {
    throw new Error(`${label} is missing a valid UUID`)
  }
  return value
}

function requireCanonicalUuid(value, label) {
  if (typeof value !== 'string' || !CANONICAL_UUID_PATTERN.test(value)) {
    throw new Error(`${label} is missing a valid UUID`)
  }
  return value
}

function parseLoopbackUrl(value, schemes, label) {
  let parsed
  try {
    parsed = new URL(value)
  } catch {
    throw new Error(`${label} must be a valid URL`)
  }
  if (!schemes.includes(parsed.protocol) || !isLoopbackHost(parsed.hostname)) {
    throw new Error(`${label} must use a localhost/loopback address`)
  }
  return parsed
}

function requireQueueName(value, label) {
  if (
    typeof value !== 'string' ||
    value.length < 1 ||
    value.length > 255 ||
    !/^[A-Za-z0-9._:-]+$/.test(value)
  ) {
    throw new Error(`${label} must be a simple local RabbitMQ queue name`)
  }
}

function isRfc3339(value) {
  if (typeof value !== 'string') return false
  const parsed = new Date(value)
  return Number.isFinite(parsed.getTime()) && /(?:Z|[+-]\d{2}:\d{2})$/.test(value)
}

function formatUuid(bytes) {
  const hex = Buffer.from(bytes).toString('hex')
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20, 32),
  ].join('-')
}

function isLoopbackHost(hostname) {
  const normalized = hostname.toLowerCase().replace(/^\[|\]$/g, '')
  return normalized === 'localhost' || normalized === '127.0.0.1' || normalized === '::1'
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

const isEntryPoint =
  process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (isEntryPoint) {
  main().catch((error) => {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 1
  })
}
