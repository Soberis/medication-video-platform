import { csrfHeaders } from '@/api/http'

export type CaptureSessionAssemblyStatus =
  | 'OPEN'
  | 'ASSEMBLING'
  | 'COMPLETE'
  | 'INCOMPLETE'
  | 'CLOSED'

export type CaptureQualityStatus = 'PENDING' | 'PASS' | 'WARNING' | 'REJECT'
export type CameraStreamStatus = 'BOUND' | 'INGESTED' | 'INGEST_FAILED' | 'UNAVAILABLE'
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

export type DatasetQualityReviewDecision =
  | 'UNREVIEWED'
  | 'QUALIFIED'
  | 'REJECTED'
  | 'NEEDS_REVIEW'
export type DatasetAnnotationReviewDecision = 'UNREVIEWED' | 'APPROVED' | 'REJECTED'
export type DatasetPostCleaningDecision = 'MISSING' | 'PASS' | 'WARNING' | 'REJECT'
export type DatasetAssetStatus = ClipAssetStatus | 'MISSING'

export interface DatasetAdmissionPreviewStream {
  camera_stream_id: string
  clip_asset_id: string
  camera_index: number
  eligible: boolean
  stream_status: CameraStreamStatus
  stream_quality_status: CaptureQualityStatus
  asset_status: DatasetAssetStatus
  asset_deleted: boolean
  quality_review_decision: DatasetQualityReviewDecision
  annotation_revision_id?: string | null
  annotation_review_decision: DatasetAnnotationReviewDecision
  post_cleaning_decision: DatasetPostCleaningDecision
  annotation_document_sha256?: string | null
  label_schema_digest?: string | null
  blocking_reasons: string[]
}

export interface DatasetAdmissionPreview {
  capture_session_id: string
  eligible: boolean
  assembly_status: CaptureSessionAssemblyStatus
  quality_status: CaptureQualityStatus
  expected_camera_count: number
  received_camera_count: number
  blocking_reasons: string[]
  streams: DatasetAdmissionPreviewStream[]
}

export interface DatasetVersionDraft {
  dataset_version_id: string
  project_id: string
  display_name: string
  status: 'DRAFT'
  created_by: string
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

export interface ClipAssetPlaybackGrant {
  grant_id: string
  purpose: 'DATA_REVIEW'
  media_url: string
  expires_at: string
  content_type: string
}

export interface MediaProbeJob {
  media_job_id: string
  project_id: string
  clip_asset_id: string
  job_type: 'MEDIA_PROBE'
  status: 'REQUESTED' | 'QUEUED' | 'RUNNING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'FAILED'
}

export interface MediaReport {
  media_report_id: string
  media_job_id: string
  project_id: string
  clip_asset_id: string
  container_format: string
  duration_microseconds: number
  video_codec: string
  video_profile: string | null
  width: number
  height: number
  pixel_format: string | null
  primary_video_bitrate_bps: number | null
  average_frame_rate_numerator: number | null
  average_frame_rate_denominator: number | null
  frame_count: number | null
  variable_frame_rate: boolean
  audio_stream_count: number
  decode_verified: true
  input_digest_verified: true
  warning_codes: string[]
  created_at: string
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
  PLAYBACK_ACCESS_DENIED: '当前账号无权播放该项目的视频。',
  CLIP_ASSET_NOT_PROBEABLE: '当前视频不处于可检测状态，请刷新后重试。',
  PLAYBACK_UNSUPPORTED_MEDIA: '当前仅支持播放 MP4 格式的视频。',
  CLIP_ASSET_DELETED: '该视频已删除，不能再播放。',
  MEDIA_REPORT_NOT_FOUND: '媒体检测报告尚未就绪，请在检测完成后刷新状态。',
  OBJECT_STORAGE_UNAVAILABLE: '对象存储暂时无法签发播放地址，请稍后重试。',
  DATASET_VERSION_SESSION_NOT_ELIGIBLE: '当前配药操作尚未满足创建数据集草稿的条件，请刷新后重试。',
  DATASET_VERSION_CAPTURE_SESSION_DUPLICATE: '同一配药操作不能重复加入数据集草稿。',
  DATASET_VERSION_IDEMPOTENCY_KEY_REUSED: '本次创建请求与已提交请求冲突，请刷新后重试。',
  DATASET_VERSION_ACCESS_DENIED: '只有项目管理员可以创建数据集草稿。',
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

export function getCaptureSessionDatasetAdmissionPreview(
  projectId: string,
  captureSessionId: string,
  signal?: AbortSignal,
) {
  return readJson<DatasetAdmissionPreview>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/capture-sessions/${encodeURIComponent(captureSessionId)}/dataset-admission-preview`,
    signal,
  )
}

export async function createDatasetVersionDraft(
  projectId: string,
  displayName: string,
  captureSessionIds: string[],
): Promise<DatasetVersionDraft> {
  const response = await fetch(`/api/v1/projects/${encodeURIComponent(projectId)}/dataset-versions`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
      ...csrfHeaders(),
    },
    body: JSON.stringify({ display_name: displayName, capture_session_ids: captureSessionIds }),
    cache: 'no-store',
  })
  if (response.ok) return (await response.json()) as DatasetVersionDraft
  throw await toCatalogError(response)
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

export function getClipAssetMediaReport(projectId: string, clipAssetId: string, signal?: AbortSignal) {
  return readJson<MediaReport>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}/media-report`,
    signal,
  )
}

export async function deleteClipAsset(projectId: string, clipAssetId: string, reason: string) {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}`,
    {
      method: 'DELETE',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
        ...csrfHeaders(),
      },
      body: JSON.stringify({ reason }),
      cache: 'no-store',
    },
  )
  if (response.status === 204) return
  throw await toCatalogError(response)
}

export async function authorizeClipAssetPlayback(projectId: string, clipAssetId: string) {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}/playback-grants`,
    {
      method: 'POST',
      headers: { Accept: 'application/json', ...csrfHeaders() },
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as ClipAssetPlaybackGrant
  throw await toCatalogError(response)
}

export async function requestClipAssetMediaProbe(projectId: string, clipAssetId: string) {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}/media-probe-jobs`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
        ...csrfHeaders(),
      },
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as MediaProbeJob
  throw await toCatalogError(response)
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

  throw await toCatalogError(response)
}

async function toCatalogError(response: Response): Promise<CatalogApiError> {
  let body: ApiErrorBody = {}
  try {
    body = (await response.json()) as ApiErrorBody
  } catch {
    // Use the safe fallback below for proxy and non-JSON failures.
  }
  const errorCode = typeof body.error_code === 'string' ? body.error_code : 'CATALOG_REQUEST_FAILED'
  return new CatalogApiError(
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
