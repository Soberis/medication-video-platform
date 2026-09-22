import { csrfHeaders } from '@/api/http'

export interface AnnotationTask {
  annotation_task_id: string
  project_id: string
  capture_group_id: string
  primary_clip_asset_id?: string | null
  primary_camera_number?: number | null
  capture_group_quality_review_id: string
  group_quality_revision: number
  confirmed_view_set: CaptureGroupViewSet
  /** Missing on historical task payloads; current API returns one row per expected view. */
  camera_views?: AnnotationCameraView[]
  camera_view_progress?: AnnotationCameraViewProgress
  group_input_version: string
  label_schema_id: string
  label_schema_digest: string
  assignee_actor_id: string
  created_by: string
  status: 'ASSIGNED' | 'SUBMITTED' | 'COMPLETED'
  can_submit: boolean
  can_review: boolean
  annotation_revision: AnnotationRevisionSummary | null
  created_at: string
  updated_at: string
  version: number
}

export type AnnotationCameraViewState = 'PENDING' | 'DRAFT' | 'SUBMITTED' | 'EXCLUDED'

export interface AnnotationCameraViewSegment extends AnnotationSegment {
  segment_id: string
  source_candidate_ids: string[]
}

export interface AnnotationCameraView {
  annotation_task_id: string
  camera_number: number
  camera_stream_id: string | null
  clip_asset_id: string | null
  source_asset_sha256: string | null
  media_status: string
  camera_stream_version: number
  clip_asset_version: number
  version: number
  state: AnnotationCameraViewState
  segments: AnnotationCameraViewSegment[]
  note: string
  exclusion_reason: string | null
}

export interface AnnotationCameraViewProgress {
  total: number
  pending: number
  draft: number
  submitted: number
  excluded: number
}

export interface AnnotationCameraViewUpdate {
  expected_version: number
  expected_input_version: string
  state: AnnotationCameraViewState
  segments: Array<{
    segment_id: string
    start_ms: number
    end_ms: number
    label: string
    source_candidate_ids: string[]
  }>
  note: string
  exclusion_reason: string | null
}

export interface AnnotationCorrespondence {
  correspondence_id: string
  status: 'CONFIRMED' | 'UNCERTAIN'
  segments: Array<{ camera_number: number; segment_id: string }>
  reason?: string
}

export interface AnnotationRevisionCameraViewSegment extends AnnotationCameraViewSegment {
  correspondence_id: string
  correspondence_status: AnnotationCorrespondence['status']
}

export interface AnnotationRevisionCameraView {
  camera_number: number
  camera_stream_id: string | null
  clip_asset_id: string | null
  clip_asset_sha256: string | null
  camera_stream_version: number
  clip_asset_version: number
  media_status: string
  disposition: 'SUBMITTED' | 'EXCLUDED'
  included: boolean
  note?: string
  exclusion_reason?: string | null
  segments: AnnotationRevisionCameraViewSegment[]
}

export interface LegacyAnnotationDocument {
  schema_version: 'annotation-document.v1'
  segments: AnnotationSegment[]
  note?: string
}

export interface GroupAnnotationDocument {
  schema_version: 'annotation-document.v2'
  capture_group_id: string
  expected_task_version: number
  input_version: string
  label_schema_id: string
  label_schema_digest: string
  camera_views: AnnotationRevisionCameraView[]
  correspondences: AnnotationCorrespondence[]
  note?: string
}

export type AnnotationDocument = LegacyAnnotationDocument | GroupAnnotationDocument

export interface AnnotationTaskProgress {
  scope: 'PROJECT' | 'ASSIGNEE'
  total: number
  awaiting_submission: number
  rework_required: number
  awaiting_review: number
  completed: number
}

export interface AnnotationTaskList {
  items: AnnotationTask[]
  progress: AnnotationTaskProgress
}

export interface AnnotationSegment {
  start_ms: number
  end_ms: number
  label: string
}

export interface AnnotationLabel {
  label_id: string
  display_name: string
  description: string
  sort_order: number
}

export interface AnnotationLabelSchema {
  label_schema_id: string
  schema_key: string
  schema_version: string
  schema_digest: string
  layer: 'primary_step'
  labels: AnnotationLabel[]
}

export interface CreateAnnotationLabelRequest {
  label_id: string
  display_name: string
  description: string
}

