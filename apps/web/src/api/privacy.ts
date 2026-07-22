import { csrfHeaders } from '@/api/http'

export type PrivacyRiskLevel = 'UNKNOWN' | 'NONE' | 'SUSPECTED' | 'CONFIRMED'
export type PrivacyRequiredAction =
  | 'RELEASE_REVIEW_REQUIRED'
  | 'DEIDENTIFICATION_REQUIRED'
  | 'QUARANTINE_REQUIRED'
export type PrivacyFindingType =
  | 'FACE'
  | 'NAME'
  | 'MEDICAL_RECORD_ID'
  | 'SCREEN'
  | 'BADGE'
  | 'ORDER_TEXT'
  | 'LABEL_TEXT'
  | 'VOICE'
  | 'OTHER_IDENTIFIABLE_INFORMATION'

export interface PrivacyAssessment {
  privacy_assessment_id: string
  project_id: string
  clip_asset_id: string
  input_digest: string
  assessment_version: number
  assessment_method: 'HUMAN_REVIEW'
  policy_key: 'privacy-assessment-policy-v1'
  policy_digest: string
  risk_level: Exclude<PrivacyRiskLevel, 'UNKNOWN'>
  required_action: PrivacyRequiredAction
  privacy_release_status: 'BLOCKED'
  finding_types: PrivacyFindingType[]
  reason_code:
    | 'NO_IDENTIFIABLE_INFORMATION_OBSERVED'
    | 'IDENTIFIABLE_INFORMATION_OBSERVED'
    | 'RISK_REQUIRES_ISOLATION'
  assessed_by: string
  supersedes_assessment_id: string | null
  assessed_at: string
}

export interface PrivacyStatus {
  clip_asset_id: string
  input_digest: string
  risk_level: PrivacyRiskLevel
  required_action: PrivacyRequiredAction | null
  privacy_release_status: 'BLOCKED'
  current_assessment: PrivacyAssessment | null
}

export interface PrivacyAssessmentCommand {
  clip_asset_id: string
  supersedes_assessment_id: string | null
  risk_level: Exclude<PrivacyRiskLevel, 'UNKNOWN'>
  required_action: PrivacyRequiredAction
  reason_code: PrivacyAssessment['reason_code']
  finding_types: PrivacyFindingType[]
}

interface ApiErrorBody {
  error_code?: unknown
  error_message?: unknown
  retryable?: unknown
  request_id?: unknown
}

const errorMessages: Record<string, string> = {
  PROJECT_NOT_FOUND: '未找到该项目，或当前账号尚未加入该项目。',
  PROJECT_ACCESS_DENIED: '需要平台管理员和项目管理员权限才能进行隐私评估。',
  CLIP_ASSET_NOT_FOUND: '未找到该项目中的视频资产。',
  CLIP_ASSET_NOT_ASSESSABLE: '视频尚未完成媒体检测，暂不能进行隐私评估。',
  PRIVACY_ASSESSMENT_SUPERSESSION_REQUIRED: '已有当前评估，重新评估必须明确替代该版本。',
  PRIVACY_ASSESSMENT_STALE: '当前评估已变化，请刷新后重新提交。',
  IDEMPOTENCY_KEY_REUSED: '本次评估请求发生冲突，请刷新后重试。',
  INVALID_PRIVACY_ASSESSMENT_DECISION: '风险等级、处理动作和发现项不一致。',
}

export class PrivacyApiError extends Error {
  readonly errorCode: string
  readonly retryable: boolean
  readonly requestId: string | null

  constructor(message: string, errorCode: string, retryable: boolean, requestId: string | null) {
    super(message)
    this.name = 'PrivacyApiError'
    this.errorCode = errorCode
    this.retryable = retryable
    this.requestId = requestId
  }
}

export async function getPrivacyStatus(projectId: string, clipAssetId: string, signal?: AbortSignal) {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}/privacy-status`,
    { method: 'GET', headers: { Accept: 'application/json' }, cache: 'no-store', signal },
  )
  if (response.ok) return (await response.json()) as PrivacyStatus
  throw await toPrivacyError(response)
}

export async function createPrivacyAssessment(projectId: string, command: PrivacyAssessmentCommand) {
  const response = await fetch(`/api/v1/projects/${encodeURIComponent(projectId)}/privacy-assessments`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
      ...csrfHeaders(),
    },
    body: JSON.stringify(command),
    cache: 'no-store',
  })
  if (response.ok) return (await response.json()) as PrivacyAssessment
  throw await toPrivacyError(response)
}

async function toPrivacyError(response: Response) {
  let body: ApiErrorBody = {}
  try {
    body = (await response.json()) as ApiErrorBody
  } catch {
    // Use the safe fallback for proxy and non-JSON failures.
  }
  const errorCode = typeof body.error_code === 'string' ? body.error_code : 'PRIVACY_REQUEST_FAILED'
  throw new PrivacyApiError(
    errorMessages[errorCode] ?? '暂时无法读取或保存隐私评估，请稍后重试。',
    errorCode,
    body.retryable === true,
    typeof body.request_id === 'string' ? body.request_id : null,
  )
}
