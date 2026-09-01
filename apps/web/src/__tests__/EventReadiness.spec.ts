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
    hand_detection: { status: 'HAND_DETECTED', confidence: 0.93 },
    ...overrides,
  }
}

function facts(overrides: Partial<EventReadinessFacts> = {}): EventReadinessFacts {
  return {
    views: [view()],
    group_quality: 'QUALIFIED',
    task_status: 'ASSIGNED',
    primary_view_id: 'clip-1',
    annotation_revision_id: null,
    annotation_schema_available: true,
    can_submit: true,
    can_review: false,
    ...overrides,
  }
}

describe('event readiness presentation', () => {
  it('reports a normal single-view event as ready for annotation', () => {
    const readiness = deriveEventReadiness(facts())

    expect(readiness.mediaReady).toBe(true)
    expect(readiness.handDetection).toMatchObject({
      status: 'HAND_DETECTED',
      confidence: 0.93,
    })
    expect(readiness.annotationReady).toBe(true)
    expect(readiness.reviewReady).toBe(false)
    expect(readiness.blockers).toEqual([])
  })

  it('keeps a missing AI result informational when the human path is ready', () => {
    const readiness = deriveEventReadiness(
      facts({
        views: [view({ hand_detection: { status: 'UNKNOWN' } })],
      }),
    )

    expect(readiness.handDetection.status).toBe('UNKNOWN')
    expect(readiness.handDetection.confidence).toBeNull()
    expect(readiness.annotationReady).toBe(true)
    expect(readiness.blockers).toEqual([])
  })

  it('keeps failed AI detection separate from media and annotation readiness', () => {
    const readiness = deriveEventReadiness(
      facts({
        views: [view({ hand_detection: { status: 'FAILED' } })],
      }),
    )

    expect(readiness.handDetection.status).toBe('FAILED')
    expect(readiness.mediaReady).toBe(true)
    expect(readiness.annotationReady).toBe(true)
    expect(readiness.blockers).toEqual([])
  })

  it('preserves per-view facts for a multi-camera event without inventing group confidence', () => {
    const readiness = deriveEventReadiness(
      facts({
        group_quality: 'QUALIFIED_WITH_EXCLUSIONS',
        views: [
          view(),
          view({
            view_id: 'clip-2',
            camera_number: 2,
            hand_detection: { status: 'NO_HAND_DETECTED' },
          }),
        ],
      }),
    )

    expect(readiness.mediaReady).toBe(true)
    expect(readiness.handDetection.status).toBe('HAND_DETECTED')
    expect(readiness.handDetection.confidence).toBeNull()
    expect(readiness.handDetection.byView.map((item) => item.status)).toEqual([
      'HAND_DETECTED',
      'NO_HAND_DETECTED',
    ])
    expect(readiness.annotationReady).toBe(true)
    expect(readiness.blockers).toEqual([])
  })

  it('does not advertise an action for an already completed event', () => {
    const readiness = deriveEventReadiness(
      facts({
        task_status: 'COMPLETED',
        annotation_revision_id: 'revision-1',
        can_submit: false,
      }),
    )

    expect(readiness.annotationReady).toBe(false)
    expect(readiness.reviewReady).toBe(false)
    expect(readiness.blockers).toEqual([])
  })
})