export interface AnnotationRevision {
  annotation_revision_id: string
  project_id: string
  annotation_task_id: string
  clip_asset_id?: string | null
  label_schema_id: string
  label_schema_digest: string
  revision_number: number
  annotation_schema_version: 'annotation-document.v1' | 'annotation-document.v2'
  document_sha256: string
  submitted_by: string
  submitted_at: string
  capture_group_id: string
  primary_clip_asset_id?: string | null
  primary_camera_number?: number | null
  confirmed_view_set: CaptureGroupViewSet
  input_version: string
}

export interface AnnotationRevisionSummary {
  annotation_revision_id: string
  label_schema_id: string
  label_schema_digest: string
  revision_number: number
  annotation_document: AnnotationDocument
  submitted_by: string
  submitted_at: string
  capture_group_id: string
  primary_clip_asset_id?: string | null
  primary_camera_number?: number | null
  confirmed_view_set: CaptureGroupViewSet
  input_version: string
  review: AnnotationReview | null
  capture_group_cleaning?: CaptureGroupCleaning | null
}

export interface CaptureGroupViewSet {
  schema_version: 'capture-group-view-set.v1'
  capture_group_id: string
  capture_group_version: number
  camera_set_snapshot_id: string
  receipt_state: 'RECEIVING' | 'COMPLETE' | 'INCOMPLETE'
  views: Array<{
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
  }>
}

export interface CaptureGroupCleaning {
  capture_group_cleaning_id: string
  project_id?: string
  capture_group_id?: string
  annotation_revision_id?: string
  annotation_review_id?: string
  annotation_review_decision?: 'APPROVED' | 'REJECTED'
  group_quality_result: string
  group_quality_revision: number
  primary_clip_asset_id?: string | null
  primary_camera_number?: number | null
  primary_interval?: CaptureGroupEvidence[]
  view_evidence?: CaptureGroupEvidence[]
  participating_views: CaptureGroupEvidence[]
  excluded_views: CaptureGroupEvidence[]
  mapping_provenance?: CaptureGroupEvidence[]
  candidate_provenance?: CaptureGroupEvidence[]
  rule_set_key?: string
  rule_set_version?: string
  rule_set_digest?: string
  outcome: 'PASS' | 'WARNING' | 'REJECT'
  reason_codes: string[]
  executed_checks: string[]
  input_fingerprint?: string
  evaluated_by?: string
  authorization_basis?: string
  evaluated_at: string
}

export interface AnnotationReview {
  annotation_review_id: string
  project_id: string
  annotation_revision_id: string
  decision: 'APPROVED' | 'REJECTED'
  review_note: string | null
  reviewed_by: string
  reviewed_at: string
  capture_group_id: string
  revision: number
  primary_clip_asset_id?: string | null
  primary_camera_number?: number | null
  confirmed_view_set: CaptureGroupViewSet
  input_version: string
  qualified_views: CaptureGroupEvidence[]
  rejected_missing_views: CaptureGroupEvidence[]
  candidate_evidence: CaptureGroupEvidence[]
  reviewed_camera_numbers?: number[]
  return_camera_numbers?: number[]
}

export type CaptureGroupEvidence = Record<string, unknown>

export class AnnotationTaskApiError extends Error {
  readonly errorCode: string

  constructor(message: string, errorCode: string) {
    super(message)
    this.errorCode = errorCode
  }
}

export async function listAnnotationTasks(
  projectId: string,
  signal?: AbortSignal,
): Promise<AnnotationTaskList> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/annotation-tasks`,
    {
      method: 'GET',
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as AnnotationTaskList
  throw await annotationTaskApiError(response)
}

export async function getAnnotationLabelSchema(
  projectId: string | null,
  signal?: AbortSignal,
): Promise<AnnotationLabelSchema> {
  const response = await fetch(
    projectId === null
      ? '/api/v1/annotation-label-schema'
      : `/api/v1/projects/${encodeURIComponent(projectId)}/annotation-label-schema`,
    {
      method: 'GET',
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as AnnotationLabelSchema
  throw await annotationTaskApiError(response)
}

export async function createAnnotationLabel(
  request: CreateAnnotationLabelRequest,
): Promise<AnnotationLabelSchema> {
  const response = await fetch('/api/v1/annotation-label-schema/labels', {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...csrfHeaders(),
    },
    cache: 'no-store',
    body: JSON.stringify(request),
  })
  if (response.ok) return (await response.json()) as AnnotationLabelSchema
  throw await annotationTaskApiError(response)
}

export async function assignCaptureGroupHumanLabelTask(
  projectId: string,
  captureGroupId: string,
  assigneeActorId: string,
): Promise<AnnotationTask> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/capture-groups/${encodeURIComponent(captureGroupId)}/annotation-tasks`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
        'X-Request-Id': crypto.randomUUID(),
        ...csrfHeaders(),
      },
      body: JSON.stringify({ assignee_actor_id: assigneeActorId.trim() }),
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as AnnotationTask
  throw await annotationTaskApiError(response)
}

