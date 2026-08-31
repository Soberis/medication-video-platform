import { describe, expect, it } from 'vitest'

import type {
  AnnotationRevisionSummary,
  AnnotationReview,
  CaptureGroupCleaning,
} from '@/api/annotation'
import type { CaptureGroupQualityReview } from '@/api/capture-groups'
import {
  deriveMedicationEventWorkflow,
  type MedicationEventViewQualityFact,
  type MedicationEventWorkflowFacts,
} from '@/features/capture-group/medication-event-workflow'

const groupId = 'group-workflow-test'

function qualityReview(result: CaptureGroupQualityReview['result']): CaptureGroupQualityReview {
  return {
    capture_group_quality_review_id: 'group-quality-review-1',
    project_id: 'project-1',
    capture_group_id: groupId,
    result,
    reason: null,
    reviewer_actor_id: 'reviewer-1',
    revision: 1,
    version: 1,
    decided_at: '2026-08-20T01:00:00Z',
    confirmed_view_set: null,
    input_version: null,
    confirmed_view_set_sha256: null,
  }
}

function viewQuality(
  decision: MedicationEventViewQualityFact['decision'] = 'QUALIFIED',
  overrides: Partial<MedicationEventViewQualityFact> = {},
): MedicationEventViewQualityFact {
  return {
    camera_number: 1,
    clip_asset_id: 'clip-1',
    present: true,
    visibility: 'loaded',
    decision,
    can_decide: false,
    ...overrides,
  }
}

function revision(
  review: AnnotationReview | null = null,
  cleaning: CaptureGroupCleaning | null = null,
): AnnotationRevisionSummary {
  return {
    annotation_revision_id: 'revision-1',
    label_schema_id: 'schema-1',
    label_schema_digest: 'schema-digest-1',
    revision_number: 1,
    annotation_document: {
      schema_version: 'annotation-document.v1',
      segments: [],
    },
    submitted_by: 'annotator-1',
    submitted_at: '2026-08-20T02:00:00Z',
    capture_group_id: groupId,
    primary_clip_asset_id: 'clip-1',
    primary_camera_number: 1,
    confirmed_view_set: null,
    input_version: 'input-1',
    review,
    post_cleaning_result: null,
    capture_group_cleaning: cleaning,
  }
}

function review(decision: AnnotationReview['decision']): AnnotationReview {
  return {
    annotation_review_id: 'annotation-review-1',
    project_id: 'project-1',
    annotation_revision_id: 'revision-1',
    decision,
    review_note: null,
    reviewed_by: 'reviewer-1',
    reviewed_at: '2026-08-20T03:00:00Z',
    capture_group_id: groupId,
    revision: 1,
    primary_clip_asset_id: 'clip-1',
    primary_camera_number: 1,
    confirmed_view_set: null,
    input_version: 'input-1',
  }
}

function facts(
  overrides: Partial<MedicationEventWorkflowFacts> = {},
): MedicationEventWorkflowFacts {
  return {
    receipt_state: 'COMPLETE',
    view_quality: [viewQuality()],
    group_quality_visibility: 'loaded',
    group_quality: qualityReview('QUALIFIED'),
    task_visibility: 'visible',
    task_status: null,
    primary_view: null,
    annotation_revision: null,
    review: null,
    cleaning: null,
    can_select_primary: false,
    can_submit: false,
    can_review: false,
    can_recheck_post_cleaning: false,
    can_finalize_receipt: false,
    can_decide_group_quality: false,
    annotation_schema_available: true,
    ...overrides,
  }
}

