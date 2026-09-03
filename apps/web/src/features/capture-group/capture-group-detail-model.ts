import type { AnnotationLabelSchema, AnnotationSegment, AnnotationTask } from '@/api/annotation'
import type {
  CaptureGroup,
  CaptureGroupQualityReview,
  CaptureGroupStream,
} from '@/api/capture-groups'
import type { HandPoseCandidateResponse } from '@/api/catalog'
import type {
  QualityReview,
  QualityReviewCommandDecision,
  QualityReviewReasonCode,
} from '@/api/quality'
import type { StatusIndicatorTone } from '@/components/status/StatusIndicator'
import type { CandidateLoadState } from '@/features/catalog/hand-pose-candidate-state'

export interface DraftAnnotationSegment extends AnnotationSegment {
  readonly draftId: number
}

export interface SegmentValidationErrors {
  start?: string
  end?: string
  label?: string
}

export interface GroupViewRow {
  cameraNumber: number
  member: CaptureGroup['camera_set_snapshot']['members'][number] | null
  stream: CaptureGroupStream | null
}

let nextDraftSegmentId = 0

export function createDraftSegment(segment?: AnnotationSegment): DraftAnnotationSegment {
  nextDraftSegmentId += 1
  return {
    draftId: nextDraftSegmentId,
    start_ms: segment?.start_ms ?? 0,
    end_ms: segment?.end_ms ?? 1000,
    label: segment?.label ?? '',
  }
}

function isValidAnnotationTime(value: number | null | undefined): value is number {
  return value !== null && value !== undefined && Number.isSafeInteger(value) && value >= 0
}

export function formatAnnotationTime(value: number | null | undefined) {
  if (!isValidAnnotationTime(value)) return null
  const milliseconds = value % 1000
  const seconds = Math.floor(value / 1000) % 60
  const minutes = Math.floor(value / 60_000) % 60
  const hours = Math.floor(value / 3_600_000)
  const clock = `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}.${String(milliseconds).padStart(3, '0')}`
  return hours > 0 ? `${String(hours).padStart(2, '0')}:${clock}` : clock
}

export function initialDraftSegments(task: AnnotationTask | null) {
  if (!task || task.status !== 'ASSIGNED') return []
  const revision = task.annotation_revision
  if (revision?.review?.decision === 'REJECTED') {
    return revision.annotation_document.segments.map((segment) => createDraftSegment(segment))
  }
  return [createDraftSegment()]
}

function validateSegment(segment: DraftAnnotationSegment): SegmentValidationErrors {
  const errors: SegmentValidationErrors = {}
  const validStart = isValidAnnotationTime(segment.start_ms)
  const validEnd = isValidAnnotationTime(segment.end_ms)
  if (!validStart) {
    errors.start =
      segment.start_ms < 0 ? '开始时间不能小于 0 毫秒。' : '开始时间必须是非负整数毫秒。'
  }
  if (!validEnd) {
    errors.end = segment.end_ms < 0 ? '结束时间不能小于 0 毫秒。' : '结束时间必须是非负整数毫秒。'
  } else if (validStart && segment.end_ms <= segment.start_ms) {
    errors.end = '结束时间必须晚于开始时间。'
  }
  if (!segment.label.trim()) errors.label = '请选择标签。'
  return errors
}

export function validateSegments(segments: DraftAnnotationSegment[]) {
  const errors: Record<number, SegmentValidationErrors> = {}
  for (const segment of segments) {
    const current = validateSegment(segment)
    if (current.start || current.end || current.label) errors[segment.draftId] = current
  }
  return errors
}

export function candidateDescription(
  state: CandidateLoadState,
  response: HandPoseCandidateResponse | null,
) {
  if (state === 'loading') return '正在读取手部检测；这不会阻止人工查看或标注。'
  if (state === 'error') return '手部检测暂时无法读取；这不会阻止人工流程。'
  if (state === 'unavailable') return '当前没有可用的手部检测；这不是人工流程失败。'
  if (!response) return '仅在媒体入库完成后读取手部检测。'
  if (response.job.status === 'FAILED') {
    return '手部检测失败；检测结果仅供人工参考，不作为正式标注或质量结论。'
  }
  if (response.job.status === 'QUEUED' || response.job.status === 'RUNNING') {
    return '手部检测处理中；检测结果仅供人工参考，不作为正式标注或质量结论。'
  }
  if (response.job.hand_result === 'NO_HAND_DETECTED') {
    return '未检测到手部；这是检测事实，不阻止人工流程。检测结果仅供人工参考，不作为正式标注或质量结论。'
  }
  if (response.job.hand_result === 'UNKNOWN') {
    return '检测结果未知；这是 AI 证据状态，不阻止人工流程。请人工判断视频是否可用。'
  }
  return `${response.candidates.length} 组手部关键点；检测结果仅供人工参考，不作为正式标注或质量结论。`
}

