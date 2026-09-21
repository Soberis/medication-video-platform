import { csrfHeaders } from '@/api/http'
import type { AnnotationSegment } from '@/api/annotation'

// --- Types ---

export type ClipDisposition = 'PENDING' | 'DRAFTING' | 'SUBMITTED' | 'EXCLUDED'

export interface ClipAnnotationSubmission {
  clip_annotation_submission_id: string
  project_id: string
  annotation_task_id: string
  capture_group_id: string
  clip_asset_id: string
  camera_number: number
  disposition: ClipDisposition
  annotation_document: {
    schema_version: 'annotation-document.v1'
    segments: AnnotationSegment[]
    note?: string
  } | null
  document_sha256: string | null
  exclusion_reason: string | null
  draft_document: {
    schema_version: 'annotation-document.v1'
    segments: AnnotationSegment[]
    note?: string
  } | null
  submitted_by: string | null
  submitted_at: string | null
  input_version: string
  created_at: string
  updated_at: string
  version: number
}

export interface ClipAnnotationSummary {
  total: number
  pending: number
  drafting: number
  submitted: number
  excluded: number
}

export interface ClipAnnotationSubmissionList {
  items: ClipAnnotationSubmission[]
  summary: ClipAnnotationSummary
}

// --- API functions ---

export async function listClipSubmissions(
  projectId: string,
  annotationTaskId: string,
  signal?: AbortSignal,
): Promise<ClipAnnotationSubmissionList> {
  const response = await fetch(
    `/api/v1/projects/${enc(projectId)}/annotation-tasks/${enc(annotationTaskId)}/clip-submissions`,
    {
      method: 'GET',
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as ClipAnnotationSubmissionList
  throw await clipAnnotationApiError(response)
}

export async function submitClipAnnotation(
  projectId: string,
  annotationTaskId: string,
  clipAssetId: string,
  segments: AnnotationSegment[],
  note?: string,
): Promise<ClipAnnotationSubmission> {
  const response = await fetch(
    `/api/v1/projects/${enc(projectId)}/annotation-tasks/${enc(annotationTaskId)}/clip-submissions/${enc(clipAssetId)}/submit`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
        'X-Request-Id': crypto.randomUUID(),
        ...csrfHeaders(),
      },
      body: JSON.stringify({
        segments: segments.map((s) => ({
          start_ms: s.start_ms,
          end_ms: s.end_ms,
          label: s.label,
        })),
        ...(note?.trim() ? { note: note.trim() } : {}),
      }),
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as ClipAnnotationSubmission
  throw await clipAnnotationApiError(response)
}

export async function excludeClip(
  projectId: string,
  annotationTaskId: string,
  clipAssetId: string,
  reason: string,
): Promise<ClipAnnotationSubmission> {
  const response = await fetch(
    `/api/v1/projects/${enc(projectId)}/annotation-tasks/${enc(annotationTaskId)}/clip-submissions/${enc(clipAssetId)}/exclude`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-Request-Id': crypto.randomUUID(),
        ...csrfHeaders(),
      },
      body: JSON.stringify({ reason }),
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as ClipAnnotationSubmission
  throw await clipAnnotationApiError(response)
}

export async function restoreClip(
  projectId: string,
  annotationTaskId: string,
  clipAssetId: string,
): Promise<ClipAnnotationSubmission> {
  const response = await fetch(
    `/api/v1/projects/${enc(projectId)}/annotation-tasks/${enc(annotationTaskId)}/clip-submissions/${enc(clipAssetId)}/restore`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-Request-Id': crypto.randomUUID(),
        ...csrfHeaders(),
      },
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as ClipAnnotationSubmission
  throw await clipAnnotationApiError(response)
}

export async function saveClipDraft(
  projectId: string,
  annotationTaskId: string,
  clipAssetId: string,
  draftDocument: Record<string, unknown>,
): Promise<ClipAnnotationSubmission> {
  const response = await fetch(
    `/api/v1/projects/${enc(projectId)}/annotation-tasks/${enc(annotationTaskId)}/clip-submissions/${enc(clipAssetId)}/draft`,
    {
      method: 'PUT',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-Request-Id': crypto.randomUUID(),
        ...csrfHeaders(),
      },
      body: JSON.stringify(draftDocument),
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as ClipAnnotationSubmission
  throw await clipAnnotationApiError(response)
}

export async function reopenClip(
  projectId: string,
  annotationTaskId: string,
  clipAssetId: string,
): Promise<ClipAnnotationSubmission> {
  const response = await fetch(
    `/api/v1/projects/${enc(projectId)}/annotation-tasks/${enc(annotationTaskId)}/clip-submissions/${enc(clipAssetId)}/reopen`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-Request-Id': crypto.randomUUID(),
        ...csrfHeaders(),
      },
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as ClipAnnotationSubmission
  throw await clipAnnotationApiError(response)
}

// --- Helpers ---

function enc(value: string): string {
  return encodeURIComponent(value)
}

async function clipAnnotationApiError(response: Response): Promise<Error> {
  let errorCode = 'CLIP_ANNOTATION_REQUEST_FAILED'
  try {
    const body = (await response.json()) as { error_code?: unknown; error_message?: unknown }
    if (typeof body.error_code === 'string') errorCode = body.error_code
  } catch {
    // non-JSON
  }
  const messages: Record<string, string> = {
    CLIP_ANNOTATION_SUBMISSION_NOT_FOUND: '机位标注记录不存在，请刷新页面。',
    CLIP_ANNOTATION_CANNOT_DRAFT: '当前机位状态不允许保存草稿。',
    CLIP_ANNOTATION_CANNOT_SUBMIT: '当前机位状态不允许提交标注。',
    CLIP_ANNOTATION_CANNOT_EXCLUDE: '当前机位状态不允许排除。',
    CLIP_ANNOTATION_NOT_EXCLUDED: '该机位未被排除，无法恢复。',
    CLIP_ANNOTATION_NOT_SUBMITTED: '该机位未提交，无法重新编辑。',
    CLIP_ANNOTATION_EXCLUSION_REASON_REQUIRED: '排除机位时必须填写原因。',
    ANNOTATION_TASK_NOT_ASSIGNEE: '只有该任务的执行人可以修改机位标注。',
    ANNOTATION_TASK_NOT_EDITABLE: '当前任务状态不允许修改机位标注。',
    ANNOTATION_TASK_NOT_FOUND: '标注任务不存在，请刷新任务列表。',
    ANNOTATION_SEGMENT_TIME_RANGE_INVALID: '每个时间段的结束时间必须晚于开始时间。',
    ANNOTATION_SEGMENT_EXCEEDS_MEDIA_DURATION: '标注时间段超出了视频已确认的时长。',
    ANNOTATION_MEDIA_DURATION_UNVERIFIED: '该机位尚无已验证的视频时长。',
    ANNOTATION_PRIMARY_STEP_OVERLAP: '动作时间段不能重叠，请调整后重试。',
    ANNOTATION_LABEL_NOT_ALLOWED: '所选标签不在当前标注规范中。',
  }
  return new Error(messages[errorCode] ?? '机位标注操作未完成，请稍后重试。')
}
