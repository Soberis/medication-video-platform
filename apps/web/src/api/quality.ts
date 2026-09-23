export type QualityReviewDecision = 'UNREVIEWED' | 'QUALIFIED' | 'REJECTED' | 'NEEDS_REVIEW'
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
