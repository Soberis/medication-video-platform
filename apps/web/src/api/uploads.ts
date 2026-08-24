export type UploadBatchStatus =
  | 'INITIATED'
  | 'UPLOADING'
  | 'COMPLETING'
  | 'COMPLETED'
  | 'FAILED'
  | 'ABORTED'
  | 'EXPIRED'

export type UploadFileStatus =
  | 'REGISTERED'
  | 'SESSION_CREATED'
  | 'UPLOADING'
  | 'COMPLETING'
  | 'UPLOADED'
  | 'FAILED'

export type UploadFileKind = 'VIDEO' | 'METADATA' | 'EVENT_LOG'

export interface UploadBatchProgress {
  batch_id: string
  project_id: string
  device_id: string
  file_count: number
  registered_file_count: number
  uploaded_file_count: number
  failed_file_count: number
  total_size_bytes: number
  uploaded_size_bytes: number
  tracked_part_count: number
  uploaded_part_count: number
  status: UploadBatchStatus
  expires_at: string
  created_at: string
}

export interface UploadFileProgress {
  upload_file_id: string
  batch_id: string
  client_file_id: string
  file_kind: UploadFileKind
  content_type: string
  size_bytes: number
  declared_sha256: string
  verified_sha256: string | null
  total_parts: number
  tracked_parts: number
  uploaded_parts: number
  uploaded_size_bytes: number
  status: UploadFileStatus
  completion_error_code: string | null
  multipart_created_at: string | null
  completion_started_at: string | null
  completed_at: string | null
  created_at: string
  updated_at: string
}

export interface UploadFileProgressList {
  batch_id: string
  registered_file_count: number
  files: UploadFileProgress[]
}

interface ApiErrorBody {
  error_code?: unknown
  error_message?: unknown
  retryable?: unknown
  request_id?: unknown
}

const uploadErrorMessages: Record<string, string> = {
  UPLOAD_BATCH_NOT_FOUND: '未找到该上传批次，请检查批次 ID 后重试。',
  UPLOAD_BATCH_FILES_NOT_FOUND: '未找到该批次的文件进度，请检查批次 ID 后重试。',
  UPLOAD_BATCH_EXPIRED: '该上传批次已过期，请重新开始上传。',
}

export class UploadApiError extends Error {
  readonly errorCode: string
  readonly retryable: boolean
  readonly requestId: string | null

  constructor(
    message: string,
    errorCode = 'UPLOAD_PROGRESS_REQUEST_FAILED',
    retryable = false,
    requestId: string | null = null,
  ) {
    super(message)
    this.name = 'UploadApiError'
    this.errorCode = errorCode
    this.retryable = retryable
    this.requestId = requestId
  }
}

async function readJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(path, {
    method: 'GET',
    headers: {
      Accept: 'application/json',
    },
    cache: 'no-store',
    signal,
  })

  if (response.ok) {
    return (await response.json()) as T
  }

  let body: ApiErrorBody = {}
  try {
    body = (await response.json()) as ApiErrorBody
  } catch {
    // The fallback below deliberately avoids exposing proxy or server internals.
  }

  const errorCode =
    typeof body.error_code === 'string' ? body.error_code : 'UPLOAD_PROGRESS_REQUEST_FAILED'
  throw new UploadApiError(
    uploadErrorMessages[errorCode] ?? '无法读取上传进度，请稍后重试。',
    errorCode,
    body.retryable === true,
    typeof body.request_id === 'string' ? body.request_id : null,
  )
}

export function getUploadBatchProgress(batchId: string, signal?: AbortSignal) {
  return readJson<UploadBatchProgress>(`/api/v1/upload/${encodeURIComponent(batchId)}`, signal)
}

export function listUploadFileProgress(batchId: string, signal?: AbortSignal) {
  return readJson<UploadFileProgressList>(
    `/api/v1/upload/${encodeURIComponent(batchId)}/files`,
    signal,
  )
}
