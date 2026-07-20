export type CaptureSessionAssemblyStatus =
  | 'OPEN'
  | 'ASSEMBLING'
  | 'COMPLETE'
  | 'INCOMPLETE'
  | 'CLOSED'

export type CaptureQualityStatus = 'PENDING' | 'PASS' | 'WARNING' | 'REJECT'
export type CameraStreamStatus = 'BOUND' | 'INGESTED' | 'INGEST_FAILED'
export type DeclaredSyncStatus = 'UNKNOWN' | 'PASS' | 'WARNING' | 'REJECT'
export type ClipAssetStatus = 'UPLOADED' | 'INGESTING' | 'INGESTED' | 'INGEST_FAILED'

export interface CaptureSessionListItem {
  capture_session_id: string
  batch_id: string
  device_id: string
  camera_set_id: string
  camera_set_key: string
  camera_set_configuration_version: number
  camera_layout_version: string
  expected_camera_count: number
  bound_stream_count: number
  received_camera_count: number
  ingest_failed_stream_count: number
  sync_group_id: string | null
  device_time_utc: string | null
  server_receive_time: string
  assembly_status: CaptureSessionAssemblyStatus
  quality_status: CaptureQualityStatus
  created_at: string
}

export interface CameraSetSnapshotMember {
  camera_channel_id: string
  camera_index: number
  camera_role: string
  mount_position: string
  enabled: boolean
}

export interface CameraSetSnapshot {
  camera_set_snapshot_id: string
  source_camera_set_id: string
  camera_set_key: string
  configuration_version: number
  layout_version: string
  expected_camera_count: number
  members: CameraSetSnapshotMember[]
}

export interface CameraStream {
  camera_stream_id: string
  clip_asset_id: string
  client_file_id: string
  camera_channel_id: string
  camera_index: number
  camera_role: string
  mount_position: string
  record_start_utc: string | null
  time_offset_ms: number
  sync_status: DeclaredSyncStatus
  stream_status: CameraStreamStatus
  quality_status: CaptureQualityStatus
}

export interface CaptureSessionDetail {
  capture_session_id: string
  project_id: string
  batch_id: string
  device_id: string
  metadata_upload_file_id: string | null
  metadata_schema_version: string
  metadata_sha256: string | null
  expected_camera_count: number
  received_camera_count: number
  sync_group_id: string | null
  device_time_utc: string | null
  device_timezone: string | null
  server_receive_time: string
  assembly_status: CaptureSessionAssemblyStatus
  quality_status: CaptureQualityStatus
  camera_set_snapshot: CameraSetSnapshot
  streams: CameraStream[]
  created_at: string
}

export interface ClipAssetSessionBinding {
  capture_session_id: string
  camera_stream_id: string
  camera_channel_id: string
  camera_index: number
  camera_role: string
  mount_position: string
  record_start_utc: string | null
  time_offset_ms: number
  declared_sync_status: DeclaredSyncStatus
  stream_status: CameraStreamStatus
  quality_status: CaptureQualityStatus
}

export interface ClipAssetRead {
  clip_asset_id: string
  batch_id: string
  source_upload_file_id: string
  client_file_id: string
  content_type: string
  size_bytes: number
  sha256: string
  status: ClipAssetStatus
  folder_id: string | null
  session_binding: ClipAssetSessionBinding | null
  created_at: string
  updated_at: string
}

export interface CatalogPage<T> {
  items: T[]
  next_cursor: string | null
}

export interface CaptureSessionFilters {
  deviceId?: string
  assemblyStatus?: CaptureSessionAssemblyStatus
  qualityStatus?: CaptureQualityStatus
  recordedFrom?: string
  recordedBefore?: string
}

export interface ClipAssetFilters {
  captureSessionId?: string
  batchId?: string
  deviceId?: string
  cameraRole?: string
  status?: ClipAssetStatus
  folderId?: string
  unclassified?: boolean
  recordedFrom?: string
  recordedBefore?: string
}

