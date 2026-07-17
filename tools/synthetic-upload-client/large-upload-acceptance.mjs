import { createHash, randomUUID } from 'node:crypto'
import { spawn } from 'node:child_process'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const TOTAL_SIZE_BYTES = 2 * 1024 * 1024 * 1024
const PART_SIZE_BYTES = 64 * 1024 * 1024
const TOTAL_PARTS = TOTAL_SIZE_BYTES / PART_SIZE_BYTES
const DEVICE_ID = 'synthetic-large-upload-acceptance'
const CONTENT_TYPE = 'video/mp4'

export function buildPaddedMp4Descriptor(prefix, totalSizeBytes, partSizeBytes) {
  if (!Buffer.isBuffer(prefix) || prefix.length === 0) {
    throw new TypeError('Synthetic MP4 prefix must be a non-empty Buffer')
  }
  if (!Number.isSafeInteger(totalSizeBytes) || totalSizeBytes <= prefix.length + 8) {
    throw new RangeError('Total size must leave room for an MP4 free box')
  }
  if (!Number.isSafeInteger(partSizeBytes) || partSizeBytes < 5 * 1024 * 1024) {
    throw new RangeError('Part size must satisfy the S3 multipart minimum')
  }
  const freeBoxSize = totalSizeBytes - prefix.length
  if (freeBoxSize > 0xffffffff) {
    throw new RangeError('The synthetic MP4 free box exceeds its 32-bit size field')
  }
  const freeBoxHeader = Buffer.alloc(8)
  freeBoxHeader.writeUInt32BE(freeBoxSize, 0)
  freeBoxHeader.write('free', 4, 'ascii')
  return {
    totalSizeBytes,
    partSizeBytes,
    totalParts: Math.ceil(totalSizeBytes / partSizeBytes),
    header: Buffer.concat([prefix, freeBoxHeader]),
  }
}

export function createPaddedMp4Part(descriptor, partNumber) {
  if (!Number.isInteger(partNumber) || partNumber < 1 || partNumber > descriptor.totalParts) {
    throw new RangeError('Part number is outside the synthetic payload')
  }
  const offset = (partNumber - 1) * descriptor.partSizeBytes
  const length = Math.min(descriptor.partSizeBytes, descriptor.totalSizeBytes - offset)
  const part = Buffer.alloc(length)
  if (offset < descriptor.header.length) {
    descriptor.header.copy(part, 0, offset, Math.min(offset + length, descriptor.header.length))
  }
  return part
}

export function payloadSha256(descriptor) {
  const hash = createHash('sha256')
  for (let partNumber = 1; partNumber <= descriptor.totalParts; partNumber += 1) {
    hash.update(createPaddedMp4Part(descriptor, partNumber))
  }
  return hash.digest('hex')
}

export function largeUploadManifest({ clientFileId, sha256, totalSizeBytes, partSizeBytes }) {
  return (
    'upload-file-manifest-v1\n' +
    `${clientFileId.toLowerCase()}\tVIDEO\t${CONTENT_TYPE}\t${totalSizeBytes}\t${sha256}\t${partSizeBytes}\n`
  )
}

class AcceptanceApiError extends Error {
  constructor(operation, status, errorCode) {
    super(`${operation} failed with HTTP ${status} (${errorCode})`)
    this.name = 'AcceptanceApiError'
    this.status = status
    this.errorCode = errorCode
  }
}

class AcceptanceApiClient {
  constructor({ baseUrl, bearerToken }) {
    this.baseUrl = new URL(baseUrl).toString().replace(/\/$/, '')
    this.bearerToken = bearerToken
  }