export function candidateTone(
  state: CandidateLoadState,
  response: HandPoseCandidateResponse | null,
): StatusIndicatorTone {
  if (state === 'error' || response?.job.status === 'FAILED') return 'warning'
  if (response?.job.hand_result === 'NO_HAND_DETECTED') return 'neutral'
  if (response?.job.hand_result === 'UNKNOWN') return 'neutral'
  if (state === 'success' && response?.job.status === 'SUCCEEDED') return 'success'
  return 'information'
}

export function candidateStatusLabel(
  state: CandidateLoadState,
  response: HandPoseCandidateResponse | null,
) {
  if (response?.job.hand_result === 'NO_HAND_DETECTED') {
    return '未检测到手部'
  }
  if (response?.job.status === 'FAILED' || state === 'error') return '失败'
  return state === 'success' ? '已读取' : '未读取'
}

export function candidateLiveStatusLabel(
  state: CandidateLoadState,
  response: HandPoseCandidateResponse | null,
) {
  if (state === 'error' || response?.job.status === 'FAILED') return '失败'
  if (response?.job.status === 'QUEUED' || response?.job.status === 'RUNNING') return '处理中'
  if (response?.job.hand_result === 'NO_HAND_DETECTED') return '已完成 · 未检测到手部'
  if (response?.job.status === 'SUCCEEDED') {
    if (response.job.hand_result === 'HAND_DETECTED') return '已完成 · 检测到手部'
    return '已完成 · 结果未知'
  }
  if (state === 'unavailable') return '暂不可用'
  if (state === 'loading') return '读取中'
  return '未读取'
}

export function formatTaskStatus(task: AnnotationTask) {
  if (task.status === 'COMPLETED') return '已完成'
  if (task.status === 'SUBMITTED') return '待复核'
  if (task.annotation_revision?.review?.decision === 'REJECTED') return '待修改'
  return '待提交'
}

export function taskStatusTone(task: AnnotationTask): StatusIndicatorTone {
  if (task.status === 'COMPLETED') return 'success'
  if (task.annotation_revision?.review?.decision === 'REJECTED') return 'warning'
  return task.status === 'SUBMITTED' ? 'information' : 'neutral'
}

export const qualityReasonsByDecision: Record<
  QualityReviewCommandDecision,
  QualityReviewReasonCode[]
> = {
  QUALIFIED: ['MEETS_REQUIREMENTS'],
  REJECTED: [
    'SENSITIVE_CONTENT',
    'BLURRY_OR_OUT_OF_FOCUS',
    'BLACK_OR_CORRUPTED_FRAMES',
    'SEVERE_OCCLUSION',
    'INCOMPLETE_ACTION',
    'WRONG_SCENE',
    'DUPLICATE',
    'OTHER_QUALITY_FAILURE',
  ],
  NEEDS_REVIEW: ['UNCERTAIN_QUALITY', 'METADATA_MISMATCH'],
}

export function groupViewDecision(
  view: GroupViewRow,
  viewReviews: Record<string, QualityReview>,
): 'UNREVIEWED' | 'QUALIFIED' | 'NEEDS_REVIEW' | 'REJECTED' {
  if (!view.stream) return 'UNREVIEWED'
  return viewReviews[view.stream.clip_asset_id]?.decision ?? 'UNREVIEWED'
}

export function currentGroupQualityAdmitting(quality: CaptureGroupQualityReview | null) {
  return quality?.result === 'QUALIFIED' || quality?.result === 'QUALIFIED_WITH_EXCLUSIONS'
}

export function createGroupViewRows(group: CaptureGroup | null): GroupViewRow[] {
  if (!group) return []
  return group.expected_camera_numbers.map((cameraNumber) => ({
    cameraNumber,
    member:
      group.camera_set_snapshot.members.find(
        (candidate) => candidate.camera_number === cameraNumber,
      ) ?? null,
    stream: group.streams.find((candidate) => candidate.camera_number === cameraNumber) ?? null,
  }))
}

export function selectedQualityLoadState(
  selectedStream: CaptureGroupStream | null,
  viewReviews: Record<string, QualityReview>,
  viewReviewErrors: Record<string, string>,
  viewQualityLoading: boolean,
) {
  return selectedStream
    ? viewReviewErrors[selectedStream.clip_asset_id]
      ? 'error'
      : viewQualityLoading || !viewReviews[selectedStream.clip_asset_id]
        ? 'loading'
        : 'success'
    : 'success'
}

export function cleaningLabel(outcome: 'PASS' | 'WARNING' | 'REJECT') {
  if (outcome === 'PASS') return '当前规则检查通过'
  if (outcome === 'WARNING') return '需处理'
  return '训练资格检查未通过'
}

export function cleaningTone(outcome: 'PASS' | 'WARNING' | 'REJECT'): StatusIndicatorTone {
  if (outcome === 'PASS') return 'success'
  return outcome === 'WARNING' ? 'warning' : 'destructive'
}

export function sortedSchemaLabels(schema: AnnotationLabelSchema | null) {
  return [...(schema?.labels ?? [])].sort(
    (left, right) =>
      left.sort_order - right.sort_order || left.label_id.localeCompare(right.label_id),
  )
}
