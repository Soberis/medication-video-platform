import type { QualityReviewDecision } from '@/api/quality'

export type EventReadinessHandDetectionStatus =
  | 'HAND_DETECTED'
  | 'NO_HAND_DETECTED'
  | 'UNKNOWN'
  | 'FAILED'

export type EventReadinessMediaStatus =
  | 'MISSING'
  | 'BOUND'
  | 'UPLOADED'
  | 'INGESTING'
  | 'INGESTED'
  | 'INGEST_FAILED'
  | 'UNAVAILABLE'

export interface EventReadinessViewFact {
  view_id: string | null
  camera_number: number
  present: boolean
  media_status: EventReadinessMediaStatus
  /** A human quality decision, when one has already been recorded. */
  quality_decision?: QualityReviewDecision
  hand_detection: {
    status: EventReadinessHandDetectionStatus
    confidence?: number | null
  }
}

export interface EventReadinessFacts {
  views: readonly EventReadinessViewFact[]
}

export interface EventReadinessViewSummary {
  view_id: string | null
  camera_number: number
}

export interface EventReadinessModel {
  /** True only when every declared view has ingested media; usableViews is the partial view set. */
  mediaReady: boolean
  handDetection: {
    /** A reliable group summary only exists for one present view; multi-view is UNKNOWN. */
    status: EventReadinessHandDetectionStatus
  }
  /** Views currently available to an existing human-label task, not AI-approved views. */
  usableViews: readonly EventReadinessViewSummary[]
  /** Views explicitly excluded by an existing human quality decision. */
  excludedViews: readonly EventReadinessViewSummary[]
  /** True when at least one present view still needs a human quality decision. */
  manualConfirmationRequired: boolean
  /** Media/receipt facts only; these are not a group workflow or export gate. */
  blockers: readonly EventReadinessBlocker[]
}

export type EventReadinessBlockerReason = 'NO_VIEWS' | 'VIEW_NOT_RECEIVED' | 'MEDIA_NOT_READY'

export interface EventReadinessBlocker {
  view_id: string | null
  camera_number: number | null
  reason: EventReadinessBlockerReason
}

function summarizeHandDetection(
  views: readonly EventReadinessViewFact[],
): EventReadinessHandDetectionStatus {
  const presentViews = views.filter((view) => view.present)
  return views.length === 1 && presentViews.length === 1
    ? (presentViews[0]?.hand_detection.status ?? 'UNKNOWN')
    : 'UNKNOWN'
}

/**
 * Summarizes receipt and media facts for a human-label task when one exists.
 * Machine evidence is informational only: it never creates a blocker, quality
 * conclusion, formal annotation, review, cleaning result, or export gate.
 */
export function deriveEventReadiness(facts: EventReadinessFacts): EventReadinessModel {
  const presentViews = facts.views.filter((view) => view.present)
  const readyViews = presentViews.filter((view) => view.media_status === 'INGESTED')
  const explicitlyExcludedViews = facts.views.filter(
    (view) => view.present && view.quality_decision === 'REJECTED',
  )
  const usableViews = readyViews
    .filter((view) => view.quality_decision !== 'REJECTED')
    .map(toViewSummary)
  const excludedViews = explicitlyExcludedViews.map(toViewSummary)
  const blockers: EventReadinessBlocker[] = []

  if (facts.views.length === 0) {
    blockers.push({ view_id: null, camera_number: null, reason: 'NO_VIEWS' })
  }
  for (const view of facts.views) {
    if (!view.present) {
      blockers.push({
        view_id: view.view_id,
        camera_number: view.camera_number,
        reason: 'VIEW_NOT_RECEIVED',
      })
    } else if (view.media_status !== 'INGESTED') {
      blockers.push({
        view_id: view.view_id,
        camera_number: view.camera_number,
        reason: 'MEDIA_NOT_READY',
      })
    }
  }

  return {
    mediaReady: facts.views.length > 0 && readyViews.length === facts.views.length,
    handDetection: { status: summarizeHandDetection(facts.views) },
    usableViews,
    excludedViews,
    manualConfirmationRequired: presentViews.some(
      (view) => view.quality_decision !== 'QUALIFIED' && view.quality_decision !== 'REJECTED',
    ),
    blockers,
  }
}

function toViewSummary(view: EventReadinessViewFact): EventReadinessViewSummary {
  return { view_id: view.view_id, camera_number: view.camera_number }
}
