import { sha256 } from '@noble/hashes/sha2.js'

import { csrfHeaders } from '@/api/http'

import { hashFileInWorker } from '@/upload/hash-file'

export const LOCAL_UPLOAD_PART_SIZE_BYTES = 8 * 1024 * 1024

export type LocalUploadPhase =
  | 'HASHING'
  | 'INITIALIZING'
  | 'REGISTERING'
  | 'UPLOADING'
  | 'VERIFYING'
  | 'ASSEMBLING'
  | 'COMPLETED'

export interface LocalUploadProgress {
  readonly phase: LocalUploadPhase
  readonly totalBytes: number
  readonly processedBytes: number
  readonly uploadedBytes: number
  readonly totalParts: number
  readonly completedParts: number
  readonly batchId?: string
}

export interface PlatformLocalUploadRequest {
  readonly projectId: string
  readonly assetFolderId: string | null
  readonly file: File
}

export interface PlatformLocalUploadResult {
  readonly batchId: string
  readonly captureSessionId: string
  readonly clipAssetId: string | null
}

interface UploadBatchResponse {
  readonly batch_id: string
}

interface UploadMultipartSessionResponse {
  readonly total_parts: number
}

interface UploadPartAuthorizationResponse {
  readonly upload_url: string
}

interface UploadBatchCompletionResponse {
  readonly clip_assets: Array<{ readonly clip_asset_id: string }>
}

interface CaptureSessionResponse {
  readonly capture_session_id: string
}

interface ApiErrorBody {
  readonly error_code?: unknown
  readonly error_message?: unknown
  readonly retryable?: unknown
}

const errorMessages: Record<string, string> = {
  INVALID_REQUEST: '上传信息不符合要求，请检查项目、文件夹和视频后重试。',
  PROJECT_NOT_FOUND: '未找到该项目，或当前用户尚未加入该项目。',
  PROJECT_ACCESS_DENIED: '当前用户无权向该项目上传视频。',
  PLATFORM_LOCAL_SINGLE_VIDEO_REQUIRED: '每个本地上传批次只能包含一个视频。',
  PLATFORM_LOCAL_VIDEO_REQUIRED: '本地上传只能登记一个视频文件。',
  PLATFORM_LOCAL_DEVICE_INVALID: '本地上传来源标识无效。',
  UPLOAD_SOURCE_FOLDER_UNSUPPORTED: '当前上传来源不支持选择项目文件夹。',
  UPLOAD_BATCH_EXPIRED: '上传批次已过期，请重新开始。',
  UPLOAD_PART_AUTHORIZATION_FAILED: '无法取得视频分片上传授权，请稍后重试。',
  UPLOAD_FILE_SHA256_MISMATCH: '服务器校验到视频内容不完整或已变化，请重新选择文件后上传。',
}

export class LocalUploadApiError extends Error {
  readonly errorCode: string
  readonly retryable: boolean

  constructor(message: string, errorCode: string, retryable = false) {
    super(message)
    this.name = 'LocalUploadApiError'
    this.errorCode = errorCode
    this.retryable = retryable
  }
}

