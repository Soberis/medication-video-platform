import { csrfHeaders } from '@/api/http'

export type CaptureQualityStatus = 'PENDING' | 'PASS' | 'WARNING' | 'REJECT'
export type CameraStreamStatus = 'BOUND' | 'INGESTED' | 'INGEST_FAILED' | 'UNAVAILABLE'
export type DeclaredSyncStatus = 'UNKNOWN' | 'PASS' | 'WARNING' | 'REJECT'
export type ClipAssetStatus = 'UPLOADED' | 'INGESTING' | 'INGESTED' | 'INGEST_FAILED'

export interface CameraSetSnapshotMember {
  camera_channel_id: string
  camera_index: number
  camera_role: string
  mount_position: string
  enabled: boolean
  enabled_for_hand_detection?: boolean
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
  enabled_for_hand_detection?: boolean
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
  enabled_for_hand_detection?: boolean
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
  session_binding: ClipAssetSessionBinding | null
  created_at: string
  updated_at: string
}

export interface ClipAssetActivityItem {
  event_id: string
  occurred_at_utc: string
  actor_type: 'USER' | 'SERVICE' | 'SYSTEM'
  actor_id: string
  action: string
  object_type: string
  object_id: string
  object_version: string | null
  outcome: 'SUCCEEDED' | 'DENIED' | 'FAILED'
  reason_code: string
  request_id: string
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

export interface HandPoseJob {
  ai_job_id: string
  project_id: string
  clip_asset_id: string
  algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER'
  algorithm_version: 'v1'
  model_sha256: string
  input_sha256: string
  input_size_bytes: number
  sampling_fps: number
  profile_sha256: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  hand_result: 'UNKNOWN' | 'HAND_DETECTED' | 'NO_HAND_DETECTED'
  run_summary?: HandPoseRunSummary | null
  requested_by: string
  created_at: string
  updated_at: string
}

export interface HandPoseRunSummary {
  analysis_complete: boolean
  hand_result: 'UNKNOWN' | 'HAND_DETECTED' | 'NO_HAND_DETECTED'
  total_video_frames: number
  inference_frame_count: number
  frames_with_hand: number
  frames_without_hand: number
  left_hand_detection_count: number
  right_hand_detection_count: number
  invalid_landmark_count: number
  processing_duration_ms: number
  started_at: string
  completed_at: string
  failure_reason?: string | null
  retryable: boolean
}

export interface HandPoseCandidate {
  frame_timestamp_ms: number
  image_width: number
  image_height: number
  hand_index: number
  handedness: 'LEFT' | 'RIGHT' | 'UNKNOWN'
  handedness_score: number
  bounding_box: { x: number; y: number; width: number; height: number }
  landmarks: Array<{ x: number; y: number; z: number }>
  valid_landmark_count: number
}

export interface HandPoseCandidateResponse {
  job: HandPoseJob
  candidates: HandPoseCandidate[]
}

export type VideoSummaryMediaStatus = ClipAssetStatus
export type VideoSummaryHandStatus = 'NOT_REQUESTED' | 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
export type VideoSummaryHandResult = 'UNKNOWN' | 'HAND_DETECTED' | 'NO_HAND_DETECTED'
export type VideoSummarySource = 'HOSPITAL_REMOTE' | 'PLATFORM_LOCAL'
export type VideoSummaryQualityDecision = 'UNREVIEWED' | 'QUALIFIED' | 'REJECTED' | 'NEEDS_REVIEW'
export type VideoSummaryAnnotationStatus = 'NOT_STARTED' | 'ASSIGNED' | 'SUBMITTED' | 'COMPLETED'
export type VideoSummaryCleaningStatus = 'NOT_STARTED' | 'PASS' | 'WARNING' | 'REJECT'

export interface VideoSummary {
  video_id: string
  display_name: string
  source_name: string
  camera_number: number | null
  captured_at: string | null
  received_at: string
  media_status: VideoSummaryMediaStatus
  quality_decision: VideoSummaryQualityDecision
  hand_status: VideoSummaryHandStatus
  hand_result: VideoSummaryHandResult
  hand_retryable: boolean
  annotation_status: VideoSummaryAnnotationStatus
  cleaning_status: VideoSummaryCleaningStatus
  retention_status: string
}

export interface DataManagementSummary {
  total_videos: number
  total_duration: number
  storage_bytes: number
  counts_by_source: Record<string, number>
  counts_by_camera: Record<string, number>
  counts_by_status: Record<string, number>
  pending_annotation: number
  pending_review: number
  pending_cleaning: number
  usable_count: number
}

export interface VideoSummaryFilters {
  source?: VideoSummarySource
  mediaStatus?: VideoSummaryMediaStatus
  handStatus?: VideoSummaryHandStatus
  handResult?: VideoSummaryHandResult
  annotationStatus?: VideoSummaryAnnotationStatus
  cleaningStatus?: VideoSummaryCleaningStatus
  cameraNumber?: number
  capturedFrom?: string
  capturedBefore?: string
  receivedFrom?: string
  receivedBefore?: string
}

export interface MediaJobDeliveryRecovery {
  recovery_id: string
  media_job_id: string
  source_message_id: string
  source_attempt: number
  new_attempt: number
  resulting_job_status: 'QUEUED'
  created_at: string
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

interface ApiErrorBody {
  error_code?: unknown
  error_message?: unknown
  retryable?: unknown
  request_id?: unknown
}

const errorMessages: Record<string, string> = {
  INVALID_CURSOR: '分页位置已经失效，请重新加载第一页。',
  INVALID_TIME_RANGE: '时间范围无效，开始时间必须早于结束时间。',
  CAPTURE_SESSION_NOT_FOUND: '未找到该配药操作记录，或它不属于当前项目。',
  CLIP_ASSET_NOT_FOUND: '未找到该视频资产，或它不属于当前项目。',
  PROJECT_NOT_FOUND: '未找到该项目，或当前账号尚未加入该项目。',
  PROJECT_ACCESS_DENIED: '当前账号无权访问该项目中的数据。',
  PLAYBACK_ACCESS_DENIED: '当前账号无权播放该项目的视频。',
  CLIP_ASSET_NOT_PROBEABLE: '当前视频不处于可检测状态，请刷新后重试。',
  AI_HAND_POSE_MODEL_NOT_CONFIGURED: '手部检测模型尚未由管理员配置，当前不能开始手部检测。',
  CLIP_ASSET_NOT_READY_FOR_AI_CANDIDATES: '视频尚未完成媒体入库，当前不能开始手部检测。',
  CLIP_ASSET_CAMERA_HAND_DETECTION_DISABLED: '当前相机未启用手部检测，请联系管理员调整相机配置。',
  AI_HAND_POSE_MEDIA_SUMMARY_REQUIRED: '媒体检测摘要尚未就绪，请刷新后重试。',
  AI_HAND_POSE_INPUT_EXCEEDS_P0_RESULT_LIMIT: '当前视频长度超出本阶段单视频手部检测范围。',
  AI_HAND_POSE_INFERENCE_FAILED: '手部检测未完成，请稍后重试。',
  AI_HAND_POSE_INVALID_OUTPUT: '手部检测输出无效，请稍后重试。',
  AI_HAND_POSE_RETRY_NOT_ALLOWED: '本次手部检测无法重试，请查看失败原因。',
  AI_HAND_POSE_RETRY_LIMIT_REACHED: '手部检测已达到最大尝试次数，不能继续重试。',
  AI_HAND_POSE_IDEMPOTENCY_KEY_REUSED: '本次手部检测请求与已提交请求冲突，请刷新后重试。',
  MEDIA_JOB_NOT_FOUND: '当前视频没有可恢复的媒体检测任务，请先发起检测或刷新状态。',
  MEDIA_JOB_DELIVERY_RECOVERY_NOT_CONFIRMED:
    '原检测任务尚未确认发布，不能执行恢复。请刷新状态后重试。',
  MEDIA_JOB_DELIVERY_RECOVERY_STATE_CONFLICT:
    '当前检测任务已不再处于可恢复的排队状态，请刷新后确认。',
  MEDIA_JOB_DELIVERY_RECOVERY_SOURCE_REUSED: '当前检测任务已经恢复过，不能再次使用原消息。',
  MEDIA_JOB_DELIVERY_RECOVERY_LIMIT_REACHED: '媒体检测已达到最大尝试次数，不能继续恢复。',
  PLAYBACK_UNSUPPORTED_MEDIA: '当前仅支持播放 MP4 格式的视频。',
  CLIP_ASSET_DELETED: '该视频已删除，不能再播放。',
  MEDIA_REPORT_NOT_FOUND: '媒体检测报告尚未就绪，请在检测完成后刷新状态。',
  OBJECT_STORAGE_UNAVAILABLE: '对象存储暂时无法签发播放地址，请稍后重试。',
  INVALID_REQUEST: '筛选条件格式不正确，请检查后重试。',
}

export class CatalogApiError extends Error {
  readonly errorCode: string
  readonly retryable: boolean
  readonly requestId: string | null
  readonly status: number

