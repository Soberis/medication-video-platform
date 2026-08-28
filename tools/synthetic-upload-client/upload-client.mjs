import { createHash, randomUUID } from 'node:crypto'

export const DEFAULT_PART_SIZE_BYTES = 8 * 1024 * 1024

const MAX_CAMERA_NUMBER = 64

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

export function normalizeCameraNumbers(cameraNumbers) {
  if (!Array.isArray(cameraNumbers) || cameraNumbers.length === 0) {
    throw new TypeError('Synthetic camera numbers must be a non-empty array')
  }
  const normalized = cameraNumbers.map((cameraNumber) => {
    if (!Number.isInteger(cameraNumber) || cameraNumber < 1 || cameraNumber > MAX_CAMERA_NUMBER) {
      throw new RangeError(`Synthetic camera number must be an integer from 1 to ${MAX_CAMERA_NUMBER}`)
    }
    return cameraNumber
  })
  if (new Set(normalized).size !== normalized.length) {
    throw new RangeError('Synthetic camera numbers must be unique')
  }
  return [...normalized].sort((left, right) => left - right)
}

export function createSyntheticGroupMetadata({
  cameraSetId,
  captureSessionId,
  deviceId,
  recordedAt,
  layoutVersion,
  syncGroupId,
  expectedCameraNumbers,
  streams,
}) {
  const expected = normalizeCameraNumbers(expectedCameraNumbers)
  if (!Array.isArray(streams) || streams.length === 0) {
    throw new TypeError('A synthetic CaptureGroup must contain at least one generated stream')
  }
  return {
    schema_version: 'capture-session-metadata-v1',
    capture_session_id: captureSessionId,
    device_id: deviceId,
    camera_set_id: cameraSetId,
    expected_camera_count: expected.length,
    camera_layout_version: layoutVersion,
    sync_group_id: syncGroupId,
    device_time_utc: recordedAt,
    device_timezone: 'Asia/Shanghai',
    streams: [...streams]
      .sort((left, right) => left.cameraNumber - right.cameraNumber)
      .map((stream) => ({
        client_file_id: stream.videoClientFileId,
        camera_channel_id: stream.cameraChannelId,
        camera_index: stream.cameraNumber - 1,
        enabled: true,
        record_start_utc: stream.recordStartAt,
        time_offset_ms: stream.timeOffsetMs ?? 0,
        sync_status: stream.syncStatus ?? 'UNKNOWN',
      })),
  }
}

