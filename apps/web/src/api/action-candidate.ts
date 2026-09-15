import { csrfHeaders } from '@/api/http'

export interface ActionCandidate {
  action_candidate_id: string
  ai_job_id: string
  project_id: string
  capture_group_id: string
  clip_asset_id: string
  start_ms: number
  end_ms: number
  action_label: string
  annotation_task_id?: string | null
  label_schema_id?: string | null
  label_schema_digest?: string | null
  confidence: number | null
  source_window: number
  stitched_windows: number[]
  created_at: string
}

export interface ActionCandidateJob {
  ai_job_id: string
  project_id: string
  capture_group_id: string
  clip_asset_id: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  attempt: number
  created_at: string
  updated_at: string
}

export async function fetchActionCandidates(
  projectId: string,
  captureGroupId: string,
): Promise<ActionCandidate[]> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/capture-groups/${encodeURIComponent(captureGroupId)}/action-candidates`,
    {
      method: 'GET',
      headers: {
        Accept: 'application/json',
        ...csrfHeaders(),
      },
    },
  )
  if (!response.ok) {
    if (response.status === 404) return []
    throw new Error(`Failed to fetch action candidates: ${response.status}`)
  }
  return (await response.json()) as ActionCandidate[]
}

export async function fetchActionCandidateJob(
  projectId: string,
  captureGroupId: string,
): Promise<ActionCandidateJob | null> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/capture-groups/${encodeURIComponent(captureGroupId)}/action-candidate-job`,
    {
      method: 'GET',
      headers: {
        Accept: 'application/json',
        ...csrfHeaders(),
      },
    },
  )
  if (!response.ok) {
    if (response.status === 204 || response.status === 404) return null
    throw new Error(`Failed to fetch action candidate job: ${response.status}`)
  }
  if (response.status === 204) return null
  return (await response.json()) as ActionCandidateJob
}

export async function dispatchActionCandidateJob(
  projectId: string,
  captureGroupId: string,
): Promise<ActionCandidateJob> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/capture-groups/${encodeURIComponent(captureGroupId)}/action-candidate-jobs`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        ...(typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
          ? { 'X-Request-ID': crypto.randomUUID() }
          : {}),
        ...csrfHeaders(),
      },
    },
  )
  if (!response.ok) {
    let errorCode = ''
    try {
      const body = (await response.json()) as { error_code?: unknown }
      if (typeof body.error_code === 'string') errorCode = body.error_code
    } catch {
      // Proxies may return a non-JSON response.
    }
    const messages: Record<string, string> = {
      AI_ACTION_TASK_SCHEMA_REQUIRED:
        '请先由项目管理员分配标注任务，绑定标签规范后再启动动作预标注。',
      AI_ACTION_SOURCE_INVALID: '请先为标注任务选择有效的主视频。',
      CLIP_ASSET_NOT_READY_FOR_AI_CANDIDATES: '主视频尚未完成媒体处理，请稍后刷新。',
      AI_ACTION_RETRY_STATE_CONFLICT: '任务状态已变化，请刷新后查看。',
      AI_ACTION_RETRY_LIMIT_REACHED: '已达到重试上限，请继续人工标注或联系管理员。',
      AI_ACTION_RETRY_NOT_ALLOWED: '当前失败无法重试，请继续人工标注或联系管理员。',
      PROJECT_ACCESS_DENIED: '当前账号没有执行此操作的权限。',
    }
    throw new Error(
      messages[errorCode] ?? `动作预标注请求未完成（HTTP ${response.status}），请刷新后重试。`,
    )
  }
  return (await response.json()) as ActionCandidateJob
}