  constructor(
    message: string,
    errorCode = 'CATALOG_REQUEST_FAILED',
    retryable = false,
    requestId: string | null = null,
    status = 0,
  ) {
    super(message)
    this.name = 'CatalogApiError'
    this.errorCode = errorCode
    this.retryable = retryable
    this.requestId = requestId
    this.status = status
  }
}

export function listVideoSummaries(
  projectId: string,
  filters: VideoSummaryFilters,
  cursor: string | null,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams({ limit: '50' })
  add(query, 'source', filters.source)
  add(query, 'media_status', filters.mediaStatus)
  add(query, 'hand_status', filters.handStatus)
  add(query, 'hand_result', filters.handResult)
  add(query, 'annotation_status', filters.annotationStatus)
  add(query, 'cleaning_status', filters.cleaningStatus)
  add(query, 'camera_number', filters.cameraNumber?.toString())
  add(query, 'captured_from', filters.capturedFrom)
  add(query, 'captured_before', filters.capturedBefore)
  add(query, 'received_from', filters.receivedFrom)
  add(query, 'received_before', filters.receivedBefore)
  add(query, 'cursor', cursor ?? undefined)
  return readJson<CatalogPage<VideoSummary>>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/videos?${query.toString()}`,
    signal,
  )
}

export function getDataManagementSummary(
  projectId: string,
  filters: VideoSummaryFilters,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams()
  add(query, 'source', filters.source)
  add(query, 'media_status', filters.mediaStatus)
  add(query, 'hand_status', filters.handStatus)
  add(query, 'hand_result', filters.handResult)
  add(query, 'annotation_status', filters.annotationStatus)
  add(query, 'cleaning_status', filters.cleaningStatus)
  add(query, 'camera_number', filters.cameraNumber?.toString())
  add(query, 'captured_from', filters.capturedFrom)
  add(query, 'captured_before', filters.capturedBefore)
  add(query, 'received_from', filters.receivedFrom)
  add(query, 'received_before', filters.receivedBefore)
  const suffix = query.size === 0 ? '' : `?${query.toString()}`
  return readJson<DataManagementSummary>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/video-data/summary${suffix}`,
    signal,
  )
}

export function getClipAsset(projectId: string, clipAssetId: string, signal?: AbortSignal) {
  return readJson<ClipAssetRead>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}`,
    signal,
  )
}

export function listClipAssetActivity(
  projectId: string,
  clipAssetId: string,
  cursor: string | null,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams({ limit: '20' })
  add(query, 'cursor', cursor ?? undefined)
  return readJson<CatalogPage<ClipAssetActivityItem>>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}/activity?${query.toString()}`,
    signal,
  )
}