export async function updateAnnotationCameraView(
  projectId: string,
  annotationTaskId: string,
  cameraNumber: number,
  update: AnnotationCameraViewUpdate,
): Promise<AnnotationTask> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/annotation-tasks/${encodeURIComponent(annotationTaskId)}/camera-views/${encodeURIComponent(String(cameraNumber))}`,
    {
      method: 'PUT',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
        ...csrfHeaders(),
      },
      body: JSON.stringify(update),
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as AnnotationTask
  throw await annotationTaskApiError(response)
}

export async function submitAnnotationRevision(
  projectId: string,
  annotationTaskId: string,
  request: {
    expectedTaskVersion: number
    inputVersion: string
    correspondences: AnnotationCorrespondence[]
    note: string
  },
): Promise<AnnotationRevision> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/annotation-tasks/${encodeURIComponent(annotationTaskId)}/revisions`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
        ...csrfHeaders(),
      },
      body: JSON.stringify({
        expected_task_version: request.expectedTaskVersion,
        expected_input_version: request.inputVersion,
        correspondences: request.correspondences,
        ...(request.note.trim() ? { note: request.note.trim() } : {}),
      }),
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as AnnotationRevision
  throw await annotationTaskApiError(response)
}

export async function reviewAnnotationRevision(
  projectId: string,
  annotationRevisionId: string,
  decision: AnnotationReview['decision'],
  reviewNote = '',
  options?: { reviewedCameraNumbers: number[]; returnCameraNumbers: number[] },
): Promise<AnnotationReview> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/annotation-revisions/${encodeURIComponent(annotationRevisionId)}/review`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
        ...csrfHeaders(),
      },
      body: JSON.stringify({
        decision,
        ...(reviewNote.trim() ? { review_note: reviewNote.trim() } : {}),
        ...(options
          ? {
              reviewed_camera_numbers: options.reviewedCameraNumbers,
              return_camera_numbers: options.returnCameraNumbers,
            }
          : {}),
      }),
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as AnnotationReview
  throw await annotationTaskApiError(response)
}

export async function downloadHumanDemonstrationHdf5(
  projectId: string,
  captureGroupIds?: string[],
  signal?: AbortSignal,
): Promise<{ blob: Blob; fileName: string }> {
  const query = new URLSearchParams()
  for (const captureGroupId of captureGroupIds ?? [])
    query.append('capture_group_id', captureGroupId)
  const querySuffix = query.size ? `?${query}` : ''
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/human-demonstrations/hdf5/v2/download${querySuffix}`,
    {
      method: 'GET',
      headers: { Accept: 'application/x-hdf5' },
      cache: 'no-store',
      signal,
    },
  )
  if (!response.ok) throw await annotationTaskApiError(response)
  return {
    blob: await response.blob(),
    fileName:
      response.headers.get('Content-Disposition')?.match(/filename="([^"]+)"/)?.[1] ??
      `human_demonstration_hdf5_v2_${captureGroupIds?.length ? 'selection' : 'project'}.hdf5`,
  }
}