describe('medication event workflow presentation', () => {
  it('keeps the six product stages and exposes one receipt action for RECEIVING', () => {
    const workflow = deriveMedicationEventWorkflow(
      facts({ receipt_state: 'RECEIVING', can_finalize_receipt: true }),
    )

    expect(workflow.stage).toBe('RECEIPT')
    expect(workflow.steps.map((step) => step.stage)).toEqual([
      'RECEIPT',
      'VIEW_QUALITY',
      'GROUP_QUALITY',
      'ANNOTATION',
      'REVIEW',
      'TRAINING_ELIGIBILITY',
    ])
    expect(workflow.primaryAction).toEqual({
      key: 'finalize-receipt',
      label: '结束接收',
      target: 'receipt',
    })
  })

  it('stops at unresolved view quality and focuses the actionable camera', () => {
    const workflow = deriveMedicationEventWorkflow(
      facts({
        view_quality: [viewQuality('UNREVIEWED', { can_decide: true })],
      }),
    )

    expect(workflow.stage).toBe('VIEW_QUALITY')
    expect(workflow.primaryAction).toEqual({
      key: 'decide-view-quality',
      label: '判断当前机位质量',
      target: 'view-quality',
    })
    expect(workflow.focusedViewId).toBe('clip-1')
  })

  it('does not turn unavailable or forbidden quality facts into an actionable stage', () => {
    const unavailable = deriveMedicationEventWorkflow(
      facts({
        view_quality: [viewQuality('UNREVIEWED', { visibility: 'unavailable' })],
      }),
    )
    const forbidden = deriveMedicationEventWorkflow(
      facts({
        view_quality: [viewQuality('UNREVIEWED', { visibility: 'forbidden' })],
      }),
    )

    expect(unavailable.stage).toBe('VIEW_QUALITY')
    expect(unavailable.primaryAction).toBeNull()
    expect(unavailable.blocker).toContain('暂不可用')
    expect(forbidden.primaryAction).toBeNull()
    expect(forbidden.blocker).toBeNull()
    expect(forbidden.waitingReason).toContain('无权')

    const loading = deriveMedicationEventWorkflow(
      facts({
        view_quality: [viewQuality('UNREVIEWED', { visibility: 'loading' })],
      }),
    )
    expect(loading.blocker).toBeNull()
    expect(loading.waitingReason).toContain('正在读取')
  })

  it('continues with an available unresolved view when another view is unavailable', () => {
    const workflow = deriveMedicationEventWorkflow(
      facts({
        view_quality: [
          viewQuality('UNREVIEWED', { visibility: 'unavailable' }),
          viewQuality('UNREVIEWED', {
            camera_number: 2,
            clip_asset_id: 'clip-2',
            can_decide: true,
          }),
        ],
      }),
    )

    expect(workflow.primaryAction?.target).toBe('view-quality')
    expect(workflow.focusedViewId).toBe('clip-2')
    expect(workflow.blocker).toBeNull()
  })

  it('exposes the next unresolved view while its selected detail is still loading', () => {
    const workflow = deriveMedicationEventWorkflow(
      facts({
        view_quality: [
          viewQuality('QUALIFIED'),
          viewQuality('UNREVIEWED', {
            camera_number: 2,
            clip_asset_id: 'clip-2',
          }),
        ],
      }),
    )

    expect(workflow.primaryAction).toBeNull()
    expect(workflow.waitingReason).toContain('聚焦')
    expect(workflow.focusedViewId).toBe('clip-2')
  })

  it('requires an explicit group-quality capability before exposing its action', () => {
    const waiting = deriveMedicationEventWorkflow(
      facts({
        group_quality: qualityReview('UNREVIEWED'),
      }),
    )
    const actionable = deriveMedicationEventWorkflow(
      facts({
        group_quality: qualityReview('UNREVIEWED'),
        can_decide_group_quality: true,
      }),
    )

    expect(waiting.stage).toBe('GROUP_QUALITY')
    expect(waiting.primaryAction).toBeNull()
    expect(waiting.waitingReason).toContain('等待')
    expect(actionable.primaryAction?.key).toBe('decide-group-quality')
  })

  it('treats permission-limited task state as waiting rather than a destructive failure', () => {
    const workflow = deriveMedicationEventWorkflow(
      facts({
        task_visibility: 'permission-limited',
      }),
    )

    expect(workflow.primaryAction).toBeNull()
    expect(workflow.blocker).toBeNull()
    expect(workflow.waitingReason).toContain('无权')
  })

  it('maps assigned annotation and rejected-review rework to the same product stage', () => {
    const assigned = deriveMedicationEventWorkflow(
      facts({
        task_status: 'ASSIGNED',
        primary_view: { clip_asset_id: 'clip-1', camera_number: 1 },
        can_submit: true,
      }),
    )
    const rework = deriveMedicationEventWorkflow(
      facts({
        task_status: 'ASSIGNED',
        primary_view: { clip_asset_id: 'clip-1', camera_number: 1 },
        annotation_revision: revision(review('REJECTED')),
        review: review('REJECTED'),
        can_submit: true,
      }),
    )

    expect(assigned.stage).toBe('ANNOTATION')
    expect(assigned.primaryAction?.key).toBe('start-annotation')
    expect(rework.stage).toBe('ANNOTATION')
    expect(rework.primaryAction).toEqual({
      key: 'continue-annotation',
      label: '继续配药动作标注',
      target: 'annotation',
    })
  })

  it('does not expose annotation submission while the label schema is unavailable', () => {
    const waiting = deriveMedicationEventWorkflow(
      facts({
        task_status: 'ASSIGNED',
        primary_view: { clip_asset_id: 'clip-1', camera_number: 1 },
        can_submit: true,
        annotation_schema_available: false,
      }),
    )

    expect(waiting.stage).toBe('ANNOTATION')
    expect(waiting.primaryAction).toBeNull()
    expect(waiting.waitingReason).toContain('标注规范')
  })

  it('distinguishes a reviewer from a non-reviewer for SUBMITTED work', () => {
    const revisionFacts = revision()
    const reviewer = deriveMedicationEventWorkflow(
      facts({
        task_status: 'SUBMITTED',
        primary_view: { clip_asset_id: 'clip-1', camera_number: 1 },
        annotation_revision: revisionFacts,
        can_review: true,
      }),
    )
    const nonReviewer = deriveMedicationEventWorkflow(
      facts({
        task_status: 'SUBMITTED',
        primary_view: { clip_asset_id: 'clip-1', camera_number: 1 },
        annotation_revision: revisionFacts,
        can_review: false,
      }),
    )

    expect(reviewer.stage).toBe('REVIEW')
    expect(reviewer.primaryAction?.key).toBe('enter-review')
    expect(nonReviewer.stage).toBe('REVIEW')
    expect(nonReviewer.primaryAction).toBeNull()
    expect(nonReviewer.waitingReason).toContain('人工复核')
  })

  it('moves approved review to deterministic training eligibility without a manual cleaning action', () => {
    const approved = deriveMedicationEventWorkflow(
      facts({
        task_status: 'COMPLETED',
        primary_view: { clip_asset_id: 'clip-1', camera_number: 1 },
        annotation_revision: revision(review('APPROVED')),
        review: review('APPROVED'),
      }),
    )

    expect(approved.stage).toBe('TRAINING_ELIGIBILITY')
    expect(approved.primaryAction).toEqual({
      key: 'view-training-eligibility',
      label: '查看训练资格检查',
      target: 'training-eligibility',
    })
  })

  it('marks a completed PASS result as complete and keeps a result action', () => {
    const cleaning = {
      capture_group_cleaning_id: 'cleaning-1',
      outcome: 'PASS' as const,
    } as unknown as CaptureGroupCleaning
    const completed = deriveMedicationEventWorkflow(
      facts({
        task_status: 'COMPLETED',
        primary_view: { clip_asset_id: 'clip-1', camera_number: 1 },
        annotation_revision: revision(review('APPROVED'), cleaning),
        review: review('APPROVED'),
        cleaning,
      }),
    )

    expect(completed.stage).toBe('TRAINING_ELIGIBILITY')
    expect(completed.steps.at(-1)?.state).toBe('completed')
    expect(completed.primaryAction?.key).toBe('view-result')
  })

  it('does not let Candidate failure or NO_HAND_DETECTED block a human annotation path', () => {
    const base = facts({
      task_status: 'ASSIGNED',
      primary_view: { clip_asset_id: 'clip-1', camera_number: 1 },
      can_submit: true,
    })
    const candidateFailed = {
      ...base,
      candidate_status: 'FAILED',
    } as MedicationEventWorkflowFacts & { candidate_status: string }
    const noHandDetected = {
      ...base,
      candidate_status: 'NO_HAND_DETECTED',
    } as MedicationEventWorkflowFacts & { candidate_status: string }

    expect(deriveMedicationEventWorkflow(candidateFailed).primaryAction?.target).toBe('annotation')
    expect(deriveMedicationEventWorkflow(noHandDetected).primaryAction?.target).toBe('annotation')
  })

  it('never exposes more than one primary action', () => {
    const models = [
      deriveMedicationEventWorkflow(
        facts({ receipt_state: 'RECEIVING', can_finalize_receipt: true }),
      ),
      deriveMedicationEventWorkflow(
        facts({ view_quality: [viewQuality('UNREVIEWED', { can_decide: true })] }),
      ),
      deriveMedicationEventWorkflow(
        facts({ group_quality: qualityReview('UNREVIEWED'), can_decide_group_quality: true }),
      ),
    ]

    for (const model of models) {
      expect([model.primaryAction].filter(Boolean)).toHaveLength(1)
    }
  })
})