export function getClipAssetMediaReport(
  projectId: string,
  clipAssetId: string,
  signal?: AbortSignal,
) {
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

export async function requestHandPoseCandidates(projectId: string, videoId: string) {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/videos/${encodeURIComponent(videoId)}/hand-pose-detections`,
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
  if (response.ok) return (await response.json()) as HandPoseJob
  throw await toCatalogError(response)
}

export async function retryHandPoseCandidates(projectId: string, videoId: string) {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/videos/${encodeURIComponent(videoId)}/hand-pose-detections/retry`,
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
  if (response.ok) return (await response.json()) as HandPoseJob
  throw await toCatalogError(response)
}

export async function getLatestHandPoseCandidates(
  projectId: string,
  videoId: string,
  signal?: AbortSignal,
) {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/videos/${encodeURIComponent(videoId)}/hand-pose-candidates`,
    { headers: { Accept: 'application/json' }, cache: 'no-store', signal },
  )
  if (response.ok) return (await response.json()) as HandPoseCandidateResponse
  throw await toCatalogError(response)
}

export async function recoverCurrentClipAssetMediaProbeDelivery(
  projectId: string,
  clipAssetId: string,
  changeReference: string,
  idempotencyKey: string,
) {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}/media-probe-delivery-recoveries`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
        ...csrfHeaders(),
      },
      body: JSON.stringify({ change_reference: changeReference }),
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as MediaJobDeliveryRecovery
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
  let hasReadableErrorBody = false
  try {
    body = (await response.json()) as ApiErrorBody
    hasReadableErrorBody = true
  } catch {
    // Use the safe fallback below for proxy and non-JSON failures.
  }
  const errorCode = typeof body.error_code === 'string' ? body.error_code : 'CATALOG_REQUEST_FAILED'
  return new CatalogApiError(
    errorMessages[errorCode] ?? '暂时无法读取视频数据，请稍后重试。',
    errorCode,
    body.retryable === true || response.status >= 500 || !hasReadableErrorBody,
    typeof body.request_id === 'string' ? body.request_id : null,
    response.status,
  )
}

function add(query: URLSearchParams, name: string, value: string | undefined) {
  const normalized = value?.trim()
  if (normalized) {
    query.set(name, normalized)
  }
}
