import { csrfHeaders } from '@/api/http'

export type CaptureGroupReceiptState = 'RECEIVING' | 'COMPLETE' | 'INCOMPLETE'
export type CaptureGroupAssemblyStatus =
  | 'OPEN'
  | 'ASSEMBLING'
  | 'COMPLETE'
  | 'INCOMPLETE'
  | 'CLOSED'
export type CaptureGroupQualityResult =
  | 'UNREVIEWED'
  | 'QUALIFIED'
  | 'QUALIFIED_WITH_EXCLUSIONS'
  | 'NEEDS_REVIEW'
  | 'REJECTED'
export type CaptureGroupQualityCommandResult = Exclude<CaptureGroupQualityResult, 'UNREVIEWED'>

export interface CaptureGroupSnapshotMember {
  camera_channel_id: string
  camera_index: number
  camera_number: number
  camera_role: string
  mount_position: string
  enabled: boolean
  enabled_for_hand_detection: boolean
}

export interface CaptureGroupSnapshot {
  camera_set_snapshot_id: string
  source_camera_set_id: string
  camera_set_key: string
  configuration_version: number
  layout_version: string
  expected_camera_count: number
  members: CaptureGroupSnapshotMember[]
}

export interface CaptureGroupStream {
  camera_stream_id: string
  clip_asset_id: string
  client_file_id: string
  camera_channel_id: string
  camera_index: number
  camera_number: number
  camera_role: string
  mount_position: string
  record_start_utc: string | null
  time_offset_ms: number
  sync_status: 'UNKNOWN' | 'PASS' | 'WARNING' | 'REJECT'
  stream_status: 'BOUND' | 'INGESTED' | 'INGEST_FAILED' | 'UNAVAILABLE'
  quality_status: 'PENDING' | 'PASS' | 'WARNING' | 'REJECT'
  enabled_for_hand_detection: boolean
}

export interface CaptureGroup {
  capture_session_id: string
  capture_group_id: string
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
  assembly_status: CaptureGroupAssemblyStatus
  quality_status: 'PENDING' | 'PASS' | 'WARNING' | 'REJECT'
  capture_group_key: string | null
  capture_group_key_source: 'EXTERNAL_CAPTURE_SESSION_ID' | 'UPSTREAM_MANIFEST_KEY' | null
  capture_group_manifest_id: string | null
  expected_camera_numbers: number[]
  received_camera_numbers: number[]
  missing_camera_numbers: number[]
  receipt_state: CaptureGroupReceiptState
  receipt_finalized_at: string | null
  camera_set_snapshot: CaptureGroupSnapshot
  streams: CaptureGroupStream[]
  created_at: string
}

export interface CaptureGroupSummary {
  capture_group_id: string
  capture_group_key: string | null
  device_time_utc: string | null
  receipt_state: CaptureGroupReceiptState
  expected_camera_count: number
  received_camera_count: number
  missing_camera_numbers: number[]
  created_at: string
}

export interface CaptureGroupListResponse {
  items: CaptureGroupSummary[]
  next_cursor: string | null
}

export interface CaptureGroupQualityView {
  camera_number: number
  camera_stream_id: string | null
  clip_asset_id: string | null
  camera_channel_id: string | null
  present: boolean
  media_status: string
  technical_quality_status: string | null
  stream_version: number
  clip_asset_version: number
  asset_available: boolean
  quality_review_decision: 'UNREVIEWED' | 'QUALIFIED' | 'NEEDS_REVIEW' | 'REJECTED'
  quality_review_version: number
}

export interface CaptureGroupViewSet {
  schema_version: 'capture-group-view-set.v1'
  capture_group_id: string
  capture_group_version: number
  camera_set_snapshot_id: string
  receipt_state: CaptureGroupReceiptState
  views: CaptureGroupQualityView[]
}

export interface CaptureGroupQualityReview {
  capture_group_quality_review_id: string | null
  project_id: string
  capture_group_id: string
  result: CaptureGroupQualityResult
  reason: string | null
  reviewer_actor_id: string | null
  revision: number
  version: number
  decided_at: string | null
  confirmed_view_set: CaptureGroupViewSet | null
  input_version: string | null
  confirmed_view_set_sha256: string | null
}

export interface CaptureGroupQualityReviewHistory {
  items: CaptureGroupQualityReview[]
}

export interface CaptureGroupQualityReviewBatchResponse {
  items: CaptureGroupQualityReview[]
}

export class CaptureGroupApiError extends Error {
  readonly errorCode: string
  readonly status: number

  constructor(message: string, errorCode = 'CAPTURE_GROUP_REQUEST_FAILED', status = 0) {
    super(message)
    this.name = 'CaptureGroupApiError'
    this.errorCode = errorCode
    this.status = status
  }
}

export function isCaptureGroupQualityForbidden(error: unknown) {
  return (
    error instanceof CaptureGroupApiError &&
    (error.status === 403 || error.errorCode === 'CAPTURE_GROUP_QUALITY_ACCESS_DENIED')
  )
}

