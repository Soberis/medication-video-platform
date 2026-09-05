export interface HandLandmarkExportPreview {
  schema_version: 'hand-landmarks.v1'
  hand_detection_view_count: number
  completed_view_count: number
  views_with_hand: number
  sample_count: number
  no_job_view_count: number
  queued_view_count: number
  running_view_count: number
  failed_view_count: number
  no_hand_view_count: number
  file_name: string
  generated_at: string
  media_type: string
}

export class HandLandmarkExportApiError extends Error {
  readonly errorCode: string

  constructor(message: string, errorCode: string) {
    super(message)
    this.name = 'HandLandmarkExportApiError'
    this.errorCode = errorCode
  }
}

export async function getHandLandmarkExportPreview(
  projectId: string,
  captureGroupId?: string,
  signal?: AbortSignal,
): Promise<HandLandmarkExportPreview> {
  const query = captureGroupId ? `?capture_group_id=${encodeURIComponent(captureGroupId)}` : ''
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/hand-landmarks/v1/preview${query}`,
    {
      method: 'GET',
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as HandLandmarkExportPreview
  throw await handLandmarkExportApiError(
    response,
    '暂时无法读取 MediaPipe 手部检测关键点，请稍后重试。',
  )
}

export async function downloadHandLandmarkExport(
  projectId: string,
  generatedAt: string,
  captureGroupId?: string,
  signal?: AbortSignal,
): Promise<Blob> {
  const query = new URLSearchParams({ generated_at: generatedAt })
  if (captureGroupId) query.set('capture_group_id', captureGroupId)
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/hand-landmarks/v1/download?${query}`,
    {
      method: 'GET',
      headers: { Accept: 'text/csv; charset=UTF-8' },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return await response.blob()
  throw await handLandmarkExportApiError(
    response,
    'MediaPipe 手部检测关键点暂时无法导出，请稍后重试。',
  )
}

async function handLandmarkExportApiError(
  response: Response,
  fallbackMessage: string,
): Promise<HandLandmarkExportApiError> {
  let errorCode = 'HAND_LANDMARK_EXPORT_REQUEST_FAILED'
  try {
    const body = (await response.json()) as { error_code?: unknown }
    if (typeof body.error_code === 'string') errorCode = body.error_code
  } catch {
    // Keep the stable fallback for proxy and non-JSON failures.
  }
  const messages: Record<string, string> = {
    PROJECT_ACCESS_DENIED: '当前身份没有项目手部检测关键点导出的权限。',
    PROJECT_NOT_FOUND: '未找到该项目，或当前身份无权访问。',
    CAPTURE_GROUP_NOT_FOUND: '未找到该数据组，或该数据组不属于当前项目。',
    HAND_LANDMARK_EXPORT_UNAVAILABLE: '当前环境暂未开放手部检测关键点导出。',
    HAND_LANDMARK_EXPORT_EVIDENCE_INVALID: '当前手部关键点证据不完整，暂时无法导出。',
    HAND_LANDMARK_EXPORT_FAILED: '手部检测关键点导出未完成，请稍后重试。',
    HAND_LANDMARK_EXPORT_DATA_INVALID: '手部关键点导出内容校验未通过，请稍后重试。',
  }
  return new HandLandmarkExportApiError(messages[errorCode] ?? fallbackMessage, errorCode)
}
