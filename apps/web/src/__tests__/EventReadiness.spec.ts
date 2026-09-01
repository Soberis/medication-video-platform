import { describe, expect, it } from 'vitest'

import {
  deriveEventReadiness,
  type EventReadinessFacts,
  type EventReadinessViewFact,
} from '@/features/capture-group/medication-event-workflow'

function view(overrides: Partial<EventReadinessViewFact> = {}): EventReadinessViewFact {
  return {
    view_id: 'clip-1',
    camera_number: 1,
    present: true,
    media_status: 'INGESTED',
    quality_decision: 'QUALIFIED',
    hand_detection: { status: 'HAND_DETECTED', confidence: 0.93 },
    ...overrides,
  }
}

function facts(overrides: Partial<EventReadinessFacts> = {}): EventReadinessFacts {
  return {
    views: [view()],
    ...overrides,
  }
}

describe('event readiness presentation', () => {
  it('reports a normal single-view event as media-ready and usable', () => {
    const readiness = deriveEventReadiness(facts())

    expect(readiness).toEqual({
      mediaReady: true,
      handDetection: {
        status: 'HAND_DETECTED',
      },
      usableViews: [{ view_id: 'clip-1', camera_number: 1 }],
      excludedViews: [],
      manualConfirmationRequired: false,
      blockers: [],
    })
  })

  it('keeps NO_HAND_DETECTED usable but requires human confirmation', () => {
    const readiness = deriveEventReadiness(
      facts({
        views: [
          view({ hand_detection: { status: 'NO_HAND_DETECTED' }, quality_decision: 'UNREVIEWED' }),
        ],
      }),
    )

    expect(readiness.handDetection).toEqual({
      status: 'NO_HAND_DETECTED',
    })
    expect(readiness.usableViews).toEqual([{ view_id: 'clip-1', camera_number: 1 }])
    expect(readiness.excludedViews).toEqual([])
    expect(readiness.manualConfirmationRequired).toBe(true)
    expect(readiness.blockers).toEqual([])
  })

  it('keeps FAILED distinct from no-hand and does not block a ready view', () => {
    const readiness = deriveEventReadiness(
      facts({
        views: [view({ hand_detection: { status: 'FAILED' } })],
      }),
    )

    expect(readiness.handDetection).toEqual({
      status: 'FAILED',
    })
    expect(readiness.mediaReady).toBe(true)
    expect(readiness.usableViews).toEqual([{ view_id: 'clip-1', camera_number: 1 }])
    expect(readiness.excludedViews).toEqual([])
    expect(readiness.manualConfirmationRequired).toBe(false)
    expect(readiness.blockers).toEqual([])
  })

  it('keeps mixed multi-camera evidence per view and only honors explicit human exclusion', () => {
    const readiness = deriveEventReadiness(
      facts({
        views: [
          view(),
          view({
            view_id: 'clip-2',
            camera_number: 2,
            hand_detection: { status: 'NO_HAND_DETECTED' },
            quality_decision: 'REJECTED',
          }),
          view({
            view_id: 'clip-3',
            camera_number: 3,
            hand_detection: { status: 'FAILED' },
            quality_decision: 'QUALIFIED',
          }),
        ],
      }),
    )

    expect(readiness.mediaReady).toBe(true)
    expect(readiness.handDetection).toEqual({
      status: 'UNKNOWN',
    })
    expect(readiness.usableViews).toEqual([
      { view_id: 'clip-1', camera_number: 1 },
      { view_id: 'clip-3', camera_number: 3 },
    ])
    expect(readiness.excludedViews).toEqual([{ view_id: 'clip-2', camera_number: 2 }])
    expect(readiness.manualConfirmationRequired).toBe(false)
    expect(readiness.blockers).toEqual([])
  })

  it('does not add readiness blockers for a completed event', () => {
    const readiness = deriveEventReadiness(
      facts({
        views: [view({ quality_decision: 'QUALIFIED' })],
      }),
    )

    expect(readiness.usableViews).toEqual([{ view_id: 'clip-1', camera_number: 1 }])
    expect(readiness.excludedViews).toEqual([])
    expect(readiness.manualConfirmationRequired).toBe(false)
    expect(readiness.blockers).toEqual([])
  })

  it('keeps a missing or unready view local to that view', () => {
    const readiness = deriveEventReadiness(
      facts({
        views: [
          view(),
          view({
            view_id: 'clip-2',
            camera_number: 2,
            present: false,
            media_status: 'MISSING',
          }),
        ],
      }),
    )

    expect(readiness.mediaReady).toBe(false)
    expect(readiness.usableViews).toEqual([{ view_id: 'clip-1', camera_number: 1 }])
    expect(readiness.blockers).toEqual([
      { view_id: 'clip-2', camera_number: 2, reason: 'VIEW_NOT_RECEIVED' },
    ])
  })
})
