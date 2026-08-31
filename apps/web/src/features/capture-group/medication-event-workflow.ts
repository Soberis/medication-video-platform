import type {
  AnnotationReview,
  AnnotationRevisionSummary,
  AnnotationTask,
  CaptureGroupCleaning,
  PostCleaningResult,
} from '@/api/annotation'
import type { CaptureGroupQualityReview, CaptureGroupReceiptState } from '@/api/capture-groups'
import type { QualityReviewDecision } from '@/api/quality'
import type {
  CaptureGroupQualityVisibility,
  CaptureGroupTaskVisibility,
} from '@/features/capture-group-presentation'

export type MedicationEventWorkflowStage =
  | 'RECEIPT'
  | 'VIEW_QUALITY'
  | 'GROUP_QUALITY'
  | 'ANNOTATION'
  | 'REVIEW'
  | 'TRAINING_ELIGIBILITY'

export const medicationEventWorkflowStageLabels: Record<MedicationEventWorkflowStage, string> = {
  RECEIPT: '接收',
  VIEW_QUALITY: '机位质量',
  GROUP_QUALITY: '事件质量',
  ANNOTATION: '配药动作标注',
  REVIEW: '人工复核',
  TRAINING_ELIGIBILITY: '训练资格检查',
}

const medicationEventWorkflowStages: readonly MedicationEventWorkflowStage[] = [
  'RECEIPT',
  'VIEW_QUALITY',
  'GROUP_QUALITY',
  'ANNOTATION',
  'REVIEW',
  'TRAINING_ELIGIBILITY',
]

export type MedicationEventFactVisibility = 'loading' | 'loaded' | 'unavailable' | 'forbidden'

export interface MedicationEventViewQualityFact {
  camera_number: number
  clip_asset_id: string | null
  present: boolean
  visibility: MedicationEventFactVisibility
  decision: QualityReviewDecision
  can_decide: boolean
  blocker?: string
  waiting_reason?: string
}

export interface MedicationEventPrimaryViewFact {
  clip_asset_id: string | null
  camera_number: number | null
}

export type MedicationEventCleaningFact =
  | Pick<CaptureGroupCleaning, 'outcome'>
  | Pick<PostCleaningResult, 'outcome'>

export interface MedicationEventWorkflowFacts {
  receipt_state: CaptureGroupReceiptState
  view_quality: readonly MedicationEventViewQualityFact[]
  group_quality_visibility: CaptureGroupQualityVisibility
  group_quality: CaptureGroupQualityReview | null
  task_visibility: CaptureGroupTaskVisibility
  task_status: AnnotationTask['status'] | null
  primary_view: MedicationEventPrimaryViewFact | null
  annotation_revision: AnnotationRevisionSummary | null
  review: AnnotationReview | null
  cleaning: MedicationEventCleaningFact | null
  can_select_primary: boolean
  can_submit: boolean
  can_review: boolean
  can_recheck_post_cleaning: boolean
  can_finalize_receipt: boolean
  can_decide_group_quality: boolean
  annotation_schema_available: boolean
}

export type MedicationEventWorkflowStepState =
  | 'completed'
  | 'current'
  | 'waiting'
  | 'blocked'
  | 'upcoming'

export interface MedicationEventWorkflowStep {
  stage: MedicationEventWorkflowStage
  label: string
  state: MedicationEventWorkflowStepState
}

export type MedicationEventWorkflowActionKey =
  | 'finalize-receipt'
  | 'decide-view-quality'
  | 'decide-group-quality'
  | 'select-primary-view'
  | 'start-annotation'
  | 'continue-annotation'
  | 'enter-review'
  | 'view-training-eligibility'
  | 'view-result'

export type MedicationEventWorkflowTarget =
  | 'receipt'
  | 'view-quality'
  | 'group-quality'
  | 'annotation'
  | 'review'
  | 'training-eligibility'

export interface MedicationEventWorkflowPrimaryAction {
  key: MedicationEventWorkflowActionKey
  label: string
  target: MedicationEventWorkflowTarget
}

