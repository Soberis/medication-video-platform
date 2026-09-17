import { csrfHeaders } from '@/api/http'

export type QualityReviewDecision = 'UNREVIEWED' | 'QUALIFIED' | 'REJECTED' | 'NEEDS_REVIEW'
export type QualityReviewCommandDecision = Exclude<QualityReviewDecision, 'UNREVIEWED'>
export type QualityReviewReasonCode =
  | 'MEETS_REQUIREMENTS'
  | 'SENSITIVE_CONTENT'
  | 'BLURRY_OR_OUT_OF_FOCUS'
  | 'BLACK_OR_CORRUPTED_FRAMES'
  | 'SEVERE_OCCLUSION'
  | 'INCOMPLETE_ACTION'
  | 'WRONG_SCENE'
  | 'DUPLICATE'
  | 'OTHER_QUALITY_FAILURE'
  | 'UNCERTAIN_QUALITY'
  | 'METADATA_MISMATCH'

export interface QualityReview {
  quality_review_id: string | null
  project_id: string
  clip_asset_id: string
  decision: QualityReviewDecision
  reason_code: QualityReviewReasonCode | null
  reviewed_by: string | null
  reviewed_at: string | null
  version: number
}

export interface QualityReviewCommand {
  decision: QualityReviewCommandDecision
  reason_code: QualityReviewReasonCode
  expected_version: number
}

export class QualityReviewApiError extends Error {
  readonly errorCode: string

  constructor(message: string, errorCode: string) {
    super(message)
    this.name = 'QualityReviewApiError'
    this.errorCode = errorCode
  }
}

export async function getQualityReview(
  projectId: string,
  clipAssetId: string,
  signal?: AbortSignal,
) {
  const response = await fetch(path(projectId, clipAssetId), {
    method: 'GET',
    headers: { Accept: 'application/json' },
    cache: 'no-store',
    signal,
  })
  return readResponse(response)
}

export async function recordQualityReview(
  projectId: string,
  clipAssetId: string,
  command: QualityReviewCommand,
) {
  const response = await fetch(path(projectId, clipAssetId), {
    method: 'PUT',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'X-Request-Id': crypto.randomUUID(),
      ...csrfHeaders(),
    },
    body: JSON.stringify(command),
    cache: 'no-store',
  })
  return readResponse(response)
}

async function readResponse(response: Response): Promise<QualityReview> {
  if (response.ok) return (await response.json()) as QualityReview
  let errorCode = 'QUALITY_REVIEW_FAILED'
  try {
    const body = (await response.json()) as { error_code?: unknown }
    if (typeof body.error_code === 'string') errorCode = body.error_code
  } catch {
    // Use the stable fallback for proxy or non-JSON failures.
  }
  const messages: Record<string, string> = {
    CLIP_ASSET_NOT_FOUND: '未找到该视频，或它已被删除。',
    PROJECT_NOT_FOUND: '未找到该项目，或当前账号尚未加入。',
    QUALITY_REVIEW_NOT_READY: '视频完成媒体检测后才能记录质量结论。',
    INVALID_QUALITY_REASON: '所选原因与质量结论不匹配。',
    INVALID_QUALITY_REVIEW: '质量判断内容不完整，请重新选择。',
    QUALITY_REVIEW_CONFLICT: '质量结论已被其他项目成员更新，请刷新后重试。',
  }
  throw new QualityReviewApiError(
    messages[errorCode] ?? '质量结论暂未保存，请稍后重试。',
    errorCode,
  )
}

function path(projectId: string, clipAssetId: string) {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}/quality-review`
}
