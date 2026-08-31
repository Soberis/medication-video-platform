import type {
  CaptureGroup,
  CaptureGroupQualityResult,
  CaptureGroupQualityReview,
  CaptureGroupQualityView,
  CaptureGroupStream,
} from '@/api/capture-groups'
import type { AnnotationTask } from '@/api/annotation'
import type { CaptureGroupFocus } from '@/app/paths'
import type { StatusIndicatorTone } from '@/components/status/StatusIndicator'

export const receiptStateLabels: Record<CaptureGroup['receipt_state'], string> = {
  RECEIVING: '接收中',
  COMPLETE: '已完整接收',
  INCOMPLETE: '接收完成 · 有缺失',
}

export const groupQualityLabels: Record<CaptureGroupQualityResult, string> = {
  UNREVIEWED: '待判断',
  QUALIFIED: '合格',
  QUALIFIED_WITH_EXCLUSIONS: '合格（排除部分机位）',
  NEEDS_REVIEW: '需复核',
  REJECTED: '不合格',
}

export type CaptureGroupQualityVisibility = 'loading' | 'loaded' | 'unavailable' | 'forbidden'

export function groupQualityDisplayLabel(
  visibility: CaptureGroupQualityVisibility,
  quality: CaptureGroupQualityReview | null,
) {
  if (visibility === 'loading') return '读取中'
  if (visibility === 'forbidden') return '无权查看'
  if (visibility === 'unavailable' || !quality) return '暂不可用'
  return groupQualityLabels[quality.result]
}

export function groupQualityDisplayTone(
  visibility: CaptureGroupQualityVisibility,
  quality: CaptureGroupQualityReview | null,
): StatusIndicatorTone {
  return visibility === 'loaded' && quality ? groupQualityTone(quality.result) : 'neutral'
}

export const viewQualityLabels: Record<CaptureGroupQualityView['quality_review_decision'], string> =
  {
    UNREVIEWED: '待判断',
    QUALIFIED: '合格',
    NEEDS_REVIEW: '需复核',
    REJECTED: '不合格',
  }

export const mediaStatusLabels: Record<string, string> = {
  MISSING: '缺失',
  BOUND: '已绑定 · 待媒体入库',
  INGESTED: '媒体已入库',
  INGEST_FAILED: '媒体入库失败',
  UNAVAILABLE: '媒体不可用',
  UPLOADED: '待媒体检测',
  INGESTING: '媒体检测中',
}

export const technicalQualityLabels: Record<string, string> = {
  PENDING: '技术检测中',
  PASS: '技术检测通过',
  WARNING: '技术检测需关注',
  REJECT: '技术检测未通过',
}

const syncReliabilityLabels: Record<string, string> = {
  PASS: '可靠声明',
  RELIABLE: '可靠声明',
  WARNING: '近似声明',
  APPROXIMATE: '近似声明',
  REJECT: '不可靠声明',
  UNRELIABLE: '不可靠声明',
  UNKNOWN: '未知声明',
}

export function syncReliabilityDisplayLabel(value: string) {
  return syncReliabilityLabels[value] ?? '未识别声明'
}

export const groupQualityReasons = [
  '所有参与机位满足当前事件准入要求。',
  '部分机位被排除，但剩余机位足以支持人工标注。',
  '机位事实或质量结论需要人工进一步确认。',
  '当前事件不满足人工标注准入要求。',
] as const

export function receiptTone(state: CaptureGroup['receipt_state']): StatusIndicatorTone {
  if (state === 'COMPLETE') return 'success'
  if (state === 'INCOMPLETE') return 'warning'
  return 'information'
}

export function groupQualityTone(result: CaptureGroupQualityResult): StatusIndicatorTone {
  if (result === 'QUALIFIED' || result === 'QUALIFIED_WITH_EXCLUSIONS') return 'success'
  if (result === 'NEEDS_REVIEW') return 'warning'
  if (result === 'REJECTED') return 'destructive'
  return 'neutral'
}

export function viewQualityTone(
  decision: CaptureGroupQualityView['quality_review_decision'],
): StatusIndicatorTone {
  if (decision === 'QUALIFIED') return 'success'
  if (decision === 'NEEDS_REVIEW') return 'warning'
  if (decision === 'REJECTED') return 'destructive'
  return 'neutral'
}

export function streamTone(stream: CaptureGroupStream | null): StatusIndicatorTone {
  if (!stream) return 'destructive'
  if (stream.stream_status === 'INGEST_FAILED' || stream.stream_status === 'UNAVAILABLE') {
    return 'destructive'
  }
  if (stream.stream_status === 'INGESTED') return 'success'
  return 'information'
}

export function formatViewQualitySummary(review: CaptureGroupQualityReview | null) {
  const views = review?.confirmed_view_set?.views ?? []
  if (!views.length) return '未形成事件质量快照'
  const counts = views.reduce(
    (summary, view) => {
      summary[view.quality_review_decision] += 1
      return summary
    },
    { QUALIFIED: 0, NEEDS_REVIEW: 0, REJECTED: 0, UNREVIEWED: 0 } as Record<
      CaptureGroupQualityView['quality_review_decision'],
      number
    >,
  )
  return [
    counts.QUALIFIED ? `${counts.QUALIFIED} 合格` : '',
    counts.NEEDS_REVIEW ? `${counts.NEEDS_REVIEW} 需复核` : '',
    counts.REJECTED ? `${counts.REJECTED} 不合格` : '',
    counts.UNREVIEWED ? `${counts.UNREVIEWED} 待判断` : '',
  ]
    .filter(Boolean)
    .join(' · ')
}

export type CaptureGroupTaskVisibility = 'visible' | 'permission-limited' | 'unavailable'