export async function uploadPlatformLocalVideo(
  request: PlatformLocalUploadRequest,
  onProgress: (progress: LocalUploadProgress) => void,
  signal?: AbortSignal,
): Promise<PlatformLocalUploadResult> {
  const contentType = requireVideoContentType(request.file)
  const totalParts = Math.ceil(request.file.size / LOCAL_UPLOAD_PART_SIZE_BYTES)
  if (totalParts > 10_000) {
    throw new LocalUploadApiError(
      '文件超过当前分片上传的单文件上限。',
      'LOCAL_UPLOAD_TOO_MANY_PARTS',
    )
  }

  onProgress(progress('HASHING', request.file.size, 0, 0, totalParts, 0))
  const hashes = await hashFileInWorker(
    request.file,
    LOCAL_UPLOAD_PART_SIZE_BYTES,
    (processedBytes, totalBytes) =>
      onProgress(progress('HASHING', totalBytes, processedBytes, 0, totalParts, 0)),
    signal,
  )
  const clientFileId = crypto.randomUUID()
  const manifestSha256 = calculateManifestSha256({
    clientFileId,
    contentType,
    sizeBytes: request.file.size,
    sha256: hashes.sha256,
    partSizeBytes: LOCAL_UPLOAD_PART_SIZE_BYTES,
  })

  onProgress(progress('INITIALIZING', request.file.size, request.file.size, 0, totalParts, 0))
  const batch = await requestJson<UploadBatchResponse>(
    '/api/v1/upload/init',
    'POST',
    {
      project_id: request.projectId,
      device_id: 'PLATFORM_LOCAL',
      upload_source_type: 'PLATFORM_LOCAL',
      asset_folder_id: request.assetFolderId,
      file_count: 1,
      total_size_bytes: request.file.size,
      manifest_sha256: manifestSha256,
    },
    crypto.randomUUID(),
    signal,
  )

  onProgress(
    progress('REGISTERING', request.file.size, request.file.size, 0, totalParts, 0, batch.batch_id),
  )
  await requestJson(
    `/api/v1/upload/${encodeURIComponent(batch.batch_id)}/files/${encodeURIComponent(clientFileId)}`,
    'PUT',
    {
      file_kind: 'VIDEO',
      content_type: contentType,
      size_bytes: request.file.size,
      sha256: hashes.sha256,
      part_size_bytes: LOCAL_UPLOAD_PART_SIZE_BYTES,
    },
    crypto.randomUUID(),
    signal,
  )

  const multipart = await requestJson<UploadMultipartSessionResponse>(
    `/api/v1/upload/${encodeURIComponent(batch.batch_id)}/files/${encodeURIComponent(clientFileId)}/multipart`,
    'PUT',
    undefined,
    undefined,
    signal,
  )
  if (multipart.total_parts !== totalParts || hashes.partSha256.length !== totalParts) {
    throw new LocalUploadApiError(
      '本地上传清单与服务器分片计划不一致。',
      'LOCAL_UPLOAD_PLAN_MISMATCH',
    )
  }

  let uploadedBytes = 0
  for (let index = 0; index < totalParts; index += 1) {
    const partNumber = index + 1
    const part = request.file.slice(
      index * LOCAL_UPLOAD_PART_SIZE_BYTES,
      Math.min((index + 1) * LOCAL_UPLOAD_PART_SIZE_BYTES, request.file.size),
    )
    const partSha256 = hashes.partSha256[index]
    if (!partSha256) throw new LocalUploadApiError('本地分片摘要缺失。', 'LOCAL_UPLOAD_HASH_FAILED')

    const authorization = await requestJson<UploadPartAuthorizationResponse>(
      `/api/v1/upload/${encodeURIComponent(batch.batch_id)}/files/${encodeURIComponent(clientFileId)}/parts/${partNumber}/authorization`,
      'POST',
      { sha256: partSha256 },
      undefined,
      signal,
    )
    onProgress(
      progress(
        'UPLOADING',
        request.file.size,
        request.file.size,
        uploadedBytes,
        totalParts,
        index,
        batch.batch_id,
      ),
    )
    await uploadPart(
      authorization.upload_url,
      part,
      (partBytes) => {
        onProgress(
          progress(
            'UPLOADING',
            request.file.size,
            request.file.size,
            uploadedBytes + partBytes,
            totalParts,
            index,
            batch.batch_id,
          ),
        )
      },
      signal,
    )
    await requestJson(
      `/api/v1/upload/${encodeURIComponent(batch.batch_id)}/files/${encodeURIComponent(clientFileId)}/parts/${partNumber}/confirmation`,
      'PUT',
      undefined,
      undefined,
      signal,
    )
    uploadedBytes += part.size
    onProgress(
      progress(
        'UPLOADING',
        request.file.size,
        request.file.size,
        uploadedBytes,
        totalParts,
        partNumber,
        batch.batch_id,
      ),
    )
  }

  onProgress(
    progress(
      'VERIFYING',
      request.file.size,
      request.file.size,
      uploadedBytes,
      totalParts,
      totalParts,
      batch.batch_id,
    ),
  )
  await requestJson(
    `/api/v1/upload/${encodeURIComponent(batch.batch_id)}/files/${encodeURIComponent(clientFileId)}/completion`,
    'PUT',
    undefined,
    undefined,
    signal,
  )
  const completion = await requestJson<UploadBatchCompletionResponse>(
    `/api/v1/upload/${encodeURIComponent(batch.batch_id)}/completion`,
    'PUT',
    undefined,
    undefined,
    signal,
  )
  onProgress(
    progress(
      'ASSEMBLING',
      request.file.size,
      request.file.size,
      uploadedBytes,
      totalParts,
      totalParts,
      batch.batch_id,
    ),
  )
  const session = await requestJson<CaptureSessionResponse>(
    `/api/v1/upload/${encodeURIComponent(batch.batch_id)}/capture-session`,
    'PUT',
    undefined,
    undefined,
    signal,
  )
  onProgress(
    progress(
      'COMPLETED',
      request.file.size,
      request.file.size,
      uploadedBytes,
      totalParts,
      totalParts,
      batch.batch_id,
    ),
  )

  return {
    batchId: batch.batch_id,
    captureSessionId: session.capture_session_id,
    clipAssetId: completion.clip_assets[0]?.clip_asset_id ?? null,
  }
}

