export interface TrainingDataV3ExportPreview {
  schema_version: 'training-data.v3'
  eligible_group_count: number
  event_count: number
  file_name: 'training_data_v3.json'
  content_type: 'application/vnd.medication.training-data.v3+json'
  split_unit: 'capture_group'
  includes_mp4: false
}

export class TrainingDataExportApiError extends Error {
  readonly errorCode: string

  constructor(message: string, errorCode: string) {
    super(message)
    this.errorCode = errorCode
  }
}

export async function getTrainingDataV3ExportPreview(
  projectId: string,
  signal?: AbortSignal,
): Promise<TrainingDataV3ExportPreview> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/training-data/v3/preview`,
    {
      method: 'GET',
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as TrainingDataV3ExportPreview
  throw await trainingDataExportApiError(
    response,
    '暂时无法读取 training-data.v3 导出信息，请稍后重试。',
  )
}

export async function downloadTrainingDataV3(
  projectId: string,
  signal?: AbortSignal,
): Promise<Blob> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/training-data/v3/download`,
    {
      method: 'GET',
      headers: { Accept: 'application/vnd.medication.training-data.v3+json' },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return await response.blob()
  throw await trainingDataExportApiError(response, 'training-data.v3 暂时无法导出，请稍后重试。')
}

async function trainingDataExportApiError(
  response: Response,
  fallbackMessage: string,
): Promise<TrainingDataExportApiError> {
  let errorCode = 'TRAINING_DATA_EXPORT_REQUEST_FAILED'
  try {
    const body = (await response.json()) as { error_code?: unknown }
    if (typeof body.error_code === 'string') errorCode = body.error_code
  } catch {
    // Keep the stable fallback for proxy and non-JSON failures.
  }
  const messages: Record<string, string> = {
    PROJECT_ACCESS_DENIED: '当前身份没有项目训练数据导出的权限。',
    PROJECT_NOT_FOUND: '未找到该项目，或当前身份无权访问。',
    TRAINING_DATA_EXPORT_UNAVAILABLE: '当前环境暂未开放训练数据导出。',
    TRAINING_DATA_V3_EVIDENCE_INVALID: '当前配药事件导出证据不完整，暂时无法导出。',
    TRAINING_DATA_V3_READBACK_INVALID: '配药事件训练数据导出内容校验未通过，请稍后重试。',
    TRAINING_DATA_EXPORT_FAILED: '训练数据导出未完成，请稍后重试。',
    TRAINING_DATA_V3_EXPORT_FAILED: '配药事件训练数据导出未完成，请稍后重试。',
  }
  return new TrainingDataExportApiError(messages[errorCode] ?? fallbackMessage, errorCode)
}
