import { csrfHeaders } from '@/api/http'

export interface AnnotationTask {
  annotation_task_id: string
  project_id: string
  clip_asset_id: string
  assignee_actor_id: string
  created_by: string
  status: 'ASSIGNED' | 'SUBMITTED'
  can_submit: boolean
  can_review: boolean
  annotation_revision: AnnotationRevisionSummary | null
  created_at: string
  updated_at: string
  version: number
}

export interface AnnotationSegment {
  start_ms: number
  end_ms: number
  label: string
}

export interface AnnotationRevision {
  annotation_revision_id: string
  project_id: string
  annotation_task_id: string
  clip_asset_id: string
  revision_number: 1
  annotation_schema_version: 'annotation-document.v1'
  document_sha256: string
  submitted_by: string
  submitted_at: string
}

export interface AnnotationRevisionSummary {
  annotation_revision_id: string
  revision_number: 1
  annotation_document: {
    schema_version: 'annotation-document.v1'
    segments: AnnotationSegment[]
    note?: string
  }
  submitted_by: string
  submitted_at: string
  review: AnnotationReview | null
}

export interface AnnotationReview {
  annotation_review_id: string
  project_id: string
  annotation_revision_id: string
  decision: 'APPROVED' | 'REJECTED'
  review_note: string | null
  reviewed_by: string
  reviewed_at: string
}

export class AnnotationTaskApiError extends Error {
  readonly errorCode: string

  constructor(message: string, errorCode: string) {
    super(message)
    this.errorCode = errorCode
  }
}

export async function listAnnotationTasks(projectId: string, signal?: AbortSignal): Promise<AnnotationTask[]> {
  const response = await fetch(`/api/v1/projects/${encodeURIComponent(projectId)}/annotation-tasks`, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    cache: 'no-store',
    signal,
  })
  if (response.ok) return ((await response.json()) as { items: AnnotationTask[] }).items
  throw await annotationTaskApiError(response)
}

export async function createAnnotationTask(
  projectId: string,
  clipAssetId: string,
  assigneeActorId: string,
): Promise<AnnotationTask> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}/annotation-tasks`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
        ...csrfHeaders(),
      },
      body: JSON.stringify({ assignee_actor_id: assigneeActorId }),
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
      body: JSON.stringify({ decision }),
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as AnnotationReview
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
    ANNOTATION_TASK_QUALITY_NOT_QUALIFIED: '只有已判定合格的视频才能创建标注任务。',
    ANNOTATION_TASK_NOT_READY: '视频完成媒体检测后才能创建标注任务。',
    ANNOTATION_TASK_ALREADY_EXISTS: '该视频已有标注任务，请在标注任务中继续处理。',
    ANNOTATION_TASK_IDEMPOTENCY_KEY_REUSED: '本次创建请求与已提交请求冲突，请刷新后重试。',
    ANNOTATION_TASK_NOT_ASSIGNEE: '只有该任务的执行人可以提交标注。',
    ANNOTATION_TASK_ALREADY_SUBMITTED: '该任务已经提交标注，等待后续复核。',
    ANNOTATION_TASK_NO_LONGER_QUALIFIED: '该视频已不再满足标注提交条件。',
    ANNOTATION_SEGMENT_TIME_RANGE_INVALID: '每个时间片段的结束时间必须晚于开始时间。',
    ANNOTATION_REVIEW_SELF_REVIEW_FORBIDDEN: '标注提交者不能复核自己的标注。',
    ANNOTATION_REVISION_ALREADY_REVIEWED: '该标注已经完成复核，请刷新查看结果。',
    ANNOTATION_REVISION_NO_LONGER_QUALIFIED: '该视频已不再满足标注复核条件。',
    PROJECT_ACCESS_DENIED: '当前身份没有执行此标注操作的权限。',
  }
  return new AnnotationTaskApiError(messages[errorCode] ?? '标注任务暂未创建，请稍后重试。', errorCode)
}
