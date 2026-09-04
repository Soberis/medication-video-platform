export interface TrainingDataV3ExportPreview {
  schema_version: 'training-data.v3'
  eligible_group_count: number
  event_count: number
  file_name: 'training_data_v3.json'
  content_type: 'application/vnd.medication.training-data.v3+json'
  split_unit: 'capture_group'
  includes_mp4: false
}

export class TrainingDataV3ExportApiError extends Error {
  readonly errorCode: string

  constructor(message: string, errorCode: string) {
    super(message)
    this.name = 'TrainingDataV3ExportApiError'
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
  throw await trainingDataV3ExportApiError(
    response,
    '暂时无法读取 training-data.v3 导出资格，请稍后重试。',
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
  throw await trainingDataV3ExportApiError(response, 'training-data.v3 导出未完成，请稍后重试。')
}

async function trainingDataV3ExportApiError(
  response: Response,
  fallbackMessage: string,
): Promise<TrainingDataV3ExportApiError> {
  let errorCode = 'TRAINING_DATA_EXPORT_REQUEST_FAILED'
  try {
    const body = (await response.json()) as { error_code?: unknown }
    if (typeof body.error_code === 'string') errorCode = body.error_code
  } catch {
    // Keep the stable fallback for proxy and non-JSON failures.
  }
  const messages: Record<string, string> = {
    PROJECT_ACCESS_DENIED: '当前身份没有项目训练资格数据导出的权限。',
    PROJECT_NOT_FOUND: '未找到该项目，或当前身份无权访问。',
    TRAINING_DATA_EXPORT_UNAVAILABLE: '当前环境暂未开放 training-data.v3 导出。',
    TRAINING_DATA_V3_EVIDENCE_INVALID: '当前事件来源证据不完整，暂时无法导出。',
    TRAINING_DATA_V3_ELIGIBILITY_INVALID: '当前事件训练资格事实不完整，暂时无法导出。',
    TRAINING_DATA_V3_CANDIDATE_PROVENANCE_INVALID: '当前候选证据来源不完整，暂时无法导出。',
    TRAINING_DATA_V3_READBACK_INVALID: '导出内容校验未通过，请稍后重试。',
    TRAINING_DATA_V3_EXPORT_FAILED: 'training-data.v3 导出未完成，请稍后重试。',
  }
  return new TrainingDataV3ExportApiError(messages[errorCode] ?? fallbackMessage, errorCode)
}
