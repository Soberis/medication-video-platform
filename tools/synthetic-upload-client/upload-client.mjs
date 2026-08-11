import { createHash, randomUUID } from 'node:crypto'

export const DEFAULT_PART_SIZE_BYTES = 8 * 1024 * 1024

export function sha256Hex(value) {
  return createHash('sha256').update(value).digest('hex')
}

export function buildManifest(files) {
  const lines = [...files]
    .sort((left, right) => left.clientFileId.localeCompare(right.clientFileId))
    .map(
      (file) =>
        `${file.clientFileId.toLowerCase()}\t${file.fileKind}\t${file.contentType}\t${file.bytes.length}\t${file.sha256}\t${file.partSizeBytes}\n`,
    )

  return `upload-file-manifest-v1\n${lines.join('')}`
}

export function splitParts(bytes, partSizeBytes = DEFAULT_PART_SIZE_BYTES) {
  if (!Buffer.isBuffer(bytes) || bytes.length === 0) {
    throw new TypeError('Upload input must be a non-empty Buffer')
  }
  if (!Number.isSafeInteger(partSizeBytes) || partSizeBytes < DEFAULT_PART_SIZE_BYTES) {
    throw new RangeError('Part size must be at least 8 MiB')
  }

  const parts = []
  for (let offset = 0; offset < bytes.length; offset += partSizeBytes) {
    parts.push(bytes.subarray(offset, Math.min(offset + partSizeBytes, bytes.length)))
  }
  return parts
}

export function createUploadFile(fileKind, contentType, bytes, clientFileId = randomUUID(), fileName = null) {
  if (!Buffer.isBuffer(bytes) || bytes.length === 0) {
    throw new TypeError('Synthetic upload file must be a non-empty Buffer')
  }
  return {
    clientFileId,
    fileName,
    fileKind,
    contentType,
    bytes,
    sha256: sha256Hex(bytes),
    partSizeBytes: DEFAULT_PART_SIZE_BYTES,
  }
}

export function createSyntheticMetadata({
  cameraChannelId,
  cameraSetId,
  captureSessionId,
  deviceId,
  recordedAt,
  videoClientFileId,
}) {
  return {
    schema_version: 'capture-session-metadata-v1',
    capture_session_id: captureSessionId,
    device_id: deviceId,
    camera_set_id: cameraSetId,
    expected_camera_count: 1,
    camera_layout_version: 'synthetic-layout-v1',
    sync_group_id: `synthetic-${captureSessionId}`,
    device_time_utc: recordedAt,
    device_timezone: 'Asia/Shanghai',
    streams: [
      {
        client_file_id: videoClientFileId,
        camera_channel_id: cameraChannelId,
        camera_index: 0,
        enabled: true,
        record_start_utc: recordedAt,
        time_offset_ms: 0,
        sync_status: 'UNKNOWN',
      },
    ],
  }
}

export class PlatformApiError extends Error {
  constructor(operation, status, problem) {
    const code = typeof problem?.error_code === 'string' ? problem.error_code : 'HTTP_ERROR'
    super(`${operation} failed with HTTP ${status} (${code})`)
    this.name = 'PlatformApiError'
    this.status = status
    this.errorCode = code
    this.requestId = typeof problem?.request_id === 'string' ? problem.request_id : null
  }
}

export class SyntheticUploadClient {
  constructor({ baseUrl, projectId, bearerToken, fetchImpl = globalThis.fetch }) {
    const parsedBaseUrl = new URL(baseUrl)
    if (!['http:', 'https:'].includes(parsedBaseUrl.protocol)) {
      throw new TypeError('PLATFORM_BASE_URL must use HTTP or HTTPS')
    }
    if (!bearerToken) {
      throw new TypeError('DEV_BEARER_TOKEN is required')
    }
    this.baseUrl = parsedBaseUrl.toString().replace(/\/$/, '')
    this.projectId = projectId
    this.bearerToken = bearerToken
    this.fetchImpl = fetchImpl
  }