const errorMessages: Record<string, string> = {
  CAPTURE_GROUP_NOT_FOUND: '未找到该数据组，或它不属于当前项目。',
  INVALID_CURSOR: '分页位置已经失效，请重新加载第一页。',
  PROJECT_ACCESS_DENIED: '当前账号无权访问该项目中的数据组。',
  CAPTURE_GROUP_QUALITY_ACCESS_DENIED: '当前账号没有读取事件质量的权限。',
  CAPTURE_GROUP_QUALITY_RECEIPT_NOT_FINALIZED:
    '回执仍在接收中；当前只能记录“需复核”，不能提交最终组质量结论。',
  CAPTURE_GROUP_QUALITY_NO_QUALIFIED_VIEW: '至少需要一路人工判定为合格的视图，才能提交准入结论。',
  CAPTURE_GROUP_QUALITY_INCOMPLETE_REQUIRES_EXCLUSIONS:
    '收件不完整的组不能直接判定为合格；请确认缺失视图并使用“合格（排除部分视图）”。',
  CAPTURE_GROUP_QUALITY_QUALIFIED_HAS_EXCLUSIONS:
    '存在拒绝视图时不能判定为整体合格；请使用“合格（排除部分视图）”。',
  CAPTURE_GROUP_QUALITY_VIEW_NOT_RESOLVED:
    '所有已接收视图都需要先完成“合格”或“不合格”的人工质量判定。',
  CAPTURE_GROUP_QUALITY_CONFLICT: '组质量事实已被更新，请刷新后重试。',
  CAPTURE_GROUP_QUALITY_IDEMPOTENCY_KEY_REUSED: '本次组质量请求与已有请求冲突，请刷新后重试。',
  ANNOTATION_TASK_GROUP_QUALITY_NOT_QUALIFIED:
    '当前配药事件质量不是准入状态，暂不能提交新的标注修订。',
  ANNOTATION_PRIMARY_VIEW_NOT_ELIGIBLE: '主视图必须是已接收、媒体可用且人工判定合格的视图。',
  ANNOTATION_PRIMARY_VIEW_FROZEN: '首个正式修订已经存在，主视图时间基准已锁定。',
  ANNOTATION_PRIMARY_VIEW_INPUT_CHANGED: '组内事实已变化，请重新提交组质量结论后再选择主视图。',
  ANNOTATION_TASK_NOT_ASSIGNEE: '只有任务执行人可以选择主视图。',
  ANNOTATION_TASK_NOT_FOUND: '未找到该标注任务，请刷新后重试。',
  ANNOTATION_PRIMARY_VIEW_REQUIRED: '提交修订前必须先选择主视图。',
}

export function getCaptureGroup(projectId: string, captureGroupId: string, signal?: AbortSignal) {
  return readJson<CaptureGroup>(path(projectId, captureGroupId), signal)
}

export function listCaptureGroups(
  projectId: string,
  cursor: string | null = null,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams({ limit: '20' })
  if (cursor) query.set('cursor', cursor)
  return readJson<CaptureGroupListResponse>(
    `${collectionPath(projectId)}?${query.toString()}`,
    signal,
  )
}

export async function finalizeCaptureGroupReceipt(projectId: string, captureGroupId: string) {
  const response = await fetch(`${path(projectId, captureGroupId)}/receipt/finalize`, {
    method: 'POST',
    headers: { Accept: 'application/json', ...csrfHeaders() },
    cache: 'no-store',
  })
  if (response.ok) return (await response.json()) as CaptureGroup
  throw await toCaptureGroupError(response)
}

export function getCaptureGroupQualityReview(
  projectId: string,
  captureGroupId: string,
  signal?: AbortSignal,
) {
  return readJson<CaptureGroupQualityReview>(
    `${path(projectId, captureGroupId)}/quality-review`,
    signal,
  )
}

export function listCaptureGroupQualityReviews(
  projectId: string,
  captureGroupIds: string[],
  signal?: AbortSignal,
) {
  const query = new URLSearchParams()
  for (const captureGroupId of captureGroupIds) {
    query.append('capture_group_id', captureGroupId)
  }
  return readJson<CaptureGroupQualityReviewBatchResponse>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/capture-group-quality-reviews?${query.toString()}`,
    signal,
  )
}

export function listCaptureGroupQualityReviewHistory(
  projectId: string,
  captureGroupId: string,
  signal?: AbortSignal,
) {
  return readJson<CaptureGroupQualityReviewHistory>(
    `${path(projectId, captureGroupId)}/quality-review/history`,
    signal,
  )
}

export async function recordCaptureGroupQualityReview(
  projectId: string,
  captureGroupId: string,
  result: CaptureGroupQualityCommandResult,
  reason: string,
  expectedRevision: number,
) {
  const response = await fetch(`${path(projectId, captureGroupId)}/quality-review`, {
    method: 'PUT',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
      'X-Request-Id': crypto.randomUUID(),
      ...csrfHeaders(),
    },
    body: JSON.stringify({ result, reason: reason.trim(), expected_revision: expectedRevision }),
    cache: 'no-store',
  })
  if (response.ok) return (await response.json()) as CaptureGroupQualityReview
  throw await toCaptureGroupError(response)
}

function path(projectId: string, captureGroupId: string) {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/capture-groups/${encodeURIComponent(captureGroupId)}`
}

function collectionPath(projectId: string) {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/capture-groups`
}

async function readJson<T>(requestPath: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(requestPath, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    cache: 'no-store',
    signal,
  })
  if (response.ok) return (await response.json()) as T
  throw await toCaptureGroupError(response)
}

async function toCaptureGroupError(response: Response) {
  let errorCode = 'CAPTURE_GROUP_REQUEST_FAILED'
  try {
    const body = (await response.json()) as { error_code?: unknown }
    if (typeof body.error_code === 'string') errorCode = body.error_code
  } catch {
    // Keep the safe fallback for a proxy and non-JSON failure.
  }
  return new CaptureGroupApiError(
    errorMessages[errorCode] ?? '数据组操作暂未完成，请稍后重试。',
    errorCode,
    response.status,
  )
}