export function createSyntheticEventLog({ captureGroupKey, captureSessionId, cameraNumbers, occurredAt }) {
  return Buffer.from(
    `${JSON.stringify({
      schema_version: 'synthetic-capture-event-v1',
      event_id: randomUUID(),
      event_type: 'SYNTHETIC_MEDICATION_EVENT_COMPLETED',
      occurred_at: occurredAt,
      capture_session_id: captureSessionId,
      capture_group_key: captureGroupKey,
      camera_numbers: normalizeCameraNumbers(cameraNumbers),
    })}\n`,
    'utf8',
  )
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

  async provisionSyntheticCameraSet({
    cameraNumbers,
    cameraSetKey = 'synthetic-capture-group-client',
    configurationVersion = 1,
    layoutVersion = 'synthetic-capture-group-v1',
  } = {}) {
    const expected = normalizeCameraNumbers(cameraNumbers)
    if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(cameraSetKey)) {
      throw new TypeError('Synthetic camera-set key is invalid')
    }
    if (!Number.isInteger(configurationVersion) || configurationVersion < 1) {
      throw new RangeError('Synthetic camera-set configuration version must be positive')
    }
    const channels = []
    for (const cameraNumber of expected) {
      const channel = await this.#requestJson(
        `provision synthetic camera ${cameraNumber}`,
        `/projects/${this.projectId}/camera-channels/synthetic-capture-group-client.camera-${cameraNumber}`,
        { method: 'PUT' },
      )
      channels.push({ cameraNumber, cameraChannelId: channel.camera_channel_id })
    }
    const cameraSet = await this.#requestJson(
      'configure synthetic camera set',
      `/projects/${this.projectId}/camera-sets/${cameraSetKey}/versions/${configurationVersion}`,
      {
        method: 'PUT',
        body: {
          layout_version: layoutVersion,
          members: channels.map(({ cameraNumber, cameraChannelId }) => ({
            camera_channel_id: cameraChannelId,
            camera_index: cameraNumber - 1,
            camera_role: cameraNumber === 1 ? 'OVERVIEW' : 'HANDS',
            mount_position: `synthetic-camera-${cameraNumber}`,
            enabled: true,
            enabled_for_hand_detection: true,
          })),
        },
      },
    )
    return {
      cameraSetId: cameraSet.camera_set_id,
      cameraSetKey,
      configurationVersion,
      layoutVersion,
      channels,
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

  async uploadSyntheticCaptureGroup({
    cameraSet,
    captureGroupKey,
    expectedCameraNumbers,
    presentCameraNumbers,
    videoBytesByCamera,
    capturedAt = new Date().toISOString(),
    deviceId = 'synthetic-capture-group-client.device-01',
    sourceBatchId = `synthetic-${randomUUID()}`,
  }) {
    const expected = normalizeCameraNumbers(expectedCameraNumbers)
    const present = normalizeCameraNumbers(presentCameraNumbers ?? expected)
    if (!present.every((cameraNumber) => expected.includes(cameraNumber))) {
      throw new RangeError('Every uploaded synthetic camera must belong to the explicit expected camera set')
    }
    if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(captureGroupKey)) {
      throw new TypeError('Synthetic capture group key is invalid')
    }
    if (!cameraSet?.cameraSetId || !cameraSet.layoutVersion || !Array.isArray(cameraSet.channels)) {
      throw new TypeError('A provisioned synthetic camera set is required')
    }
    const channelsByCamera = new Map(cameraSet.channels.map((channel) => [channel.cameraNumber, channel]))
    if (expected.some((cameraNumber) => !channelsByCamera.has(cameraNumber))) {
      throw new Error('The provisioned synthetic camera set does not match the expected camera numbers')
    }
    const capturedAtMs = Date.parse(capturedAt)
    if (!Number.isFinite(capturedAtMs)) {
      throw new TypeError('Synthetic capture time must be an ISO date-time')
    }

    const captureSessionId = randomUUID()
    const videoFiles = present.map((cameraNumber) => {
      const bytes = videoBytesByCamera instanceof Map
        ? videoBytesByCamera.get(cameraNumber)
        : videoBytesByCamera?.[cameraNumber]
      if (!Buffer.isBuffer(bytes) || bytes.length === 0) {
        throw new TypeError(`Synthetic camera ${cameraNumber} must have generated non-empty video bytes`)
      }
      const recordStartAt = new Date(capturedAtMs + cameraNumber * 37).toISOString()
      const videoFile = createUploadFile(
        'VIDEO',
        'video/mp4',
        bytes,
        randomUUID(),
        `synthetic-${captureGroupKey}-camera-${cameraNumber}.mp4`,
      )
      return { cameraNumber, recordStartAt, videoFile }
    })
    const metadata = createSyntheticGroupMetadata({
      cameraSetId: cameraSet.cameraSetId,
      captureSessionId,
      deviceId,
      recordedAt: capturedAt,
      layoutVersion: cameraSet.layoutVersion,
      syncGroupId: `synthetic-${captureGroupKey}`,
      expectedCameraNumbers: expected,
      streams: videoFiles.map(({ cameraNumber, recordStartAt, videoFile }) => ({
        cameraNumber,
        cameraChannelId: channelsByCamera.get(cameraNumber).cameraChannelId,
        videoClientFileId: videoFile.clientFileId,
        recordStartAt,
        timeOffsetMs: 0,
        syncStatus: 'UNKNOWN',
      })),
    })
    const metadataFile = createUploadFile(
      'METADATA',
      'application/json',
      Buffer.from(`${JSON.stringify(metadata)}\n`, 'utf8'),
      randomUUID(),
      `synthetic-${captureGroupKey}-metadata.json`,
    )
    const eventFile = createUploadFile(
      'EVENT_LOG',
      'application/x-ndjson',
      createSyntheticEventLog({
        captureGroupKey,
        captureSessionId,
        cameraNumbers: present,
        occurredAt: capturedAt,
      }),
      randomUUID(),
      `synthetic-${captureGroupKey}-event-log.ndjson`,
    )
    const files = [...videoFiles.map(({ videoFile }) => videoFile), metadataFile, eventFile]
    const manifestSha256 = sha256Hex(buildManifest(files))
    const totalSizeBytes = files.reduce((total, file) => total + file.bytes.length, 0)
    const externalManifest = await this.#requestJson(
      'accept synthetic capture group manifest',
      `/projects/${this.projectId}/external-video-ingest-manifests/v2`,
      {
        method: 'POST',
        body: {
          schema_version: 'external-video-ingest-v2',
          source_system: 'synthetic-capture-group-client',
          source_device_id: deviceId,
          source_batch_id: sourceBatchId,
          capture_group: {
            capture_group_key: captureGroupKey,
            key_source: 'UPSTREAM_MANIFEST_KEY',
            captured_at: capturedAt,
            timezone: 'Asia/Shanghai',
            expected_camera_numbers: expected,
          },
          videos: videoFiles.map(({ cameraNumber, recordStartAt, videoFile }) => ({
            client_file_id: videoFile.clientFileId,
            camera_number: cameraNumber,
            file_name: videoFile.fileName,
            declared_size_bytes: videoFile.bytes.length,
            sha256: videoFile.sha256,
            record_start_at: recordStartAt,
          })),
          upload_set_finalized: false,
        },
      },
    )
    const batch = await this.#requestJson('initialize synthetic group upload batch', '/upload/init', {
      method: 'POST',
      idempotencyKey: `synthetic-group-batch-${randomUUID()}`,
      body: {
        project_id: this.projectId,
        device_id: deviceId,
        file_count: files.length,
        total_size_bytes: totalSizeBytes,
        manifest_sha256: manifestSha256,
        external_manifest_id: externalManifest.manifest_id,
      },
    })

    for (const file of files) {
      await this.#requestJson(
        `register synthetic ${file.fileKind} file`,
        `/upload/${batch.batch_id}/files/${file.clientFileId}`,
        {
          method: 'PUT',
          idempotencyKey: `synthetic-group-file-${randomUUID()}`,
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
      'complete synthetic group upload batch',
      `/upload/${batch.batch_id}/completion`,
      { method: 'PUT' },
    )
    const session = await this.#requestJson(
      'assemble synthetic capture group session',
      `/upload/${batch.batch_id}/capture-session`,
      { method: 'PUT' },
    )
    const assetsByClientId = new Map(
      completion.clip_assets.map((asset) => [asset.client_file_id, asset]),
    )
    const views = videoFiles.map(({ cameraNumber, recordStartAt, videoFile }) => {
      const asset = assetsByClientId.get(videoFile.clientFileId)
      if (!asset?.clip_asset_id) {
        throw new Error(`The upload response did not return a ClipAsset for synthetic camera ${cameraNumber}`)
      }
      return {
        cameraNumber,
        clientFileId: videoFile.clientFileId,
        clipAssetId: asset.clip_asset_id,
        fileName: videoFile.fileName,
        recordStartAt,
      }
    })
    return {
      batchId: batch.batch_id,
      manifestId: externalManifest.manifest_id,
      captureSessionId: session.capture_session_id,
      captureGroupId: session.capture_group_id ?? session.capture_session_id,
      captureGroupKey,
      expectedCameraNumbers: expected,
      presentCameraNumbers: present,
      views,
      session,
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