export interface CaptureGroupTaskDestination {
  focus: CaptureGroupFocus
  actionLabel: string
}

export function captureGroupTaskDestination(
  task: Pick<
    AnnotationTask,
    | 'capture_group_id'
    | 'primary_clip_asset_id'
    | 'status'
    | 'can_submit'
    | 'can_select_primary'
    | 'can_review'
    | 'annotation_revision'
  >,
): CaptureGroupTaskDestination | null {
  if (!task.capture_group_id) return null

  if (task.status === 'ASSIGNED' && (task.can_submit || task.can_select_primary)) {
    return {
      focus: 'annotation',
      actionLabel:
        task.annotation_revision?.review?.decision === 'REJECTED'
          ? '进入标注返工'
          : task.primary_clip_asset_id
            ? '进入配药动作标注'
            : '选择主视图',
    }
  }

  if (task.status === 'SUBMITTED' && task.can_review && task.annotation_revision) {
    return { focus: 'review', actionLabel: '进入人工复核' }
  }

  // Completed task visibility is already authorized by the task list. The
  // recheck mutation remains guarded separately by can_recheck_post_cleaning.
  if (task.status === 'COMPLETED' && task.annotation_revision) {
    return { focus: 'cleaning', actionLabel: '查看复核 / 训练资格检查结果' }
  }

  return null
}

export const captureGroupFocusTargetIds: Record<CaptureGroupFocus, string> = {
  annotation: 'capture-group-annotation',
  review: 'capture-group-review-cleaning',
  cleaning: 'capture-group-review-cleaning',
}

export const captureGroupReviewCleaningFallbackTargetId = 'capture-group-review-cleaning-context'

export function captureGroupFocusFromLocation(
  search: string,
  hash: string,
): CaptureGroupFocus | null {
  const queryFocus = new URLSearchParams(search).get('focus')
  if (queryFocus === 'annotation' || queryFocus === 'review' || queryFocus === 'cleaning') {
    return queryFocus
  }

  const hashTarget = hash.replace(/^#/, '')
  const matchedFocus = Object.entries(captureGroupFocusTargetIds).find(
    ([, targetId]) => targetId === hashTarget,
  )
  return (matchedFocus?.[0] as CaptureGroupFocus | undefined) ?? null
}

export function taskNextAction(
  group: Pick<CaptureGroup, 'receipt_state'>,
  quality: CaptureGroupQualityReview | null,
  task: AnnotationTask | null,
  taskVisibility: CaptureGroupTaskVisibility = 'visible',
) {
  if (group.receipt_state === 'RECEIVING') return '等待接收完成后进行事件质量判断'
  if (!quality || quality.result === 'UNREVIEWED') return '进行事件质量判断'
  if (quality.result === 'NEEDS_REVIEW' || quality.result === 'REJECTED') {
    if (!task && taskVisibility === 'permission-limited') return '任务状态按当前权限不可见'
    if (!task && taskVisibility === 'unavailable') return '任务状态暂不可用，请刷新后重试'
    return task ? '查看历史任务；事件质量恢复后再提交修订' : '补充机位事实并重新判断事件质量'
  }
  if (!task) {
    if (taskVisibility === 'permission-limited') return '任务状态按当前权限不可见'
    if (taskVisibility === 'unavailable') return '任务状态暂不可用，请刷新后重试'
    return '等待系统创建唯一配药事件任务'
  }
  if (task.status === 'ASSIGNED') {
    if (!task.primary_clip_asset_id) {
      return task.can_select_primary ? '选择主视图' : '等待任务执行人选择主视图'
    }
    if (!task.can_submit) return '等待任务执行人处理'
    if (task.annotation_revision?.review?.decision === 'REJECTED') {
      return '根据复核意见修改'
    }
    return '提交配药动作修订'
  }
  if (task.status === 'SUBMITTED') {
    return task.can_review ? '进入人工复核' : '等待人工复核'
  }
  if (!task.primary_clip_asset_id) return '选择主视图'
  if (task.annotation_revision?.review?.decision === 'REJECTED') return '根据复核意见修改'
  if (task.annotation_revision?.review?.decision === 'APPROVED') {
    return task.annotation_revision.capture_group_cleaning?.outcome === 'PASS'
      ? '训练资格检查已通过'
      : '查看训练资格检查结果'
  }
  return '查看任务历史'
}

export function syncSafety(stream: CaptureGroupStream) {
  if (stream.sync_status === 'PASS') {
    return {
      label: '边端同步声明正常 · 仅供参考',
      tone: 'information' as const,
      detail: '边端声明仅作参考；当前没有已验证的可靠时间映射，参考视图不能按精确同步展示。',
      referenceOnly: true,
    }
  }
  if (stream.sync_status === 'WARNING') {
    return {
      label: '同步需关注 · 仅供参考',
      tone: 'warning' as const,
      detail: '仅作人工视觉对照，不代表精确时间对齐。',
      referenceOnly: true,
    }
  }
  if (stream.sync_status === 'REJECT') {
    return {
      label: '同步异常 · 仅供参考',
      tone: 'destructive' as const,
      detail: '不得依据该声明推导精确参考视图时间。',
      referenceOnly: true,
    }
  }
  return {
    label: '同步未知 · 仅供参考',
    tone: 'neutral' as const,
    detail: '当前没有后端验证映射；参考视图仅供事件级人工参考。',
    referenceOnly: true,
  }
}

export function qualityViewFromReview(
  review: CaptureGroupQualityReview | null,
  clipAssetId: string,
) {
  return (
    review?.confirmed_view_set?.views.find((view) => view.clip_asset_id === clipAssetId) ?? null
  )
}
