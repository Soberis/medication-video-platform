import type { ActionCandidate } from '@/api/action-candidate'
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

export function createDraftSegment(
  segment?: AnnotationSegment,
  fallbackStartMs = 0,
  fallbackEndMs = 1000,
): DraftAnnotationSegment {
  nextDraftSegmentId += 1
  return {
    draftId: nextDraftSegmentId,
    start_ms: segment?.start_ms ?? fallbackStartMs,
    end_ms: segment?.end_ms ?? fallbackEndMs,
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
  const msPart = milliseconds > 0 ? `.${String(milliseconds).padStart(3, '0')}` : ''
  const clock = `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}${msPart}`
  return hours > 0 ? `${String(hours).padStart(2, '0')}:${clock}` : clock
}

export function formatHumanFriendlyTime(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms) || ms < 0) return '00:00'
  const totalSeconds = Math.floor(ms / 1000)
  const seconds = totalSeconds % 60
  const totalMinutes = Math.floor(totalSeconds / 60)
  const minutes = totalMinutes % 60
  const hours = Math.floor(totalMinutes / 60)
  const clock = `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
  return hours > 0 ? `${String(hours).padStart(2, '0')}:${clock}` : clock
}

export function formatSegmentDurationLabel(startMs: number, endMs: number): string {
  if (!Number.isFinite(startMs) || !Number.isFinite(endMs) || endMs <= startMs) {
    return `${formatHumanFriendlyTime(startMs)} ~ ${formatHumanFriendlyTime(endMs)}`
  }
  const durationSec = Math.max(0, (endMs - startMs) / 1000)
  return `${formatHumanFriendlyTime(startMs)} ~ ${formatHumanFriendlyTime(endMs)} · 持续 ${durationSec.toFixed(1)} 秒`
}

export const ACTION_PALETTE: Record<
  string,
  { bg: string; border: string; text: string; dot: string; label: string }
> = {
  open_cap: {
    bg: '#fff7e6',
    border: '#ffd591',
    text: '#d46b08',
    dot: '#fa8c16',
    label: '操作瓶盖',
  },
  draw_liquid: {
    bg: '#e6f4ff',
    border: '#91caff',
    text: '#0958d9',
    dot: '#1677ff',
    label: '抽取药液',
  },
  inject_mix: {
    bg: '#f9f0ff',
    border: '#d3adf7',
    text: '#531dab',
    dot: '#722ed1',
    label: '注入混匀',
  },
  check_info: {
    bg: '#f6ffed',
    border: '#b7eb8f',
    text: '#389e0d',
    dot: '#52c41a',
    label: '核对信息',
  },
  label_seal: {
    bg: '#e6fffb',
    border: '#87e8de',
    text: '#08979c',
    dot: '#13c2c2',
    label: '贴签封包',
  },
  prepare_workspace: {
    bg: '#f0f5ff',
    border: '#adc6ff',
    text: '#1d39c4',
    dot: '#2f54eb',
    label: '准备工位',
  },
  retrieve_medicine: {
    bg: '#feffe6',
    border: '#fffb8f',
    text: '#d4b106',
    dot: '#fadb14',
    label: '取药',
  },
  prepare_dose: {
    bg: '#fff0f6',
    border: '#ffadd2',
    text: '#c41d7f',
    dot: '#eb2f96',
    label: '剂量配制',
  },
  final_review: {
    bg: '#fcffe6',
    border: '#eaff8f',
    text: '#7cb305',
    dot: '#a0d911',
    label: '最终复核',
  },
  cleanup: { bg: '#f5f5f5', border: '#d9d9d9', text: '#595959', dot: '#8c8c8c', label: '清理废弃' },
}

export function getActionStyle(labelKey: string) {
  const key = labelKey.toLowerCase()
  return (
    ACTION_PALETTE[key] ?? {
      bg: '#f0f0f0',
      border: '#d9d9d9',
      text: '#434343',
      dot: '#8c8c8c',
      label: labelKey,
    }
  )
}

export function initialDraftSegments(
  task: AnnotationTask | null,
  candidates?: ActionCandidate[],
  schema?: AnnotationLabelSchema | null,
  durationMs?: number | null,
) {
  if (!task || task.status !== 'ASSIGNED') return []
  const revision = task.annotation_revision
  if (revision?.annotation_document?.segments && revision.annotation_document.segments.length > 0) {
    return revision.annotation_document.segments.map((segment) => createDraftSegment(segment))
  }
  const validCandidates = validActionCandidates(task, candidates ?? [], schema, durationMs)
  if (validCandidates.length > 0) {
    return validCandidates.map((candidate) =>
      createDraftSegment({
        start_ms: candidate.start_ms,
        end_ms: candidate.end_ms,
        label: candidate.action_label,
      }),
    )
  }
  return [createDraftSegment()]
}

export function validActionCandidates(
  task: AnnotationTask | null,
  candidates: ActionCandidate[],
  schema?: AnnotationLabelSchema | null,
  durationMs?: number | null,
): ActionCandidate[] {
  if (
    !task ||
    !schema ||
    !durationMs ||
    task.label_schema_id !== schema.label_schema_id ||
    task.label_schema_digest !== schema.schema_digest
  )
    return []
  const ordered = [...candidates].sort((a, b) => a.start_ms - b.start_ms)
  const labels = new Set(schema.labels.map((label) => label.label_id))
  const valid = ordered.every(
    (candidate, index) =>
      candidate.project_id === task.project_id &&
      candidate.capture_group_id === task.capture_group_id &&
      candidate.clip_asset_id === task.primary_clip_asset_id &&
      candidate.ai_job_id === ordered[0]?.ai_job_id &&
      labels.has(candidate.action_label) &&
      Number.isSafeInteger(candidate.start_ms) &&
      Number.isSafeInteger(candidate.end_ms) &&
      candidate.start_ms >= 0 &&
      candidate.start_ms < candidate.end_ms &&
      candidate.end_ms <= durationMs &&
      (index === 0 || candidate.start_ms >= ordered[index - 1]!.end_ms),
  )
  return valid ? ordered : []
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
  if (state === 'error') return '检测结果读取失败；检测状态暂不可用，这不会阻止人工查看或标注。'
  if (state === 'unavailable') return '暂无手部检测结果；当前暂不可用，这不会阻止人工查看或标注。'
  if (!response) return '仅在媒体入库完成后读取手部检测。'
  if (response.job.status === 'FAILED') {
    const failureReason = response.job.run_summary?.failure_reason
    return `检测任务失败${failureReason ? `；失败原因：${failureReason}` : ''}。这是机器检测证据，不构成人工标签或数据质量结论。`
  }
  if (response.job.status === 'QUEUED')
    return '等待检测；这是机器检测证据，不构成人工标签或数据质量结论。'
  if (response.job.status === 'RUNNING')
    return '检测中；这是机器检测证据，不构成人工标签或数据质量结论。'
  if (response.job.hand_result === 'HAND_DETECTED') {
    return '检测到手部；这是机器检测证据，不构成人工标签或数据质量结论。'
  }
  if (response.job.hand_result === 'NO_HAND_DETECTED') {
    return '未检测到手部；这是机器检测证据，不构成人工标签或数据质量结论。'
  }
  return '结果未知；这是机器检测证据，不构成人工标签或数据质量结论。'
}

export function candidateTone(
  state: CandidateLoadState,
  response: HandPoseCandidateResponse | null,
): StatusIndicatorTone {
  if (state === 'error') return 'warning'
  if (state === 'unavailable') return 'information'
  if (state !== 'success' || !response) return 'information'
  if (response.job.status === 'FAILED') return 'warning'
  if (response?.job.status === 'SUCCEEDED' && response.job.hand_result === 'HAND_DETECTED') {
    return 'success'
  }
  if (response?.job.status === 'SUCCEEDED') return 'neutral'
  return 'information'
}

export function candidateStatusLabel(
  state: CandidateLoadState,
  response: HandPoseCandidateResponse | null,
) {
  if (state === 'error') return '读取失败'
  if (state === 'unavailable') return '暂不可用'
  if (state === 'loading') return '读取中'
  if (state !== 'success' || !response) return '未读取'
  if (response.job.status === 'FAILED') return '检测任务失败'
  if (response.job.status === 'QUEUED') return '等待检测'
  if (response.job.status === 'RUNNING') return '检测中'
  if (response.job.hand_result === 'HAND_DETECTED') return '已完成 · 检测到手部'
  if (response.job.hand_result === 'NO_HAND_DETECTED') return '已完成 · 未检测到手部'
  return '已完成 · 结果未知'
}

export function candidateLiveStatusLabel(
  state: CandidateLoadState,
  response: HandPoseCandidateResponse | null,
) {
  return candidateStatusLabel(state, response)
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

export function groupViewDecision(
  view: GroupViewRow,
  viewReviews: Record<string, QualityReview>,
): 'UNREVIEWED' | 'QUALIFIED' | 'NEEDS_REVIEW' | 'REJECTED' {
  if (!view.stream) return 'UNREVIEWED'
  return viewReviews[view.stream.clip_asset_id]?.decision ?? 'UNREVIEWED'
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

export function currentGroupQualityAdmitting(quality: CaptureGroupQualityReview | null) {
  return quality?.result === 'QUALIFIED' || quality?.result === 'QUALIFIED_WITH_EXCLUSIONS'
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

export function cleaningLabel(outcome: 'PASS' | 'WARNING' | 'REJECT') {
  if (outcome === 'PASS') return '当前规则检查通过'
  if (outcome === 'WARNING') return '需处理'
  return '当前规则检查未通过'
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