export interface MedicationEventWorkflowModel {
  stage: MedicationEventWorkflowStage
  steps: MedicationEventWorkflowStep[]
  primaryAction: MedicationEventWorkflowPrimaryAction | null
  blocker: string | null
  waitingReason: string | null
  focusedViewId: string | null
}

interface WorkflowBuildOptions {
  currentState?: MedicationEventWorkflowStepState
  primaryAction?: MedicationEventWorkflowPrimaryAction | null
  blocker?: string | null
  waitingReason?: string | null
  focusedViewId?: string | null
}

function action(
  key: MedicationEventWorkflowActionKey,
  label: string,
  target: MedicationEventWorkflowTarget,
): MedicationEventWorkflowPrimaryAction {
  return { key, label, target }
}

function buildWorkflow(
  stage: MedicationEventWorkflowStage,
  options: WorkflowBuildOptions = {},
): MedicationEventWorkflowModel {
  const stageIndex = medicationEventWorkflowStages.indexOf(stage)
  const primaryAction = options.primaryAction ?? null
  const blocker = options.blocker ?? null
  const waitingReason = options.waitingReason ?? null
  const currentState =
    options.currentState ?? (blocker ? 'blocked' : waitingReason ? 'waiting' : 'current')

  return {
    stage,
    steps: medicationEventWorkflowStages.map((candidate, index) => ({
      stage: candidate,
      label: medicationEventWorkflowStageLabels[candidate],
      state: index < stageIndex ? 'completed' : index > stageIndex ? 'upcoming' : currentState,
    })),
    primaryAction,
    blocker,
    waitingReason,
    focusedViewId: options.focusedViewId ?? null,
  }
}

function isResolvedViewDecision(decision: QualityReviewDecision) {
  return decision === 'QUALIFIED' || decision === 'REJECTED'
}

function groupQualityResultLabel(result: NonNullable<CaptureGroupQualityReview>['result']) {
  if (result === 'QUALIFIED') return '合格'
  if (result === 'QUALIFIED_WITH_EXCLUSIONS') return '合格（排除部分机位）'
  if (result === 'NEEDS_REVIEW') return '需复核'
  if (result === 'REJECTED') return '不合格'
  return '待判断'
}

function deriveViewQualityWorkflow(
  facts: MedicationEventWorkflowFacts,
): MedicationEventWorkflowModel | null {
  const presentViews = facts.view_quality.filter((view) => view.present)
  const unresolvedViews = presentViews.filter((view) => !isResolvedViewDecision(view.decision))
  if (!unresolvedViews.length) return null

  const actionableView = unresolvedViews.find(
    (view) => view.visibility === 'loaded' && view.can_decide,
  )
  if (actionableView?.clip_asset_id) {
    return buildWorkflow('VIEW_QUALITY', {
      primaryAction: action('decide-view-quality', '判断当前机位质量', 'view-quality'),
      focusedViewId: actionableView.clip_asset_id,
    })
  }

  // A local read or media failure must not stop another available view from
  // being judged. Keep the next available unresolved view focused while its
  // detail context loads; surface the local blocker only when no other view
  // can make progress.
  const focusableView = unresolvedViews.find(
    (view) => view.visibility === 'loaded' && Boolean(view.clip_asset_id) && !view.blocker,
  )
  if (focusableView?.clip_asset_id) {
    return buildWorkflow('VIEW_QUALITY', {
      waitingReason: '正在聚焦下一路未决机位，等待其媒体事实加载。',
      focusedViewId: focusableView.clip_asset_id,
    })
  }

  const loadingView = unresolvedViews.find((view) => view.visibility === 'loading')
  if (loadingView) {
    return buildWorkflow('VIEW_QUALITY', {
      waitingReason: '机位质量正在读取；状态暂不可确认，请稍候刷新。',
    })
  }

  const malformedView = unresolvedViews.find((view) => !view.clip_asset_id)
  if (malformedView) {
    return buildWorkflow('VIEW_QUALITY', {
      blocker: `机位 ${malformedView.camera_number} 缺少已绑定视频，暂不能判断机位质量。`,
    })
  }

  const blockedView = unresolvedViews.find((view) => view.blocker)
  if (blockedView?.blocker) {
    return buildWorkflow('VIEW_QUALITY', { blocker: blockedView.blocker })
  }

  const forbiddenView = unresolvedViews.find((view) => view.visibility === 'forbidden')
  if (forbiddenView) {
    return buildWorkflow('VIEW_QUALITY', {
      waitingReason: `当前账号无权读取机位 ${forbiddenView.camera_number} 的机位质量；等待有权限的人员处理。`,
    })
  }

  const unavailableView = unresolvedViews.find((view) => view.visibility === 'unavailable')
  if (unavailableView) {
    return buildWorkflow('VIEW_QUALITY', {
      blocker: `机位 ${unavailableView.camera_number} 的机位质量暂不可用，请刷新后重试。`,
    })
  }

  const waitingView = unresolvedViews.find((view) => view.waiting_reason)
  return buildWorkflow('VIEW_QUALITY', {
    waitingReason: waitingView?.waiting_reason ?? '等待有权限的人员判断当前机位质量。',
  })
}