interface ApiErrorBody {
  error_code?: unknown
  error_message?: unknown
  retryable?: unknown
  request_id?: unknown
}

const errorMessages: Record<string, string> = {
  INVALID_CURSOR: '分页位置已经失效，请重新加载第一页。',
  INVALID_TIME_RANGE: '拍摄时间范围无效，开始时间必须早于结束时间。',
  INVALID_FOLDER_FILTER: '文件夹和未分类筛选不能同时使用。',
  CAPTURE_SESSION_NOT_FOUND: '未找到该配药操作记录，或它不属于当前项目。',
  CLIP_ASSET_NOT_FOUND: '未找到该视频资产，或它不属于当前项目。',
  PROJECT_NOT_FOUND: '未找到该项目，或当前用户尚未加入该项目。',
  PROJECT_ACCESS_DENIED: '当前用户无权访问该项目中的数据。',
  INVALID_REQUEST: '筛选条件格式不正确，请检查后重试。',
}

export class CatalogApiError extends Error {
  readonly errorCode: string
  readonly retryable: boolean
  readonly requestId: string | null

  constructor(
    message: string,
    errorCode = 'CATALOG_REQUEST_FAILED',
    retryable = false,
    requestId: string | null = null,
  ) {
    super(message)
    this.name = 'CatalogApiError'
    this.errorCode = errorCode
    this.retryable = retryable
    this.requestId = requestId
  }
}

export function listCaptureSessions(
  projectId: string,
  filters: CaptureSessionFilters,
  cursor: string | null,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams({ limit: '20' })
  add(query, 'device_id', filters.deviceId)
  add(query, 'assembly_status', filters.assemblyStatus)
  add(query, 'quality_status', filters.qualityStatus)
  add(query, 'recorded_from', filters.recordedFrom)
  add(query, 'recorded_before', filters.recordedBefore)
  add(query, 'cursor', cursor ?? undefined)
  return readJson<CatalogPage<CaptureSessionListItem>>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/capture-sessions?${query.toString()}`,
    signal,
  )
}

export function getCaptureSession(
  projectId: string,
  captureSessionId: string,
  signal?: AbortSignal,
) {
  return readJson<CaptureSessionDetail>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/capture-sessions/${encodeURIComponent(captureSessionId)}`,
    signal,
  )
}

export function listClipAssets(
  projectId: string,
  filters: ClipAssetFilters,
  cursor: string | null,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams({ limit: '20' })
  add(query, 'capture_session_id', filters.captureSessionId)
  add(query, 'batch_id', filters.batchId)
  add(query, 'device_id', filters.deviceId)
  add(query, 'camera_role', filters.cameraRole)
  add(query, 'status', filters.status)
  add(query, 'folder_id', filters.folderId)
  if (filters.unclassified) query.set('unclassified', 'true')
  add(query, 'recorded_from', filters.recordedFrom)
  add(query, 'recorded_before', filters.recordedBefore)
  add(query, 'cursor', cursor ?? undefined)
  return readJson<CatalogPage<ClipAssetRead>>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets?${query.toString()}`,
    signal,
  )
}

export function getClipAsset(projectId: string, clipAssetId: string, signal?: AbortSignal) {
  return readJson<ClipAssetRead>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}`,
    signal,
  )
}

async function readJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(path, {
    method: 'GET',
    headers: { Accept: 'application/json' },
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
    // Use the safe fallback below for proxy and non-JSON failures.
  }
  const errorCode = typeof body.error_code === 'string' ? body.error_code : 'CATALOG_REQUEST_FAILED'
  throw new CatalogApiError(
    errorMessages[errorCode] ?? '暂时无法读取视频数据，请稍后重试。',
    errorCode,
    body.retryable === true,
    typeof body.request_id === 'string' ? body.request_id : null,
  )
}

function add(query: URLSearchParams, name: string, value: string | undefined) {
  const normalized = value?.trim()
  if (normalized) {
    query.set(name, normalized)
  }
}