  async provisionSyntheticCamera() {
    const channel = await this.#requestJson(
      'provision camera channel',
      `/projects/${this.projectId}/camera-channels/synthetic-upload-client.overview`,
      { method: 'PUT' },
    )
    const cameraSet = await this.#requestJson(
      'configure camera set',
      `/projects/${this.projectId}/camera-sets/synthetic-upload-client/versions/1`,
      {
        method: 'PUT',
        body: {
          layout_version: 'synthetic-layout-v1',
          members: [
            {
              camera_channel_id: channel.camera_channel_id,
              camera_index: 0,
              camera_role: 'OVERVIEW',
              mount_position: 'synthetic-front-center',
              enabled: true,
            },
          ],
        },
      },
    )
    return {
      cameraChannelId: channel.camera_channel_id,
      cameraSetId: cameraSet.camera_set_id,
    }
  }

  async uploadSyntheticSession(videoBytes, camera) {
    if (!camera?.cameraChannelId || !camera?.cameraSetId) {
      throw new TypeError('A provisioned camera channel and camera set are required')
    }
    const deviceId = 'synthetic-edge-uploader-01'
    const recordedAt = new Date().toISOString()
    const captureSessionId = randomUUID()
    const videoFile = createUploadFile('VIDEO', 'video/mp4', videoBytes, randomUUID(), 'synthetic-video.mp4')

    const metadata = createSyntheticMetadata({
      cameraChannelId: camera.cameraChannelId,
      cameraSetId: camera.cameraSetId,
      captureSessionId,
      deviceId,
      recordedAt,
      videoClientFileId: videoFile.clientFileId,
    })
    const metadataFile = createUploadFile(
      'METADATA',
      'application/json',
      Buffer.from(`${JSON.stringify(metadata)}\n`, 'utf8'),
      randomUUID(),
      'capture-session-metadata.json',
    )
    const eventFile = createUploadFile(
      'EVENT_LOG',
      'application/x-ndjson',
      Buffer.from(
        `${JSON.stringify({
          schema_version: 'synthetic-capture-event-v1',
          event_id: randomUUID(),
          event_type: 'SYNTHETIC_CAPTURE_COMPLETED',
          occurred_at: recordedAt,
          capture_session_id: captureSessionId,
        })}\n`,
        'utf8',
      ),
      randomUUID(),
      'capture-event-log.ndjson',
    )
    const files = [videoFile, metadataFile, eventFile]
    const manifestSha256 = sha256Hex(buildManifest(files))
    const totalSizeBytes = files.reduce((total, file) => total + file.bytes.length, 0)

    const batch = await this.#requestJson('initialize upload batch', '/upload/init', {
      method: 'POST',
      idempotencyKey: `synthetic-batch-${randomUUID()}`,
      body: {
        project_id: this.projectId,
        device_id: deviceId,
        file_count: files.length,
        total_size_bytes: totalSizeBytes,
        manifest_sha256: manifestSha256,
      },
    })

    for (const file of files) {
      await this.#requestJson(
        `register ${file.fileKind} file`,
        `/upload/${batch.batch_id}/files/${file.clientFileId}`,
        {
          method: 'PUT',
          idempotencyKey: `synthetic-file-${randomUUID()}`,
          body: {
            file_kind: file.fileKind,
            content_type: file.contentType,
            size_bytes: file.bytes.length,
            sha256: file.sha256,
            part_size_bytes: file.partSizeBytes,
            file_name: file.fileName,
          },
        },
      )
    }

    for (const file of files) {
      await this.#uploadFile(batch.batch_id, file)
    }

    const completion = await this.#requestJson(
      'complete upload batch',
      `/upload/${batch.batch_id}/completion`,
      { method: 'PUT' },
    )
    const session = await this.#requestJson(
      'assemble capture session',
      `/upload/${batch.batch_id}/capture-session`,
      { method: 'PUT' },
    )

    return {
      batch_id: batch.batch_id,
      capture_session_id: session.capture_session_id,
      clip_asset_ids: completion.clip_assets.map((asset) => asset.clip_asset_id),
    }
  }

  async #uploadFile(batchId, file) {
    await this.#requestJson(
      `create ${file.fileKind} multipart session`,
      `/upload/${batchId}/files/${file.clientFileId}/multipart`,
      { method: 'PUT' },
    )

    const parts = splitParts(file.bytes, file.partSizeBytes)
    for (let index = 0; index < parts.length; index += 1) {
      const partNumber = index + 1
      const part = parts[index]
      const authorization = await this.#requestJson(
        `authorize ${file.fileKind} part ${partNumber}`,
        `/upload/${batchId}/files/${file.clientFileId}/parts/${partNumber}/authorization`,
        { method: 'POST', body: { sha256: sha256Hex(part) } },
      )
      if (authorization.expected_size_bytes !== part.length) {
        throw new Error(`Server expected ${authorization.expected_size_bytes} bytes for part ${partNumber}`)
      }

      const uploadResponse = await this.fetchImpl(authorization.upload_url, {
        method: 'PUT',
        body: part,
        headers: { 'Content-Length': String(part.length) },
      })
      if (!uploadResponse.ok) {
        throw new Error(`Object-storage upload failed with HTTP ${uploadResponse.status}`)
      }

      await this.#requestJson(
        `confirm ${file.fileKind} part ${partNumber}`,
        `/upload/${batchId}/files/${file.clientFileId}/parts/${partNumber}/confirmation`,
        { method: 'PUT' },
      )
    }

    const completion = await this.#requestJson(
      `complete ${file.fileKind} file`,
      `/upload/${batchId}/files/${file.clientFileId}/completion`,
      { method: 'PUT' },
    )
    if (completion.verified_sha256 !== file.sha256) {
      throw new Error(`${file.fileKind} verified digest does not match generated bytes`)
    }
  }

  async #requestJson(operation, path, { method, body, idempotencyKey } = {}) {
    const headers = {
      Accept: 'application/json, application/problem+json',
      Authorization: `Bearer ${this.bearerToken}`,
    }
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json'
    }
    if (idempotencyKey) {
      headers['Idempotency-Key'] = idempotencyKey
    }

    const response = await this.fetchImpl(`${this.baseUrl}/api/v1${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const text = await response.text()
    let payload = null
    if (text) {
      try {
        payload = JSON.parse(text)
      } catch {
        if (!response.ok) {
          throw new PlatformApiError(operation, response.status, null)
        }
        throw new Error(`${operation} returned non-JSON content`)
      }
    }
    if (!response.ok) {
      throw new PlatformApiError(operation, response.status, payload)
    }
    return payload
  }
}