function deriveGroupQualityWorkflow(
  facts: MedicationEventWorkflowFacts,
): MedicationEventWorkflowModel | null {
  if (facts.group_quality_visibility === 'forbidden') {
    return buildWorkflow('GROUP_QUALITY', {
      waitingReason: '当前账号无权读取事件质量；等待有权限的人员处理。',
    })
  }
  if (facts.group_quality_visibility === 'unavailable') {
    return buildWorkflow('GROUP_QUALITY', {
      blocker: '事件质量暂不可用，请刷新后重试。',
    })
  }
  if (facts.group_quality_visibility === 'loading') {
    return buildWorkflow('GROUP_QUALITY', {
      waitingReason: '事件质量正在读取；状态暂不可确认，请稍候刷新。',
    })
  }
  if (!facts.group_quality) {
    return buildWorkflow('GROUP_QUALITY', {
      blocker: '事件质量状态暂不可确认，请刷新后重试。',
    })
  }

  // A submitted revision still has an explicit server-authorized review path.
  // Keep that action visible when the quality read is already downgraded; the
  // review command remains the server's concurrency/qualification guard.
  if (facts.task_status === 'SUBMITTED' && facts.annotation_revision && facts.can_review) {
    return null
  }

  if (facts.group_quality.result === 'UNREVIEWED') {
    return facts.can_decide_group_quality
      ? buildWorkflow('GROUP_QUALITY', {
          primaryAction: action('decide-group-quality', '确认事件质量', 'group-quality'),
        })
      : buildWorkflow('GROUP_QUALITY', {
          waitingReason: '等待有权限的人员确认事件质量。',
        })
  }

  if (
    facts.group_quality.result !== 'QUALIFIED' &&
    facts.group_quality.result !== 'QUALIFIED_WITH_EXCLUSIONS'
  ) {
    return facts.can_decide_group_quality
      ? buildWorkflow('GROUP_QUALITY', {
          primaryAction: action('decide-group-quality', '重新确认事件质量', 'group-quality'),
        })
      : buildWorkflow('GROUP_QUALITY', {
          waitingReason: `当前事件质量为“${groupQualityResultLabel(facts.group_quality.result)}”，等待有权限的人员重新确认。`,
        })
  }

  return null
}