export function calculateManifestSha256(entry: {
  readonly clientFileId: string
  readonly contentType: string
  readonly sizeBytes: number
  readonly sha256: string
  readonly partSizeBytes: number
}) {
  const canonical = [
    'upload-file-manifest-v1',
    [
      entry.clientFileId.toLowerCase(),
      'VIDEO',
      entry.contentType,
      entry.sizeBytes,
      entry.sha256,
      entry.partSizeBytes,
    ].join('\t'),
    '',
  ].join('\n')
  return toHex(sha256(new TextEncoder().encode(canonical)))
}

function requireVideoContentType(file: File) {
  if (file.size < 1) {
    throw new LocalUploadApiError('不能上传空文件。', 'LOCAL_UPLOAD_FILE_EMPTY')
  }
  const contentType = file.type.trim().toLowerCase()
  if (!contentType.startsWith('video/')) {
    throw new LocalUploadApiError(
      '浏览器未识别该文件为视频，请选择具有明确视频类型的文件。',
      'LOCAL_UPLOAD_VIDEO_TYPE_REQUIRED',
    )
  }
  return contentType
}

function progress(
  phase: LocalUploadPhase,
  totalBytes: number,
  processedBytes: number,
  uploadedBytes: number,
  totalParts: number,
  completedParts: number,
  batchId?: string,
): LocalUploadProgress {
  return { phase, totalBytes, processedBytes, uploadedBytes, totalParts, completedParts, batchId }
}

async function requestJson<T>(
  path: string,
  method: 'POST' | 'PUT',
  body: unknown,
  idempotencyKey: string | undefined,
  signal?: AbortSignal,
): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey
  Object.assign(headers, csrfHeaders())
  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
    signal,
  })
  if (response.ok) return (await response.json()) as T

  let errorBody: ApiErrorBody = {}
  try {
    errorBody = (await response.json()) as ApiErrorBody
  } catch {
    // The fallback below does not expose proxy, object-storage, or server implementation details.
  }
  const errorCode =
    typeof errorBody.error_code === 'string' ? errorBody.error_code : 'LOCAL_UPLOAD_REQUEST_FAILED'
  throw new LocalUploadApiError(
    errorMessages[errorCode] ?? '本地上传请求未完成，请稍后重试。',
    errorCode,
    errorBody.retryable === true,
  )
}

function uploadPart(
  uploadUrl: string,
  part: Blob,
  onProgress: (loadedBytes: number) => void,
  signal?: AbortSignal,
) {
  return new Promise<void>((resolve, reject) => {
    const request = new XMLHttpRequest()
    let finished = false
    function finish(callback: () => void) {
      if (finished) return
      finished = true
      signal?.removeEventListener('abort', abort)
      callback()
    }
    function abort() {
      request.abort()
      finish(() => reject(new DOMException('The upload was cancelled.', 'AbortError')))
    }
    request.open('PUT', uploadUrl)
    request.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress(event.loaded)
    }
    request.onload = () => {
      if (request.status >= 200 && request.status < 300) {
        finish(resolve)
      } else {
        finish(() =>
          reject(
            new LocalUploadApiError(
              '视频分片未能上传，请稍后重试。',
              'LOCAL_UPLOAD_PART_TRANSFER_FAILED',
              true,
            ),
          ),
        )
      }
    }
    request.onerror = () =>
      finish(() =>
        reject(
          new LocalUploadApiError(
            '视频分片网络连接失败，请稍后重试。',
            'LOCAL_UPLOAD_PART_TRANSFER_FAILED',
            true,
          ),
        ),
      )
    request.onabort = () =>
      finish(() => reject(new DOMException('The upload was cancelled.', 'AbortError')))
    if (signal?.aborted) {
      abort()
      return
    }
    signal?.addEventListener('abort', abort, { once: true })
    request.send(part)
  })
}

function toHex(bytes: Uint8Array) {
  return Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')
}
