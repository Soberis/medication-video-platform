export type HandLandmarkExportReasonCode =
  | 'MEDIAPIPE_NOT_FINISHED'
  | 'NO_HAND_DETECTED'
  | 'MEDIAPIPE_FAILED'
  | 'NO_VALID_21_POINT_SAMPLE'

export interface HandLandmarkExportPreview {
  schema_version: 'hand-landmarks.v1'
  eligible_event_count: number
  events_with_landmarks: number
  sample_count: number
  events_without_landmarks: number
  file_name: string
  content_type?: string
  reason_counts?: Partial<Record<HandLandmarkExportReasonCode, number>>
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
  signal?: AbortSignal,
): Promise<HandLandmarkExportPreview> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/hand-landmarks/v1/preview`,
    {
      method: 'GET',
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as HandLandmarkExportPreview
  throw await handLandmarkExportApiError(response, '暂时无法读取手部训练数据，请稍后重试。')
}

export async function downloadHandLandmarkExport(
  projectId: string,
  signal?: AbortSignal,
): Promise<Blob> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/hand-landmarks/v1/download`,
    {
      method: 'GET',
      headers: { Accept: 'text/csv; charset=UTF-8' },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return await response.blob()
  throw await handLandmarkExportApiError(response, '手部训练数据暂时无法导出，请稍后重试。')
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
    PROJECT_ACCESS_DENIED: '当前身份没有项目手部训练数据导出的权限。',
    PROJECT_NOT_FOUND: '未找到该项目，或当前身份无权访问。',
    HAND_LANDMARK_EXPORT_UNAVAILABLE: '当前环境暂未开放手部训练数据导出。',
    HAND_LANDMARK_EXPORT_EVIDENCE_INVALID: '当前手部关键点证据不完整，暂时无法导出。',
    HAND_LANDMARK_EXPORT_FAILED: '手部训练数据导出未完成，请稍后重试。',
    HAND_LANDMARK_EXPORT_DATA_INVALID: '手部关键点导出内容校验未通过，请稍后重试。',
  }
  return new HandLandmarkExportApiError(messages[errorCode] ?? fallbackMessage, errorCode)
}