  async request(operation, requestPath, { method, body, idempotencyKey, requestId } = {}) {
    const headers = {
      Accept: 'application/json, application/problem+json',
      Authorization: `Bearer ${this.bearerToken}`,
    }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey
    if (requestId) headers['X-Request-Id'] = requestId
    const response = await fetch(`${this.baseUrl}/api/v1${requestPath}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const responseText = await response.text()
    let payload = null
    if (responseText) {
      try {
        payload = JSON.parse(responseText)
      } catch {
        throw new Error(`${operation} returned non-JSON content with HTTP ${response.status}`)
      }
    }
    if (!response.ok) {
      const errorCode = typeof payload?.error_code === 'string' ? payload.error_code : 'HTTP_ERROR'
      throw new AcceptanceApiError(operation, response.status, errorCode)
    }
    return payload
  }

  async uploadPart(batchId, clientFileId, descriptor, partNumber) {
    const part = createPaddedMp4Part(descriptor, partNumber)
    const sha256 = createHash('sha256').update(part).digest('hex')
    const authorization = await this.request(
      `authorize part ${partNumber}`,
      partPath(batchId, clientFileId, partNumber, 'authorization'),
      { method: 'POST', body: { sha256 } },
    )
    if (authorization.expected_size_bytes !== part.length) {
      throw new Error(`Part ${partNumber} size differs from the server manifest`)
    }
    const response = await fetch(authorization.upload_url, {
      method: 'PUT',
      headers: { 'Content-Length': String(part.length) },
      body: part,
    })
    if (!response.ok) {
      throw new Error(`Part ${partNumber} object upload failed with HTTP ${response.status}`)
    }
  }

  async confirmPart(batchId, clientFileId, partNumber) {
    const deadline = Date.now() + 30_000
    do {
      try {
        return await this.request(
          `confirm part ${partNumber}`,
          partPath(batchId, clientFileId, partNumber, 'confirmation'),
          { method: 'PUT' },
        )
      } catch (error) {
        if (
          !(error instanceof AcceptanceApiError) ||
          error.status !== 409 ||
          error.errorCode !== 'UPLOAD_PART_NOT_FOUND_IN_STORAGE' ||
          Date.now() >= deadline
        ) {
          throw error
        }
        await new Promise((resolve) => setTimeout(resolve, 500))
      }
    } while (Date.now() < deadline)
    throw new Error(`Part ${partNumber} did not become visible before the confirmation deadline`)
  }
}

function partPath(batchId, clientFileId, partNumber, suffix) {
  return `/upload/${batchId}/files/${clientFileId}/parts/${partNumber}/${suffix}`
}

function filePath(batchId, clientFileId, suffix) {
  return `/upload/${batchId}/files/${clientFileId}/${suffix}`
}

function requireEnvironment() {
  if (process.env.RUN_LARGE_UPLOAD_ACCEPTANCE !== 'true') {
    throw new Error('Set RUN_LARGE_UPLOAD_ACCEPTANCE=true to authorize the 2 GiB synthetic test')
  }
  if (!process.env.DEV_BEARER_TOKEN) {
    throw new Error('DEV_BEARER_TOKEN is required')
  }
  return {
    baseUrl: process.env.PLATFORM_BASE_URL || 'http://localhost:8080',
    bearerToken: process.env.DEV_BEARER_TOKEN,
    projectId: process.env.DEV_PROJECT_ID || '00000000-0000-0000-0000-000000000001',
  }
}

async function phaseOne(checkpointPath) {
  const environment = requireEnvironment()
  const prefix = await readFile(path.join(path.dirname(checkpointPath), 'synthetic-prefix.mp4'))
  const descriptor = buildPaddedMp4Descriptor(prefix, TOTAL_SIZE_BYTES, PART_SIZE_BYTES)
  const sha256 = payloadSha256(descriptor)
  const clientFileId = randomUUID()
  const manifestSha256 = createHash('sha256')
    .update(largeUploadManifest({ clientFileId, sha256, totalSizeBytes: TOTAL_SIZE_BYTES, partSizeBytes: PART_SIZE_BYTES }))
    .digest('hex')
  const client = new AcceptanceApiClient(environment)
  const batch = await client.request('initialize large upload batch', '/upload/init', {
    method: 'POST',
    idempotencyKey: `large-upload-batch-${randomUUID()}`,
    body: {
      project_id: environment.projectId,
      device_id: DEVICE_ID,
      file_count: 1,
      total_size_bytes: TOTAL_SIZE_BYTES,
      manifest_sha256: manifestSha256,
    },
  })
  await client.request('register large video', `/upload/${batch.batch_id}/files/${clientFileId}`, {
    method: 'PUT',
    idempotencyKey: `large-upload-file-${randomUUID()}`,
    body: {
      file_kind: 'VIDEO',
      content_type: CONTENT_TYPE,
      size_bytes: TOTAL_SIZE_BYTES,
      sha256,
      part_size_bytes: PART_SIZE_BYTES,
    },
  })
  await client.request('create multipart session', filePath(batch.batch_id, clientFileId, 'multipart'), {
    method: 'PUT',
  })
  await writeFile(
    checkpointPath,
    `${JSON.stringify({
      schema_version: 'large-upload-resume-checkpoint-v1',
      batch_id: batch.batch_id,
      client_file_id: clientFileId,
      sha256,
      total_size_bytes: TOTAL_SIZE_BYTES,
      part_size_bytes: PART_SIZE_BYTES,
      total_parts: TOTAL_PARTS,
    }, null, 2)}\n`,
    { encoding: 'utf8', mode: 0o600 },
  )

  await client.uploadPart(batch.batch_id, clientFileId, descriptor, 1)
  await client.confirmPart(batch.batch_id, clientFileId, 1)
  await client.uploadPart(batch.batch_id, clientFileId, descriptor, 2)
  process.stdout.write(`${JSON.stringify({ phase: 'interrupted', confirmed_parts: [1], uploaded_unconfirmed_parts: [2] })}\n`)
}

async function abortAndAwaitCleanup(client, batchId) {
  const deadline = Date.now() + 120_000
  let response
  do {
    response = await client.request('abort synthetic large upload', `/upload/${batchId}/abort`, {
      method: 'PUT',
      requestId: `large-upload-cleanup-${batchId}`,
    })
    if (response.cleanup_status === 'SUCCEEDED' || response.cleanup_status === 'NOT_REQUIRED') {
      return response
    }
    if (response.cleanup_status === 'FAILED') {
      throw new Error('Synthetic large upload cleanup reached FAILED')
    }
    await new Promise((resolve) => setTimeout(resolve, 2_000))
  } while (Date.now() < deadline)
  throw new Error(`Synthetic large upload cleanup timed out in ${response?.cleanup_status ?? 'UNKNOWN'}`)
}

async function phaseTwo(checkpointPath) {
  const environment = requireEnvironment()
  const checkpoint = JSON.parse(await readFile(checkpointPath, 'utf8'))
  const prefix = await readFile(path.join(path.dirname(checkpointPath), 'synthetic-prefix.mp4'))
  const descriptor = buildPaddedMp4Descriptor(prefix, checkpoint.total_size_bytes, checkpoint.part_size_bytes)
  const sha256 = payloadSha256(descriptor)
  if (checkpoint.schema_version !== 'large-upload-resume-checkpoint-v1' || sha256 !== checkpoint.sha256) {
    throw new Error('Checkpoint does not match the deterministic synthetic payload')
  }
  const client = new AcceptanceApiClient(environment)
  const startedAt = Date.now()
  let cleanup
  try {
    const reconciled = await client.request(
      'reconcile interrupted parts',
      filePath(checkpoint.batch_id, checkpoint.client_file_id, 'parts/reconciliation'),
      { method: 'PUT' },
    )
    const recoveredParts = reconciled.parts
      .filter((part) => part.status === 'UPLOADED')
      .map((part) => part.part_number)
    if (reconciled.uploaded_parts !== 2 || !recoveredParts.includes(1) || !recoveredParts.includes(2)) {
      throw new Error('Reconciliation did not recover both pre-interruption parts')
    }

    for (let partNumber = 3; partNumber <= descriptor.totalParts; partNumber += 1) {
      await client.uploadPart(checkpoint.batch_id, checkpoint.client_file_id, descriptor, partNumber)
      await client.confirmPart(checkpoint.batch_id, checkpoint.client_file_id, partNumber)
      process.stdout.write(`${JSON.stringify({ phase: 'resuming', confirmed_part: partNumber, total_parts: descriptor.totalParts })}\n`)
    }

    const completion = await client.request(
      'complete 2 GiB file',
      filePath(checkpoint.batch_id, checkpoint.client_file_id, 'completion'),
      { method: 'PUT' },
    )
    const replay = await client.request(
      'replay 2 GiB file completion',
      filePath(checkpoint.batch_id, checkpoint.client_file_id, 'completion'),
      { method: 'PUT' },
    )
    if (
      completion.status !== 'UPLOADED' ||
      completion.size_bytes !== checkpoint.total_size_bytes ||
      completion.verified_sha256 !== checkpoint.sha256 ||
      JSON.stringify(completion) !== JSON.stringify(replay)
    ) {
      throw new Error('Final file integrity or idempotent Complete File replay failed')
    }

    cleanup = await abortAndAwaitCleanup(client, checkpoint.batch_id)
    process.stdout.write(`ACCEPTANCE_RESULT ${JSON.stringify({
      schema_version: 'large-upload-resume-acceptance-v1',
      total_size_bytes: checkpoint.total_size_bytes,
      part_size_bytes: checkpoint.part_size_bytes,
      total_parts: checkpoint.total_parts,
      process_boundary_resume: true,
      uploaded_unconfirmed_part_recovered: true,
      reconciled_uploaded_parts: reconciled.uploaded_parts,
      verified_sha256: completion.verified_sha256,
      complete_replay_identical: true,
      cleanup_status: cleanup.cleanup_status,
      resume_and_verify_duration_ms: Date.now() - startedAt,
    })}\n`)
  } finally {
    if (!cleanup) {
      try {
        await abortAndAwaitCleanup(client, checkpoint.batch_id)
      } catch (error) {
        process.stderr.write(`Cleanup after failed acceptance did not complete: ${error.message}\n`)
      }
    }
  }
}

function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: 'inherit', windowsHide: true })
    child.on('error', reject)
    child.on('exit', (code) => {
      if (code === 0) resolve()
      else reject(new Error(`${path.basename(command)} exited with code ${code}`))
    })
  })
}

async function generateSyntheticPrefix(outputPath) {
  await run(process.env.FFMPEG_PATH || 'ffmpeg', [
    '-hide_banner', '-loglevel', 'error', '-y',
    '-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=10',
    '-t', '1', '-an', '-c:v', 'libx264', '-preset', 'veryfast',
    '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
    '-metadata', 'title=synthetic-large-upload-acceptance', outputPath,
  ])
}

async function orchestrate() {
  requireEnvironment()
  const workDirectory = await mkdtemp(path.join(tmpdir(), 'medication-large-upload-'))
  const checkpointPath = path.join(workDirectory, 'checkpoint.json')
  try {
    await generateSyntheticPrefix(path.join(workDirectory, 'synthetic-prefix.mp4'))
    const entryPoint = fileURLToPath(import.meta.url)
    await run(process.execPath, [entryPoint, 'phase-one', checkpointPath])
    await run(process.execPath, [entryPoint, 'phase-two', checkpointPath])
  } finally {
    await rm(workDirectory, { recursive: true, force: true })
  }
}

const isEntryPoint = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (isEntryPoint) {
  const mode = process.argv[2]
  const task = mode === 'phase-one'
    ? phaseOne(process.argv[3])
    : mode === 'phase-two'
      ? phaseTwo(process.argv[3])
      : orchestrate()
  task.catch((error) => {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 1
  })
}