function deriveAnnotationWorkflow(
  facts: MedicationEventWorkflowFacts,
): MedicationEventWorkflowModel {
  if (facts.task_visibility === 'unavailable') {
    return buildWorkflow('ANNOTATION', {
      blocker: '配药动作标注任务状态暂不可用，请刷新后重试。',
    })
  }
  if (facts.task_visibility === 'permission-limited') {
    return buildWorkflow('ANNOTATION', {
      waitingReason: '当前账号无权查看配药动作标注任务状态；等待有权限的人员处理。',
    })
  }
  if (!facts.task_status) {
    return buildWorkflow('ANNOTATION', {
      waitingReason: '事件质量已确认，等待系统创建唯一配药动作标注任务。',
    })
  }

  const hasPrimaryView = Boolean(facts.primary_view?.clip_asset_id)
  if (facts.task_status === 'ASSIGNED') {
    if (!hasPrimaryView) {
      return facts.can_select_primary
        ? buildWorkflow('ANNOTATION', {
            primaryAction: action('select-primary-view', '选择主视图', 'annotation'),
          })
        : buildWorkflow('ANNOTATION', {
            waitingReason: '等待任务执行人选择主视图。',
          })
    }
    if (!facts.can_submit) {
      return buildWorkflow('ANNOTATION', {
        waitingReason: '等待任务执行人处理当前配药动作标注。',
      })
    }
    if (!facts.annotation_schema_available) {
      return buildWorkflow('ANNOTATION', {
        waitingReason: '标注规范暂时无法读取，当前不能提交新的修订。',
      })
    }
    return buildWorkflow('ANNOTATION', {
      primaryAction: action(
        facts.review?.decision === 'REJECTED' ? 'continue-annotation' : 'start-annotation',
        facts.review?.decision === 'REJECTED' ? '继续配药动作标注' : '进入配药动作标注',
        'annotation',
      ),
    })
  }

  if (facts.task_status === 'SUBMITTED') {
    if (!facts.annotation_revision) {
      return buildWorkflow('REVIEW', {
        blocker: '已提交任务缺少正式修订事实；状态暂不可确认，请刷新后重试。',
      })
    }
    return facts.can_review
      ? buildWorkflow('REVIEW', {
          primaryAction: action('enter-review', '进入人工复核', 'review'),
        })
      : buildWorkflow('REVIEW', {
          waitingReason: '当前账号没有该修订的复核权限；等待授权的人工复核。',
        })
  }

  if (!facts.annotation_revision) {
    return buildWorkflow('ANNOTATION', {
      blocker: '已完成任务缺少正式修订事实；状态暂不可确认，请刷新后重试。',
    })
  }

  const review = facts.review ?? facts.annotation_revision.review
  if (!review) {
    return buildWorkflow('REVIEW', {
      waitingReason: '等待人工复核。',
    })
  }
  if (review.decision !== 'APPROVED') {
    return buildWorkflow('REVIEW', {
      blocker: '当前任务缺少已通过的人工复核，不能进入训练资格检查。',
    })
  }

  const cleaning =
    facts.cleaning ??
    facts.annotation_revision.capture_group_cleaning ??
    facts.annotation_revision.post_cleaning_result
  if (cleaning?.outcome === 'PASS') {
    return buildWorkflow('TRAINING_ELIGIBILITY', {
      primaryAction: action('view-result', '查看结果', 'training-eligibility'),
      currentState: 'completed',
    })
  }
  return buildWorkflow('TRAINING_ELIGIBILITY', {
    primaryAction: action('view-training-eligibility', '查看训练资格检查', 'training-eligibility'),
  })
}

/**
 * Derives the user-facing current workflow from one complete server fact set.
 * This is intentionally presentation-only: it does not grant permissions,
 * persist state, call APIs, or replace a domain workflow state machine.
 */
export function deriveMedicationEventWorkflow(
  facts: MedicationEventWorkflowFacts,
): MedicationEventWorkflowModel {
  if (facts.receipt_state === 'RECEIVING') {
    return facts.can_finalize_receipt
      ? buildWorkflow('RECEIPT', {
          primaryAction: action('finalize-receipt', '结束接收', 'receipt'),
        })
      : buildWorkflow('RECEIPT', {
          waitingReason: '当前账号或会话不支持结束接收，等待有权限的操作人处理。',
        })
  }

  const viewQualityWorkflow = deriveViewQualityWorkflow(facts)
  if (viewQualityWorkflow) return viewQualityWorkflow

  const groupQualityWorkflow = deriveGroupQualityWorkflow(facts)
  if (groupQualityWorkflow) return groupQualityWorkflow

  return deriveAnnotationWorkflow(facts)
}
