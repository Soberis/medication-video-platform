export interface TrainingDataExportPreview {
  schema_version: 'training-data.v2'
  eligible_video_count: number
  candidate_observation_count: number
  training_row_count: number
  unlabeled_candidate_observation_count: number
  training_row_definition: string
  file_name: 'training_data.parquet'
  content_type: 'application/vnd.apache.parquet'
  includes_mp4: false
}

export class TrainingDataExportApiError extends Error {
  readonly errorCode: string

  constructor(message: string, errorCode: string) {
    super(message)
    this.errorCode = errorCode
  }
}

export async function getTrainingDataExportPreview(
  projectId: string,
  signal?: AbortSignal,
): Promise<TrainingDataExportPreview> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/training-data/preview`,
    {
      method: 'GET',
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as TrainingDataExportPreview
  throw await trainingDataExportApiError(response, '暂时无法读取训练数据导出信息，请稍后重试。')
}

export async function downloadTrainingData(projectId: string, signal?: AbortSignal): Promise<Blob> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/training-data/download`,
    {
      method: 'GET',
      headers: { Accept: 'application/vnd.apache.parquet' },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return await response.blob()
  throw await trainingDataExportApiError(response, '训练数据暂未导出，请稍后重试。')
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
    TRAINING_DATA_EXPORT_EVIDENCE_INVALID: '当前可用数据证据不完整，暂时无法导出。',
    TRAINING_DATA_EXPORT_FAILED: '训练数据导出未完成，请稍后重试。',
  }
  return new TrainingDataExportApiError(messages[errorCode] ?? fallbackMessage, errorCode)
}
