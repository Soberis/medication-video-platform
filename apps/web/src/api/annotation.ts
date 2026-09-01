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
  group_input_version: string
  label_schema_id: string
  label_schema_digest: string
  assignee_actor_id: string
  created_by: string
  status: 'ASSIGNED' | 'SUBMITTED' | 'COMPLETED'
  can_submit: boolean
  can_select_primary: boolean
  can_review: boolean
  can_recheck_capture_group_cleaning: boolean
  annotation_revision: AnnotationRevisionSummary | null
  created_at: string
  updated_at: string
  version: number
}

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

export interface AnnotationRevision {
  annotation_revision_id: string
  project_id: string
  annotation_task_id: string
  clip_asset_id: string
  label_schema_id: string
  label_schema_digest: string
  revision_number: number
  annotation_schema_version: 'annotation-document.v1'
  document_sha256: string
  submitted_by: string
  submitted_at: string
  capture_group_id: string
  primary_clip_asset_id: string
  primary_camera_number: number
  confirmed_view_set: CaptureGroupViewSet
  input_version: string
}

export interface AnnotationRevisionSummary {
  annotation_revision_id: string
  label_schema_id: string
  label_schema_digest: string
  revision_number: number
  annotation_document: {
    schema_version: 'annotation-document.v1'
    segments: AnnotationSegment[]
    note?: string
  }
  submitted_by: string
  submitted_at: string
  capture_group_id: string
  primary_clip_asset_id: string
  primary_camera_number: number
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
  primary_clip_asset_id: string
  primary_camera_number: number
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
  primary_clip_asset_id: string
  primary_camera_number: number
  confirmed_view_set: CaptureGroupViewSet
  input_version: string
  qualified_views: CaptureGroupEvidence[]
  rejected_missing_views: CaptureGroupEvidence[]
  candidate_evidence: CaptureGroupEvidence[]
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
  projectId: string,
  signal?: AbortSignal,
): Promise<AnnotationLabelSchema> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/annotation-label-schema`,
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

export async function selectAnnotationPrimaryView(
  projectId: string,
  annotationTaskId: string,
  clipAssetId: string,
): Promise<AnnotationTask> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/annotation-tasks/${encodeURIComponent(annotationTaskId)}/primary-view`,
    {
      method: 'PUT',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-Request-Id': crypto.randomUUID(),
        ...csrfHeaders(),
      },
      body: JSON.stringify({ clip_asset_id: clipAssetId }),
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as AnnotationTask
  throw await annotationTaskApiError(response)
}

export async function submitAnnotationRevision(
  projectId: string,
  annotationTaskId: string,
  segments: AnnotationSegment[],
  note: string,
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
      body: JSON.stringify({ segments, ...(note.trim() ? { note: note.trim() } : {}) }),
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
      }),
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as AnnotationReview
  throw await annotationTaskApiError(response)
}

export async function recheckCaptureGroupCleaning(
  projectId: string,
  annotationRevisionId: string,
): Promise<CaptureGroupCleaning> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/annotation-revisions/${encodeURIComponent(annotationRevisionId)}/capture-group-cleaning-rechecks`,
    {
      method: 'POST',
      headers: { Accept: 'application/json', ...csrfHeaders() },
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as CaptureGroupCleaning
  throw await annotationTaskApiError(response)
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
    ANNOTATION_TASK_ACCESS_DENIED: '当前身份没有访问配药动作标注的权限。',
    ANNOTATION_TASK_NOT_FOUND: '当前标注任务已不存在，请刷新任务列表。',
    ANNOTATION_REVISION_NOT_FOUND: '当前标注修订已不存在，请刷新任务列表。',
    ANNOTATION_TASK_QUALITY_NOT_QUALIFIED:
      '只有事件质量结论为“合格”的 CaptureGroup 才能进入配药动作标注。',
    ANNOTATION_TASK_NOT_READY: '事件内视图完成媒体检测后才能进入配药动作标注。',
    ANNOTATION_TASK_ALREADY_EXISTS:
      '该 CaptureGroup 已有配药动作标注，请在配药动作标注页面继续处理。',
    ANNOTATION_TASK_IDEMPOTENCY_KEY_REUSED: '本次请求与已提交请求冲突，请刷新后重试。',
    ANNOTATION_TASK_NOT_ASSIGNEE: '只有该任务的执行人可以提交标注。',
    ANNOTATION_TASK_ALREADY_SUBMITTED: '该配药动作标注已经提交，等待后续复核。',
    ANNOTATION_TASK_NOT_SUBMITTED: '该标注任务已不是待复核状态，请刷新任务列表。',
    ANNOTATION_TASK_NO_LONGER_QUALIFIED: '该 CaptureGroup 已不再满足标注提交条件。',
    ANNOTATION_SEGMENT_TIME_RANGE_INVALID: '每个时间段的结束时间必须晚于开始时间。',
    ANNOTATION_SEGMENT_EXCEEDS_MEDIA_DURATION: '标注时间段超出了主视图已确认的视频时长。',
    ANNOTATION_LABEL_NOT_ALLOWED: '所选标签不在当前标注规范中，请重新选择。',
    ANNOTATION_PRIMARY_STEP_OVERLAP: '主流程步骤的时间段不能重叠，请调整后重试。',
    ANNOTATION_REVIEW_SELF_REVIEW_FORBIDDEN: '配药动作标注提交者不能复核自己的标注。',
    ANNOTATION_REVISION_ALREADY_REVIEWED: '该标注已经完成复核，请刷新查看结果。',
    ANNOTATION_REVISION_IDEMPOTENCY_KEY_REUSED: '本次复核请求与已有记录冲突，请刷新后重试。',
    ANNOTATION_REVISION_NOT_CURRENT: '当前修订已不是待复核版本，请刷新任务列表查看最新状态。',
    ANNOTATION_REVISION_NO_LONGER_QUALIFIED: '该 CaptureGroup 已不再满足标注复核条件。',
    ANNOTATION_GROUP_CLEANING_RECHECK_NOT_READY:
      '当前配药事件修订、复核或任务状态不允许重新检查训练资格。',
    ANNOTATION_GROUP_CLEANING_SUPPORT_MAPPINGS_UNSUPPORTED:
      '训练资格检查不接受浏览器提供的视图映射；请刷新后重试。',
    INVALID_ANNOTATION_TASK: '提交内容不符合当前标注规范，请检查后重试。',
    PROJECT_ACCESS_DENIED: '当前身份没有执行此标注操作的权限。',
  }
  return new AnnotationTaskApiError(
    messages[errorCode] ?? '配药动作操作暂未完成，请稍后重试。',
    errorCode,
  )
}
