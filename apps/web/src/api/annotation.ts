import { csrfHeaders } from '@/api/http'

export interface AnnotationTask {
  annotation_task_id: string
  project_id: string
  clip_asset_id: string
  assignee_actor_id: string
  created_by: string
  status: 'ASSIGNED'
  created_at: string
  updated_at: string
  version: number
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
    PROJECT_ACCESS_DENIED: '当前身份没有创建标注任务的权限。',
  }
  return new AnnotationTaskApiError(messages[errorCode] ?? '标注任务暂未创建，请稍后重试。', errorCode)
}