async function annotationTaskApiError(response: Response): Promise<AnnotationTaskApiError> {
  let errorCode = 'ANNOTATION_TASK_REQUEST_FAILED'
  try {
    const body = (await response.json()) as { error_code?: unknown }
    if (typeof body.error_code === 'string') errorCode = body.error_code
  } catch {
    // Keep the stable fallback for proxy and non-JSON failures.
  }
  const messages: Record<string, string> = {
    ANNOTATION_TASK_ACCESS_DENIED: '当前身份没有访问标注任务的权限。',
    ANNOTATION_TASK_NOT_FOUND: '标注任务不存在，请刷新任务列表。',
    ANNOTATION_REVISION_NOT_FOUND: '标注修订不存在，请刷新任务列表。',
    ANNOTATION_TASK_QUALITY_NOT_QUALIFIED: '采集记录整体质量为“合格”后才能开始标注。',
    ANNOTATION_TASK_GROUP_QUALITY_NOT_QUALIFIED: '请先完成机位质量和整体质量判断，再开始标注。',
    ANNOTATION_TASK_ALREADY_ASSIGNED: '该采集记录已分配给其他成员，请刷新任务状态。',
    ANNOTATION_TASK_ASSIGNMENT_IDEMPOTENCY_KEY_REUSED: '本次分配请求与已有请求冲突，请刷新后重试。',
    ANNOTATION_TASK_NOT_READY: '采集记录中的视频完成媒体检测后才能开始标注。',
    ANNOTATION_TASK_ALREADY_EXISTS: '该采集记录已有标注任务，请继续处理。',
    ANNOTATION_TASK_IDEMPOTENCY_KEY_REUSED: '本次请求与已提交请求冲突，请刷新后重试。',
    ANNOTATION_TASK_NOT_ASSIGNEE: '只有该任务的执行人可以提交标注。',
    ANNOTATION_TASK_ALREADY_SUBMITTED: '标注已提交，等待复核。',
    ANNOTATION_TASK_NOT_SUBMITTED: '该标注任务已不是待复核状态，请刷新任务列表。',
    ANNOTATION_TASK_NO_LONGER_QUALIFIED: '该采集记录当前不满足标注提交条件。',
    ANNOTATION_SEGMENT_TIME_RANGE_INVALID: '每个时间段的结束时间必须晚于开始时间。',
    ANNOTATION_SEGMENT_EXCEEDS_MEDIA_DURATION: '标注时间段超出了当前机位已确认的视频时长。',
    ANNOTATION_MEDIA_DURATION_UNVERIFIED: '当前机位尚无已验证的视频时长；请完成媒体检查后重试。',
    ANNOTATION_PRIMARY_VIEW_INPUT_CHANGED: '采集或质量输入已变化，请管理员更新任务输入后重新标注。',
    ANNOTATION_PRIMARY_VIEW_NOT_ELIGIBLE: '该机位当前不满足媒体与质量条件。',
    ANNOTATION_PRIMARY_VIEW_FROZEN: '当前输入已有正式修订，已提交机位不能修改。',
    ANNOTATION_LABEL_NOT_ALLOWED: '所选标签不在当前标注规范中，请重新选择。',
    ANNOTATION_PRIMARY_STEP_OVERLAP: '主要步骤的时间段不能重叠，请调整后重试。',
    ANNOTATION_REVIEW_SELF_REVIEW_FORBIDDEN: '标注提交者不能复核自己的标注。',
    ANNOTATION_REVISION_ALREADY_REVIEWED: '该标注已完成复核，请刷新查看结果。',
    ANNOTATION_REVISION_IDEMPOTENCY_KEY_REUSED: '本次复核请求与已有记录冲突，请刷新后重试。',
    ANNOTATION_REVISION_NOT_CURRENT: '当前修订已不是待复核版本，请刷新任务列表查看最新状态。',
    ANNOTATION_REVISION_NO_LONGER_QUALIFIED: '该采集记录当前不满足标注复核条件。',
    ANNOTATION_GROUP_CLEANING_RECHECK_NOT_READY:
      '当前采集记录的标注修订、复核或任务状态不允许重新执行标注检查。',
    ANNOTATION_GROUP_CLEANING_SUPPORT_MAPPINGS_UNSUPPORTED:
      '标注检查不接受浏览器提供的视图映射；请刷新后重试。',
    HDF5_EXPORT_UNAVAILABLE: 'HDF5 演示数据仅对合成数据配置开放。',
    HDF5_EXPORT_NOT_READY: '当前没有审核通过的人工标注，暂时不能导出 HDF5 演示数据。',
    HDF5_EXPORT_DATA_INVALID: 'HDF5 演示数据的当前来源校验未通过，请刷新后重试。',
    HDF5_EXPORT_FAILED: 'HDF5 演示数据未能生成，请稍后重试。',
    ANNOTATION_LABEL_ALREADY_EXISTS: '当前标签库中已存在相同的稳定标签 ID。',
    INVALID_ANNOTATION_TASK: '提交内容不符合当前标注规范，请检查后重试。',
    PROJECT_ACCESS_DENIED: '当前身份没有执行此标注操作的权限。',
  }
  return new AnnotationTaskApiError(
    messages[errorCode] ?? '标注操作未完成，请稍后重试。',
    errorCode,
  )
}
