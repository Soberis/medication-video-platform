import { App as AntdApp, Alert, Button, Descriptions, Input, Select } from 'antd'
import {
  Check,
  CircleAlert,
  Clock3,
  LockKeyhole,
  RefreshCw,
  ShieldAlert,
  Video,
} from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { Link, useLocation, useParams } from 'react-router'

import {
  CaptureGroupApiError,
  getCaptureGroup,
  getCaptureGroupQualityReview,
  listCaptureGroupQualityReviewHistory,
  recordCaptureGroupQualityReview,
  finalizeCaptureGroupReceipt,
  type CaptureGroup,
  type CaptureGroupQualityCommandResult,
  type CaptureGroupQualityReview,
  type CaptureGroupStream,
} from '@/api/capture-groups'
import {
  AnnotationTaskApiError,
  getAnnotationLabelSchema,
  listAnnotationTasks,
  selectAnnotationPrimaryView,
  submitAnnotationRevision,
  type AnnotationLabelSchema,
  type AnnotationSegment,
  type AnnotationTask,
} from '@/api/annotation'
import {
  authorizeClipAssetPlayback,
  CatalogApiError,
  getClipAsset,
  getClipAssetMediaReport,
  getLatestHandPoseCandidates,
  type ClipAssetPlaybackGrant,
  type ClipAssetRead,
  type HandPoseCandidateResponse,
  type MediaReport,
} from '@/api/catalog'
import {
  getQualityReview,
  recordQualityReview,
  type QualityReview,
  type QualityReviewCommandDecision,
  type QualityReviewReasonCode,
} from '@/api/quality'
import { paths } from '@/app/paths'
import { PlaybackCard, MediaReportCard, type PlaybackState } from '@/components/AssetDetailSections'
import { MetadataList } from '@/components/data/MetadataList'
import { PageHeader } from '@/components/PageHeader'
import { QualityReviewCard } from '@/components/AssetDetailSections'
import { StatusBadge, type StatusBadgeTone } from '@/components/StatusBadge'
import { StatusIndicator, type StatusIndicatorTone } from '@/components/status/StatusIndicator'
import { ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { InspectorSection } from '@/components/workspace/InspectorSection'
import { Section } from '@/components/workspace/Section'
import {
  classifyCandidateReadError,
  type CandidateLoadState,
} from '@/features/catalog/hand-pose-candidate-state'
import {
  groupQualityLabels,
  groupQualityTone,
  mediaStatusLabels,
  receiptStateLabels,
  receiptTone,
  syncSafety,
  technicalQualityLabels,
  viewQualityLabels,
  viewQualityTone,
  streamTone,
} from '@/features/capture-group-presentation'
import { formatDateTime } from '@/features/catalog/catalog-presentation'

interface DraftAnnotationSegment extends AnnotationSegment {
  readonly draftId: number
}

interface SegmentValidationErrors {
  start?: string
  end?: string
  label?: string
}

interface GroupViewRow {
  cameraNumber: number
  member: CaptureGroup['camera_set_snapshot']['members'][number] | null
  stream: CaptureGroupStream | null
}

let nextDraftSegmentId = 0

function createDraftSegment(segment?: AnnotationSegment): DraftAnnotationSegment {
  nextDraftSegmentId += 1
  return {
    draftId: nextDraftSegmentId,
    start_ms: segment?.start_ms ?? 0,
    end_ms: segment?.end_ms ?? 1000,
    label: segment?.label ?? '',
  }
}

function initialDraftSegments(task: AnnotationTask | null) {
  if (!task || task.status !== 'ASSIGNED') return []
  const revision = task.annotation_revision
  if (revision?.review?.decision === 'REJECTED') {
    return revision.annotation_document.segments.map((segment) => createDraftSegment(segment))
  }
  return [createDraftSegment()]
}

function validateSegment(segment: DraftAnnotationSegment): SegmentValidationErrors {
  const errors: SegmentValidationErrors = {}
  if (segment.start_ms < 0) errors.start = '开始时间不能小于 0 毫秒。'
  if (segment.end_ms <= segment.start_ms) errors.end = '结束时间必须晚于开始时间。'
  if (!segment.label.trim()) errors.label = '请选择标签。'
  return errors
}

function validateSegments(segments: DraftAnnotationSegment[]) {
  const errors: Record<number, SegmentValidationErrors> = {}
  for (const segment of segments) {
    const current = validateSegment(segment)
    if (current.start || current.end || current.label) errors[segment.draftId] = current
  }
  return errors
}

function candidateDescription(
  state: CandidateLoadState,
  response: HandPoseCandidateResponse | null,
) {
  if (state === 'loading') return '正在读取 MediaPipe Candidate；这不会阻止人工查看或标注。'
  if (state === 'error') return 'MediaPipe Candidate 暂时无法读取；这不会阻止人工流程。'
  if (state === 'unavailable') return '当前没有可用的 MediaPipe Candidate；这不是人工流程失败。'
  if (!response) return '仅在媒体入库完成后读取 MediaPipe Candidate。'
  if (response.job.status === 'FAILED') {
    return 'MediaPipe Candidate 失败；结果仅作辅助，不影响人工质量判断或标注。'
  }
  if (response.job.status === 'QUEUED' || response.job.status === 'RUNNING') {
    return 'MediaPipe Candidate 处理中；结果仅作辅助，不影响人工质量判断或标注。'
  }
  if (response.job.hand_result === 'NO_HAND_DETECTED') {
    return 'NO_HAND_DETECTED：未检测到手部；这是正常候选事实，不阻止人工流程。'
  }
  return `${response.candidates.length} 组手部关键点；结果仅作辅助，不是正式人工标注。`
}

function candidateTone(
  state: CandidateLoadState,
  response: HandPoseCandidateResponse | null,
): StatusIndicatorTone {
  if (state === 'error' || response?.job.status === 'FAILED') return 'warning'
  if (response?.job.hand_result === 'NO_HAND_DETECTED') return 'neutral'
  if (state === 'success' && response?.job.status === 'SUCCEEDED') return 'success'
  return 'information'
}

function formatTaskStatus(task: AnnotationTask) {
  if (task.status === 'COMPLETED') return '已完成'
  if (task.status === 'SUBMITTED') return '待 Review'
  if (task.annotation_revision?.review?.decision === 'REJECTED') return '待修改'
  return '待提交'
}

function taskStatusTone(task: AnnotationTask): StatusIndicatorTone {
  if (task.status === 'COMPLETED') return 'success'
  if (task.annotation_revision?.review?.decision === 'REJECTED') return 'warning'
  return task.status === 'SUBMITTED' ? 'information' : 'neutral'
}

const qualityReasonsByDecision: Record<QualityReviewCommandDecision, QualityReviewReasonCode[]> = {
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

function groupViewDecision(
  view: GroupViewRow,
  viewReviews: Record<string, QualityReview>,
): 'UNREVIEWED' | 'QUALIFIED' | 'NEEDS_REVIEW' | 'REJECTED' {
  if (!view.stream) return 'UNREVIEWED'
  return viewReviews[view.stream.clip_asset_id]?.decision ?? 'UNREVIEWED'
}

function currentGroupQualityAdmitting(quality: CaptureGroupQualityReview | null) {
  return quality?.result === 'QUALIFIED' || quality?.result === 'QUALIFIED_WITH_EXCLUSIONS'
}

export default function CaptureGroupDetailView() {
  const { projectId = '', captureGroupId = '' } = useParams()
  const location = useLocation()
  const { message } = AntdApp.useApp()
  const [group, setGroup] = useState<CaptureGroup | null>(null)
  const [groupQuality, setGroupQuality] = useState<CaptureGroupQualityReview | null>(null)
  const [qualityHistory, setQualityHistory] = useState<CaptureGroupQualityReview[]>([])
  const [task, setTask] = useState<AnnotationTask | null>(null)
  const [schema, setSchema] = useState<AnnotationLabelSchema | null>(null)
  const [viewReviews, setViewReviews] = useState<Record<string, QualityReview>>({})
  const [viewReviewErrors, setViewReviewErrors] = useState<Record<string, string>>({})
  const [groupLoadState, setGroupLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [groupErrorCode, setGroupErrorCode] = useState('')
  const [groupErrorMessage, setGroupErrorMessage] = useState('')
  const [qualityErrorMessage, setQualityErrorMessage] = useState('')
  const [taskErrorMessage, setTaskErrorMessage] = useState('')
  const [viewQualityLoading, setViewQualityLoading] = useState(true)
  const [selectedClipAssetId, setSelectedClipAssetId] = useState<string | null>(null)
  const [selectedDetail, setSelectedDetail] = useState<ClipAssetRead | null>(null)
  const [selectedMediaReport, setSelectedMediaReport] = useState<MediaReport | null>(null)
  const [selectedMediaReportState, setSelectedMediaReportState] = useState<
    'loading' | 'success' | 'error'
  >('loading')
  const [selectedMediaReportError, setSelectedMediaReportError] = useState('')
  const [candidateResponse, setCandidateResponse] = useState<HandPoseCandidateResponse | null>(null)
  const [candidateState, setCandidateState] = useState<CandidateLoadState>('idle')
  const [playbackGrant, setPlaybackGrant] = useState<ClipAssetPlaybackGrant | null>(null)
  const [playbackState, setPlaybackState] = useState<PlaybackState>('idle')
  const [playbackMessage, setPlaybackMessage] = useState('')
  const [isRequestingPlayback, setIsRequestingPlayback] = useState(false)
  const [groupDecision, setGroupDecision] =
    useState<CaptureGroupQualityCommandResult>('NEEDS_REVIEW')
  const [groupReason, setGroupReason] = useState('')
  const [isSavingGroupQuality, setIsSavingGroupQuality] = useState(false)
  const [isFinalizingReceipt, setIsFinalizingReceipt] = useState(false)
  const [selectedQualityDecision, setSelectedQualityDecision] =
    useState<QualityReviewCommandDecision>('QUALIFIED')
  const [selectedQualityReason, setSelectedQualityReason] =
    useState<QualityReviewReasonCode>('MEETS_REQUIREMENTS')
  const [isSavingViewQuality, setIsSavingViewQuality] = useState(false)
  const [isSelectingPrimary, setIsSelectingPrimary] = useState(false)
  const [draftSegments, setDraftSegments] = useState<DraftAnnotationSegment[]>([])
  const [segmentErrors, setSegmentErrors] = useState<Record<number, SegmentValidationErrors>>({})
  const [annotationNote, setAnnotationNote] = useState('')
  const [isSubmittingRevision, setIsSubmittingRevision] = useState(false)
  const groupRequest = useRef<AbortController | null>(null)
  const selectedViewRequest = useRef<AbortController | null>(null)
  const playbackExpiryTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const viewRows = useMemo<GroupViewRow[]>(() => {
    if (!group) return []
    return group.expected_camera_numbers.map((cameraNumber) => ({
      cameraNumber,
      member:
        group.camera_set_snapshot.members.find(
          (candidate) => candidate.camera_number === cameraNumber,
        ) ?? null,
      stream: group.streams.find((candidate) => candidate.camera_number === cameraNumber) ?? null,
    }))
  }, [group])

  const selectedView = useMemo(
    () => viewRows.find((view) => view.stream?.clip_asset_id === selectedClipAssetId) ?? null,
    [selectedClipAssetId, viewRows],
  )
  const selectedStream = selectedView?.stream ?? null
  const selectedReview = selectedStream ? (viewReviews[selectedStream.clip_asset_id] ?? null) : null
  const sortedLabels = useMemo(
    () =>
      [...(schema?.labels ?? [])].sort(
        (left, right) =>
          left.sort_order - right.sort_order || left.label_id.localeCompare(right.label_id),
      ),
    [schema],
  )
  const groupQualityAdmitting = currentGroupQualityAdmitting(groupQuality)
  const groupQualityDowngraded =
    groupQuality?.result === 'NEEDS_REVIEW' || groupQuality?.result === 'REJECTED'
  const primaryLocked = Boolean(task?.annotation_revision)
  const canSubmitRevision = Boolean(
    task &&
      task.capture_group_id === captureGroupId &&
      task.status === 'ASSIGNED' &&
      task.can_submit &&
      task.primary_clip_asset_id &&
      groupQualityAdmitting &&
      !groupQualityDowngraded,
  )
  const selectedQualityLoadState = selectedStream
    ? viewReviewErrors[selectedStream.clip_asset_id]
      ? 'error'
      : viewQualityLoading || !viewReviews[selectedStream.clip_asset_id]
        ? 'loading'
        : 'success'
    : 'success'
  const selectedQualityError = selectedStream
    ? (viewReviewErrors[selectedStream.clip_asset_id] ?? '')
    : ''

  const clearPlaybackExpiryTimer = useCallback(() => {
    if (playbackExpiryTimer.current !== null) {
      clearTimeout(playbackExpiryTimer.current)
      playbackExpiryTimer.current = null
    }
  }, [])

  const resetPlayback = useCallback(() => {
    clearPlaybackExpiryTimer()
    setPlaybackGrant(null)
    setPlaybackState('idle')
    setPlaybackMessage('')
  }, [clearPlaybackExpiryTimer])

  const loadGroup = useCallback(async () => {
    groupRequest.current?.abort()
    const request = new AbortController()
    groupRequest.current = request
    setGroupLoadState('loading')
    setGroupErrorCode('')
    setGroupErrorMessage('')
    setQualityErrorMessage('')
    setTaskErrorMessage('')
    setViewQualityLoading(true)
    try {
      const nextGroup = await getCaptureGroup(projectId, captureGroupId, request.signal)
      if (request.signal.aborted || groupRequest.current !== request) return
      const [qualityResult, historyResult, taskResult, schemaResult] = await Promise.allSettled([
        getCaptureGroupQualityReview(projectId, captureGroupId, request.signal),
        listCaptureGroupQualityReviewHistory(projectId, captureGroupId, request.signal),
        listAnnotationTasks(projectId, request.signal),
        getAnnotationLabelSchema(projectId, request.signal),
      ])
      if (request.signal.aborted || groupRequest.current !== request) return

      const nextQuality = qualityResult.status === 'fulfilled' ? qualityResult.value : null
      const nextHistory = historyResult.status === 'fulfilled' ? historyResult.value.items : []
      const taskList = taskResult.status === 'fulfilled' ? taskResult.value.items : []
      const nextTask =
        taskList.find((candidate) => candidate.capture_group_id === captureGroupId) ?? null
      const nextSchema = schemaResult.status === 'fulfilled' ? schemaResult.value : null
      setGroup(nextGroup)
      setGroupQuality(nextQuality)
      setQualityHistory(nextHistory)
      setTask(nextTask)
      setSchema(nextSchema)
      if (qualityResult.status === 'rejected') {
        setQualityErrorMessage(
          '暂时无法读取当前 Group Quality；详情仍保留 CaptureGroup 与视图事实。',
        )
      }
      if (taskResult.status === 'rejected') {
        setTaskErrorMessage('暂时无法读取唯一 CaptureGroup 任务；请刷新后重试。')
      }
      if (nextTask && schemaResult.status === 'rejected') {
        setTaskErrorMessage('标签规范暂时无法读取，当前不能提交新的修订。')
      }

      const reviewEntries = await Promise.all(
        nextGroup.streams.map(async (stream) => {
          try {
            return [
              stream.clip_asset_id,
              await getQualityReview(projectId, stream.clip_asset_id, request.signal),
            ] as const
          } catch (error) {
            return [
              stream.clip_asset_id,
              error instanceof Error ? error : new Error('VIEW_QUALITY_REQUEST_FAILED'),
            ] as const
          }
        }),
      )
      if (request.signal.aborted || groupRequest.current !== request) return
      const nextReviews: Record<string, QualityReview> = {}
      const nextErrors: Record<string, string> = {}
      for (const [clipAssetId, result] of reviewEntries) {
        if ('decision' in result) {
          nextReviews[clipAssetId] = result
        } else {
          nextErrors[clipAssetId] = result.message
        }
      }
      setViewReviews(nextReviews)
      setViewReviewErrors(nextErrors)
      setViewQualityLoading(false)
      setSelectedClipAssetId((current) => {
        const presentIds = new Set(nextGroup.streams.map((stream) => stream.clip_asset_id))
        if (current && presentIds.has(current)) return current
        if (nextTask?.primary_clip_asset_id && presentIds.has(nextTask.primary_clip_asset_id)) {
          return nextTask.primary_clip_asset_id
        }
        return nextGroup.streams[0]?.clip_asset_id ?? null
      })
      setGroupDecision(
        nextQuality?.result && nextQuality.result !== 'UNREVIEWED'
          ? nextQuality.result
          : 'NEEDS_REVIEW',
      )
      setGroupReason(nextQuality?.reason ?? '')
      setDraftSegments(initialDraftSegments(nextTask))
      setSegmentErrors({})
      setAnnotationNote('')
      setGroupLoadState('success')
    } catch (error) {
      if (request.signal.aborted || groupRequest.current !== request) return
      setGroupLoadState('error')
      setGroupErrorCode(
        error instanceof CaptureGroupApiError || error instanceof CatalogApiError
          ? error.errorCode
          : 'CAPTURE_GROUP_NOT_FOUND',
      )
      setGroupErrorMessage(
        error instanceof CaptureGroupApiError || error instanceof CatalogApiError
          ? error.message
          : error instanceof Error
            ? error.message
            : '暂时无法读取 CaptureGroup 详情，请稍后重试。',
      )
    } finally {
      if (groupRequest.current === request) groupRequest.current = null
    }
  }, [captureGroupId, projectId])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadGroup(), 0)
    return () => {
      window.clearTimeout(timer)
      groupRequest.current?.abort()
    }
  }, [loadGroup])

  const loadSelectedView = useCallback(async () => {
    selectedViewRequest.current?.abort()
    const request = new AbortController()
    selectedViewRequest.current = request
    resetPlayback()
    setSelectedDetail(null)
    setSelectedMediaReport(null)
    setSelectedMediaReportState('loading')
    setSelectedMediaReportError('')
    setCandidateResponse(null)
    setCandidateState('idle')
    if (!selectedClipAssetId || !group) {
      setSelectedMediaReportState('success')
      return
    }
    const stream = group.streams.find(
      (candidate) => candidate.clip_asset_id === selectedClipAssetId,
    )
    if (!stream) {
      setSelectedMediaReportState('success')
      return
    }
    try {
      const detail = await getClipAsset(projectId, selectedClipAssetId, request.signal)
      if (request.signal.aborted || selectedViewRequest.current !== request) return
      setSelectedDetail(detail)
      if (detail.status !== 'INGESTED') {
        setSelectedMediaReportState('success')
        setCandidateState('unavailable')
        return
      }
      const [candidateResult, mediaReportResult] = await Promise.allSettled([
        getLatestHandPoseCandidates(projectId, selectedClipAssetId, request.signal),
        getClipAssetMediaReport(projectId, selectedClipAssetId, request.signal),
      ])
      if (request.signal.aborted || selectedViewRequest.current !== request) return
      if (candidateResult.status === 'fulfilled') {
        setCandidateResponse(candidateResult.value)
        setCandidateState('success')
      } else {
        setCandidateState(classifyCandidateReadError(candidateResult.reason))
      }
      if (mediaReportResult.status === 'fulfilled') {
        setSelectedMediaReport(mediaReportResult.value)
        setSelectedMediaReportState('success')
      } else {
        setSelectedMediaReportState('error')
        setSelectedMediaReportError(
          mediaReportResult.reason instanceof CatalogApiError
            ? mediaReportResult.reason.message
            : '媒体检测报告暂时无法读取。',
        )
      }
    } catch (error) {
      if (request.signal.aborted || selectedViewRequest.current !== request) return
      setSelectedMediaReportState('error')
      setSelectedMediaReportError(
        error instanceof CatalogApiError ? error.message : '暂时无法读取所选视图的媒体事实。',
      )
    } finally {
      if (selectedViewRequest.current === request) selectedViewRequest.current = null
    }
  }, [group, projectId, resetPlayback, selectedClipAssetId])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadSelectedView(), 0)
    return () => {
      window.clearTimeout(timer)
      selectedViewRequest.current?.abort()
    }
  }, [loadSelectedView])

  useEffect(() => {
    const review = selectedReview
    const decision = review?.decision
    const nextDecision: QualityReviewCommandDecision =
      decision === 'REJECTED' || decision === 'NEEDS_REVIEW' || decision === 'QUALIFIED'
        ? decision
        : 'QUALIFIED'
    const timer = window.setTimeout(() => {
      setSelectedQualityDecision(nextDecision)
      setSelectedQualityReason(
        review?.reason_code && qualityReasonsByDecision[nextDecision].includes(review.reason_code)
          ? review.reason_code
          : (qualityReasonsByDecision[nextDecision][0] ?? 'MEETS_REQUIREMENTS'),
      )
    }, 0)
    return () => window.clearTimeout(timer)
  }, [selectedReview])

  useEffect(() => {
    return () => clearPlaybackExpiryTimer()
  }, [clearPlaybackExpiryTimer])

  async function startPlayback() {
    if (
      !selectedClipAssetId ||
      selectedDetail?.content_type !== 'video/mp4' ||
      isRequestingPlayback
    ) {
      return
    }
    resetPlayback()
    setIsRequestingPlayback(true)
    try {
      const grant = await authorizeClipAssetPlayback(projectId, selectedClipAssetId)
      setPlaybackGrant(grant)
      setPlaybackState('waiting')
      const remaining = new Date(grant.expires_at).getTime() - Date.now()
      if (!Number.isFinite(remaining) || remaining <= 0) {
        setPlaybackState('expired')
        setPlaybackMessage('本次播放授权已到期，请重新获取后继续查看。')
      } else {
        playbackExpiryTimer.current = setTimeout(() => {
          playbackExpiryTimer.current = null
          setPlaybackState('expired')
          setPlaybackMessage('本次播放授权已到期，请重新获取后继续查看。')
        }, remaining)
      }
    } catch (error) {
      setPlaybackState('error')
      setPlaybackMessage(
        error instanceof CatalogApiError ? error.message : '无法获取播放授权，请稍后重试。',
      )
    } finally {
      setIsRequestingPlayback(false)
    }
  }

  async function finalizeReceipt() {
    if (!group || group.receipt_state !== 'RECEIVING' || isFinalizingReceipt) return
    setIsFinalizingReceipt(true)
    try {
      await finalizeCaptureGroupReceipt(projectId, captureGroupId)
      void message.success('CaptureGroup 回执已关闭，正在刷新服务端事实。')
      await loadGroup()
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '回执关闭未完成，请稍后重试。')
    } finally {
      setIsFinalizingReceipt(false)
    }
  }

  async function saveGroupQuality() {
    if (!group || !groupQuality || !groupReason.trim() || isSavingGroupQuality) return
    const receivingFinal = group.receipt_state === 'RECEIVING' && groupDecision !== 'NEEDS_REVIEW'
    if (receivingFinal) return
    setIsSavingGroupQuality(true)
    try {
      await recordCaptureGroupQualityReview(
        projectId,
        captureGroupId,
        groupDecision,
        groupReason,
        groupQuality.revision,
      )
      void message.success('Group Quality 已记录，正在刷新任务与视图快照。')
      await loadGroup()
    } catch (error) {
      void message.error(
        error instanceof Error ? error.message : 'Group Quality 暂未保存，请稍后重试。',
      )
    } finally {
      setIsSavingGroupQuality(false)
    }
  }

  async function saveViewQuality() {
    if (
      !selectedStream ||
      !selectedDetail ||
      selectedDetail.status !== 'INGESTED' ||
      isSavingViewQuality
    ) {
      return
    }
    setIsSavingViewQuality(true)
    try {
      const review = await recordQualityReview(projectId, selectedStream.clip_asset_id, {
        decision: selectedQualityDecision,
        reason_code: selectedQualityReason,
        expected_version: selectedReview?.version ?? 0,
      })
      setViewReviews((current) => ({ ...current, [selectedStream.clip_asset_id]: review }))
      void message.success(`相机 ${selectedStream.camera_number} 的 View Quality 已保存。`)
      await loadGroup()
    } catch (error) {
      void message.error(
        error instanceof Error ? error.message : 'View Quality 暂未保存，请稍后重试。',
      )
    } finally {
      setIsSavingViewQuality(false)
    }
  }

  async function choosePrimaryView(clipAssetId: string) {
    if (!task || primaryLocked || task.primary_clip_asset_id === clipAssetId || isSelectingPrimary)
      return
    setIsSelectingPrimary(true)
    try {
      const updatedTask = await selectAnnotationPrimaryView(
        projectId,
        task.annotation_task_id,
        clipAssetId,
      )
      setTask(updatedTask)
      void message.success('主视图已保存；正式标注时间轴将以该视图为准。')
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '主视图暂未保存，请刷新后重试。')
    } finally {
      setIsSelectingPrimary(false)
    }
  }

  async function submitRevision(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!task || !canSubmitRevision || isSubmittingRevision) return
    const errors = validateSegments(draftSegments)
    if (Object.keys(errors).length) {
      setSegmentErrors(errors)
      return
    }
    setSegmentErrors({})
    setIsSubmittingRevision(true)
    try {
      await submitAnnotationRevision(
        projectId,
        task.annotation_task_id,
        draftSegments.map((segment) => ({
          start_ms: segment.start_ms,
          end_ms: segment.end_ms,
          label: segment.label,
        })),
        annotationNote,
      )
      void message.success('配药动作修订已提交；Primary View 现在锁定。')
      await loadGroup()
    } catch (error) {
      void message.error(
        error instanceof AnnotationTaskApiError ? error.message : '修订暂未提交，请稍后重试。',
      )
    } finally {
      setIsSubmittingRevision(false)
    }
  }

  function updateSegment(draftId: number, field: keyof AnnotationSegment, value: string | number) {
    setDraftSegments((current) =>
      current.map((segment) =>
        segment.draftId === draftId ? { ...segment, [field]: value } : segment,
      ),
    )
  }

  const playbackAvailability = selectedStream
    ? selectedDetail
      ? selectedDetail.status === 'INGESTED'
        ? {
            title: '媒体已入库，可按需播放',
            description: '播放授权只在点击后获取；播放器仍只加载当前选中的一路视图。',
            type: 'success' as const,
          }
        : selectedDetail.status === 'INGEST_FAILED'
          ? {
              title: '媒体检测失败',
              description:
                '媒体 Probe 失败不会改变回执；若播放授权仍可用，可继续人工查看，但不能记录质量结论。',
              type: 'error' as const,
            }
          : {
              title: mediaStatusLabels[selectedDetail.status] ?? '媒体尚未就绪',
              description: '媒体事实尚未完成；当前不允许记录 View Quality。',
              type: 'warning' as const,
            }
      : {
          title: '正在读取当前视图',
          description: '读取完成后才会显示播放与媒体事实。',
          type: 'info' as const,
        }
    : {
        title: '选择一路已接收视图',
        description: '缺失相机保留在列表中，但不会加载播放器。',
        type: 'info' as const,
      }
  const canPlay = selectedDetail?.content_type === 'video/mp4'
  const currentCandidateTone = candidateTone(candidateState, candidateResponse)
  const selectedSync = selectedStream ? syncSafety(selectedStream) : null
  const currentGroupResult = groupQuality?.result ?? 'UNREVIEWED'
  const groupQualityReceiving = group?.receipt_state === 'RECEIVING'
  const groupQualitySaveDisabled =
    !groupQuality ||
    !groupReason.trim() ||
    isSavingGroupQuality ||
    (groupQualityReceiving && groupDecision !== 'NEEDS_REVIEW')
  const primaryOptions = viewRows.map((view) => {
    const decision = groupViewDecision(view, viewReviews)
    const enabled = Boolean(
      view.stream &&
        view.stream.stream_status === 'INGESTED' &&
        viewReviews[view.stream.clip_asset_id]?.decision === 'QUALIFIED',
    )
    return {
      value: view.stream?.clip_asset_id ?? `missing-${view.cameraNumber}`,
      label: `相机 ${view.cameraNumber} · ${view.member?.camera_role ?? '未命名'} · ${
        view.stream ? viewQualityLabels[decision] : '缺失'
      }`,
      disabled: !enabled,
    }
  })
  const annotationBlockingReason = !task
    ? 'Group Quality 通过后，服务端会自动创建唯一 CaptureGroup 任务。'
    : groupQualityReceiving
      ? '回执仍在接收中，不能进入组级标注准入。'
      : !groupQualityAdmitting
        ? '当前 Group Quality 为需复核或不合格；任务和历史仍保留，但不能提交新的 Revision。'
        : !task.primary_clip_asset_id
          ? '请选择一路已接收、媒体可用且 View Quality 合格的主视图。'
          : !task.can_submit
            ? '当前账号或任务状态不允许提交；请查看服务端任务状态。'
            : task.status !== 'ASSIGNED'
              ? `当前任务状态：${formatTaskStatus(task)}。`
              : ''

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑导航">
        <Link to={{ pathname: paths.captureGroups(projectId), search: location.search }}>
          CaptureGroup 多机位
        </Link>
        <span aria-hidden="true">/</span>
        <span>详情</span>
      </nav>
      <PageHeader
        className="capture-group-page-header capture-group-detail-header"
        eyebrow="V1.25 · CaptureGroup"
        titleId="capture-group-detail-title"
        title={group?.capture_group_key ?? 'CaptureGroup 详情'}
        description={
          group ? (
            <span className="capture-group-header-status">
              <StatusIndicator tone={receiptTone(group.receipt_state)}>
                回执 · {receiptStateLabels[group.receipt_state]}
              </StatusIndicator>
              <span>{group.capture_group_id}</span>
            </span>
          ) : undefined
        }
        actions={
          <>
            {group?.receipt_state === 'RECEIVING' ? (
              <Button
                size="small"
                loading={isFinalizingReceipt}
                onClick={() => void finalizeReceipt()}
              >
                关闭接收回执
              </Button>
            ) : null}
            <Button
              size="small"
              icon={<RefreshCw aria-hidden="true" size={14} strokeWidth={1.8} />}
              loading={groupLoadState === 'loading'}
              onClick={() => void loadGroup()}
            >
              刷新事实
            </Button>
          </>
        }
      />
      {groupLoadState === 'loading' ? (
        <LoadingState>{group ? '正在刷新 CaptureGroup…' : '正在读取 CaptureGroup…'}</LoadingState>
      ) : null}
      {groupLoadState === 'error' ? (
        <ErrorState code={groupErrorCode} onRetry={() => void loadGroup()}>
          {groupErrorMessage}
        </ErrorState>
      ) : null}
      {group ? (
        <>
          <section className="capture-group-overview" aria-label="CaptureGroup 回执摘要">
            <div className="capture-group-overview-card">
              <span className="app-eyebrow">Receipt</span>
              <strong>{receiptStateLabels[group.receipt_state]}</strong>
              <span>
                应 {group.expected_camera_count} 路 · 已收 {group.received_camera_numbers.length} 路
                · 缺失 {group.missing_camera_numbers.length} 路
              </span>
            </div>
            <div className="capture-group-overview-card">
              <span className="app-eyebrow">Group Quality</span>
              <StatusIndicator tone={groupQualityTone(currentGroupResult)}>
                {groupQualityLabels[currentGroupResult]}
              </StatusIndicator>
              <span>服务端结论；页面不计算组质量。</span>
            </div>
            <div className="capture-group-overview-card">
              <span className="app-eyebrow">Capture time</span>
              <strong>{formatDateTime(group.device_time_utc ?? group.created_at)}</strong>
              <span>{group.device_timezone ?? '时区未提供'}</span>
            </div>
          </section>
          {qualityErrorMessage ? (
            <Alert type="warning" showIcon title={qualityErrorMessage} />
          ) : null}
          {taskErrorMessage ? <Alert type="warning" showIcon title={taskErrorMessage} /> : null}
          <div className="capture-group-workspace">
            <div className="capture-group-main-column">
              <Section
                className="capture-group-views-section"
                title="Camera / View"
                titleId="capture-group-views-title"
                actions={
                  <span className="capture-group-section-note">
                    相机编号来自冻结 CameraSetSnapshot
                  </span>
                }
              >
                <div
                  className="capture-group-view-list"
                  role="list"
                  aria-label="CaptureGroup 相机视图列表"
                >
                  {viewRows.map((view) => {
                    const stream = view.stream
                    const selected = Boolean(stream && stream.clip_asset_id === selectedClipAssetId)
                    const decision = groupViewDecision(view, viewReviews)
                    const isRejected = decision === 'REJECTED'
                    return (
                      <article
                        className={`capture-group-view-row${selected ? ' is-selected' : ''}${isRejected ? ' is-rejected' : ''}`}
                        key={view.cameraNumber}
                        role="listitem"
                        aria-label={`相机 ${view.cameraNumber} · ${stream ? (mediaStatusLabels[stream.stream_status] ?? stream.stream_status) : '缺失'}`}
                      >
                        <button
                          className="capture-group-view-select"
                          type="button"
                          disabled={!stream}
                          aria-pressed={selected}
                          onClick={() => stream && setSelectedClipAssetId(stream.clip_asset_id)}
                        >
                          <span className="capture-group-camera-number">
                            Cam {view.cameraNumber}
                          </span>
                          <span className="capture-group-view-copy">
                            <strong>{view.member?.camera_role ?? '相机成员未提供'}</strong>
                            <span>{view.member?.mount_position ?? '安装位置未提供'}</span>
                          </span>
                        </button>
                        <div className="capture-group-view-facts">
                          <StatusIndicator tone={streamTone(stream)}>
                            媒体 ·{' '}
                            {stream
                              ? (mediaStatusLabels[stream.stream_status] ?? stream.stream_status)
                              : '缺失'}
                          </StatusIndicator>
                          <StatusIndicator tone={viewQualityTone(decision)}>
                            人工质量 · {viewQualityLabels[decision]}
                          </StatusIndicator>
                          {selected && stream ? (
                            <StatusIndicator tone={currentCandidateTone}>
                              AI Candidate ·{' '}
                              {candidateResponse?.job.hand_result === 'NO_HAND_DETECTED'
                                ? 'NO_HAND_DETECTED'
                                : candidateResponse?.job.status === 'FAILED' ||
                                    candidateState === 'error'
                                  ? '失败'
                                  : candidateState === 'success'
                                    ? '已读取'
                                    : '未读取'}
                            </StatusIndicator>
                          ) : null}
                          {stream?.quality_status ? (
                            <span className="capture-group-view-secondary-fact">
                              技术质量 ·{' '}
                              {technicalQualityLabels[stream.quality_status] ??
                                stream.quality_status}
                            </span>
                          ) : null}
                        </div>
                        <div className="capture-group-view-actions">
                          {isRejected ? (
                            <ShieldAlert aria-hidden="true" size={16} strokeWidth={1.8} />
                          ) : null}
                          <span>{selected ? '已聚焦' : stream ? '查看' : '缺失'}</span>
                        </div>
                      </article>
                    )
                  })}
                </div>
                {group.missing_camera_numbers.length ? (
                  <Alert
                    className="capture-group-missing-note"
                    type="warning"
                    showIcon
                    title={`缺失相机：${group.missing_camera_numbers.map((number) => `Cam ${number}`).join('、')}`}
                    description="缺失编号保留在原位置；不会被重新编号，也不会被浏览器当作质量结论。"
                  />
                ) : null}
              </Section>

              <section
                className="capture-group-focused-workspace"
                aria-labelledby="capture-group-focused-title"
              >
                <header className="capture-group-focused-heading">
                  <div>
                    <p className="app-eyebrow">Focused workspace</p>
                    <h2 id="capture-group-focused-title">
                      {selectedStream
                        ? `Cam ${selectedStream.camera_number} · ${selectedStream.camera_role}`
                        : '等待选择视图'}
                    </h2>
                  </div>
                  {selectedStream ? (
                    <StatusIndicator tone="information">
                      {task?.primary_clip_asset_id === selectedStream.clip_asset_id
                        ? 'Primary View'
                        : 'Supporting View'}
                    </StatusIndicator>
                  ) : null}
                </header>
                {selectedStream &&
                task?.primary_clip_asset_id &&
                task.primary_clip_asset_id !== selectedStream.clip_asset_id ? (
                  <Alert
                    className="capture-group-supporting-warning"
                    type="info"
                    showIcon
                    title="当前为 Supporting View"
                    description="可用于人工视觉对照；正式 Annotation timeline 仍以 Primary View 为时间基准。"
                  />
                ) : null}
                <PlaybackCard
                  availability={playbackAvailability}
                  candidateOverlay={candidateResponse?.candidates ?? []}
                  canPlay={Boolean(canPlay)}
                  canRecoverMissingMediaProbe={false}
                  grant={playbackGrant}
                  isRecoveringMediaProbe={false}
                  isRequestingPlayback={isRequestingPlayback}
                  mediaProbeMessage={
                    selectedDetail?.status === 'INGEST_FAILED'
                      ? 'Media Probe failure 是媒体事实；不改变 Receipt，也不自动判定 View Quality。'
                      : ''
                  }
                  playbackMessage={playbackMessage}
                  playbackState={playbackState}
                  onClose={resetPlayback}
                  onLoadedMetadata={() => {
                    setPlaybackState('ready')
                    setPlaybackMessage('')
                  }}
                  onPlaybackError={() => {
                    setPlaybackState('error')
                    setPlaybackMessage('视频加载失败，请重新获取播放授权后重试。')
                  }}
                  onRecoverMissingMediaProbe={() => undefined}
                  onStartPlayback={() => void startPlayback()}
                />
                {selectedStream ? (
                  <div
                    className="capture-group-compare-strip"
                    aria-label="Supporting View 相机切换"
                  >
                    <div className="capture-group-compare-heading">
                      <span className="app-eyebrow">Camera switcher</span>
                      <span>只切换当前主播放器，不预加载其他完整视频。</span>
                    </div>
                    <div className="capture-group-compare-list">
                      {viewRows.map((view) => {
                        const stream = view.stream
                        const isCurrent = stream?.clip_asset_id === selectedStream.clip_asset_id
                        const decision = groupViewDecision(view, viewReviews)
                        return (
                          <button
                            className={`capture-group-compare-item${isCurrent ? ' is-current' : ''}`}
                            type="button"
                            key={view.cameraNumber}
                            disabled={!stream}
                            aria-pressed={isCurrent}
                            onClick={() => stream && setSelectedClipAssetId(stream.clip_asset_id)}
                          >
                            <span className="capture-group-compare-thumbnail" aria-hidden="true">
                              <Video size={16} strokeWidth={1.7} />
                            </span>
                            <span>
                              <strong>Cam {view.cameraNumber}</strong>
                              <small>
                                {stream ? viewQualityLabels[decision] : '缺失'}
                                {stream && stream.stream_status !== 'INGESTED'
                                  ? ' · 媒体不可用'
                                  : ''}
                              </small>
                            </span>
                          </button>
                        )
                      })}
                    </div>
                  </div>
                ) : null}
                {selectedSync ? (
                  <div className="capture-group-sync-note" role="status">
                    <Clock3 aria-hidden="true" size={16} strokeWidth={1.8} />
                    <div>
                      <StatusIndicator tone={selectedSync.tone}>
                        {selectedSync.label}
                      </StatusIndicator>
                      <p>{selectedSync.detail}</p>
                    </div>
                  </div>
                ) : null}
                {selectedDetail ? (
                  <div className="capture-group-focused-facts">
                    <Section title="AI Candidate facts" titleId="capture-group-candidate-title">
                      <div className="capture-group-candidate-facts">
                        <StatusIndicator tone={currentCandidateTone}>
                          {candidateResponse?.job.hand_result === 'NO_HAND_DETECTED'
                            ? 'NO_HAND_DETECTED'
                            : candidateResponse?.job.status === 'FAILED' ||
                                candidateState === 'error'
                              ? 'AI FAILED'
                              : candidateResponse?.job.status === 'SUCCEEDED'
                                ? 'Candidate ready'
                                : candidateState === 'unavailable'
                                  ? '尚无 Candidate'
                                  : '读取中'}
                        </StatusIndicator>
                        <p>{candidateDescription(candidateState, candidateResponse)}</p>
                        {candidateResponse?.job.run_summary ? (
                          <Descriptions
                            column={{ xs: 1, sm: 2 }}
                            items={[
                              {
                                key: 'frames',
                                label: '分析帧数',
                                children: candidateResponse.job.run_summary.inference_frame_count,
                              },
                              {
                                key: 'hands',
                                label: '发现手部帧',
                                children: candidateResponse.job.run_summary.frames_with_hand,
                              },
                              {
                                key: 'left',
                                label: '左手候选',
                                children:
                                  candidateResponse.job.run_summary.left_hand_detection_count,
                              },
                              {
                                key: 'right',
                                label: '右手候选',
                                children:
                                  candidateResponse.job.run_summary.right_hand_detection_count,
                              },
                              ...(candidateResponse.job.run_summary.failure_reason
                                ? [
                                    {
                                      key: 'failure',
                                      label: '失败原因',
                                      children: candidateResponse.job.run_summary.failure_reason,
                                    },
                                  ]
                                : []),
                            ]}
                          />
                        ) : null}
                      </div>
                    </Section>
                    <MediaReportCard
                      detail={selectedDetail}
                      errorCode={
                        selectedMediaReportState === 'error' ? 'MEDIA_REPORT_UNAVAILABLE' : ''
                      }
                      errorMessage={selectedMediaReportError}
                      loadState={selectedMediaReportState}
                      onRetry={() => void loadSelectedView()}
                      report={selectedMediaReport}
                    />
                  </div>
                ) : null}
              </section>

              <Section
                className="capture-group-view-quality-workspace"
                title="View Quality"
                titleId="capture-group-view-quality-title"
                actions={
                  <span className="capture-group-section-note">自动证据与人工决定分开保存</span>
                }
              >
                <div className="capture-group-quality-boundary">
                  <div>
                    <strong>Automatic evidence</strong>
                    <span>Media Probe、技术质量和 MediaPipe Candidate 只作为事实展示。</span>
                  </div>
                  <div>
                    <strong>Human decision</strong>
                    <span>QUALIFIED / NEEDS_REVIEW / REJECTED 由人工记录，REJECTED 仍保留。</span>
                  </div>
                </div>
                {selectedStream && selectedDetail ? (
                  <QualityReviewCard
                    assetStatus={selectedDetail.status}
                    canReview={selectedDetail.status === 'INGESTED'}
                    errorCode={
                      selectedQualityLoadState === 'error' ? 'VIEW_QUALITY_REQUEST_FAILED' : ''
                    }
                    errorMessage={selectedQualityError || '当前 View Quality 暂时无法读取。'}
                    isSaving={isSavingViewQuality}
                    loadState={selectedQualityLoadState}
                    review={selectedReview}
                    selectedDecision={selectedQualityDecision}
                    selectedReason={selectedQualityReason}
                    onSave={() => void saveViewQuality()}
                    onSelectionChange={(decision, reason) => {
                      setSelectedQualityDecision(decision)
                      setSelectedQualityReason(reason)
                    }}
                  />
                ) : (
                  <p className="form-hint">选择一路已接收视图后记录人工 View Quality。</p>
                )}
              </Section>

              <Section
                className="capture-group-annotation-section"
                id="capture-group-annotation"
                title="CaptureGroup Annotation"
                titleId="capture-group-annotation-title"
                actions={
                  <span className="capture-group-section-note">一个组任务 · 一条 Revision 链</span>
                }
              >
                {task ? (
                  <>
                    <div className="capture-group-task-banner">
                      <div>
                        <StatusIndicator tone={taskStatusTone(task)}>
                          唯一 CaptureGroup task · {formatTaskStatus(task)}
                        </StatusIndicator>
                        <span>执行人：{task.assignee_actor_id}</span>
                      </div>
                      <Link
                        className="text-link"
                        to={paths.annotationTask(projectId, task.annotation_task_id)}
                      >
                        Review / Cleaning 上下文
                      </Link>
                    </div>
                    {groupQualityDowngraded ? (
                      <Alert
                        type="warning"
                        showIcon
                        title={`当前 Group Quality 为“${groupQualityLabels[currentGroupResult]}”`}
                        description="任务和既有 Revision 历史仍可审计；新 Revision submission 已阻止。恢复组质量准入后再继续。"
                      />
                    ) : null}
                    <div className="capture-group-primary-view-control">
                      <div>
                        <strong>Primary View · 正式时间基准</strong>
                        <p>
                          {primaryLocked
                            ? '首个正式 Revision 已存在；Primary View 已锁定。'
                            : '在首个正式 Revision 前选择一路符合服务端条件的视图。'}
                        </p>
                      </div>
                      <Select
                        aria-label="Primary View"
                        value={task.primary_clip_asset_id ?? undefined}
                        placeholder="选择主视图"
                        disabled={primaryLocked || isSelectingPrimary || !groupQualityAdmitting}
                        loading={isSelectingPrimary}
                        options={primaryOptions}
                        onChange={(value) => void choosePrimaryView(value)}
                      />
                      {primaryLocked ? (
                        <LockKeyhole aria-label="Primary View 已锁定" size={17} />
                      ) : null}
                    </div>
                    {annotationBlockingReason ? (
                      <p className="capture-group-annotation-blocking" role="status">
                        <CircleAlert aria-hidden="true" size={16} strokeWidth={1.8} />
                        {annotationBlockingReason}
                      </p>
                    ) : null}
                    {task.status === 'ASSIGNED' ? (
                      <form
                        className="capture-group-annotation-form"
                        onSubmit={(event) => void submitRevision(event)}
                      >
                        <div className="capture-group-form-heading">
                          <div>
                            <span className="app-eyebrow">Formal annotation timeline</span>
                            <h3>配药动作时间段</h3>
                          </div>
                          <span>所有相机共享这一条组级 Revision 链，不创建按相机重复的标签。</span>
                        </div>
                        {draftSegments.map((segment, index) => {
                          const errors = segmentErrors[segment.draftId] ?? {}
                          return (
                            <div className="capture-group-segment-row" key={segment.draftId}>
                              <label>
                                <span>开始（毫秒）</span>
                                <input
                                  type="number"
                                  min={0}
                                  value={segment.start_ms}
                                  disabled={!canSubmitRevision}
                                  onChange={(event) =>
                                    updateSegment(
                                      segment.draftId,
                                      'start_ms',
                                      Number(event.target.value),
                                    )
                                  }
                                />
                                {errors.start ? <small>{errors.start}</small> : null}
                              </label>
                              <label>
                                <span>结束（毫秒）</span>
                                <input
                                  type="number"
                                  min={1}
                                  value={segment.end_ms}
                                  disabled={!canSubmitRevision}
                                  onChange={(event) =>
                                    updateSegment(
                                      segment.draftId,
                                      'end_ms',
                                      Number(event.target.value),
                                    )
                                  }
                                />
                                {errors.end ? <small>{errors.end}</small> : null}
                              </label>
                              <label>
                                <span>动作标签</span>
                                <Select
                                  aria-label={`第 ${index + 1} 个时间段动作标签`}
                                  value={segment.label || undefined}
                                  placeholder="选择标签"
                                  disabled={!canSubmitRevision || !sortedLabels.length}
                                  options={sortedLabels.map((label) => ({
                                    value: label.label_id,
                                    label: label.display_name,
                                  }))}
                                  onChange={(value) =>
                                    updateSegment(segment.draftId, 'label', value)
                                  }
                                />
                                {errors.label ? <small>{errors.label}</small> : null}
                              </label>
                              {draftSegments.length > 1 ? (
                                <Button
                                  type="text"
                                  danger
                                  disabled={!canSubmitRevision}
                                  onClick={() =>
                                    setDraftSegments((current) =>
                                      current.filter(
                                        (candidate) => candidate.draftId !== segment.draftId,
                                      ),
                                    )
                                  }
                                >
                                  删除
                                </Button>
                              ) : null}
                            </div>
                          )
                        })}
                        <div className="capture-group-annotation-form-actions">
                          <Button
                            type="text"
                            disabled={!canSubmitRevision}
                            onClick={() =>
                              setDraftSegments((current) => [...current, createDraftSegment()])
                            }
                          >
                            添加时间段
                          </Button>
                          <label className="capture-group-note-field">
                            <span>修订备注（可选）</span>
                            <Input.TextArea
                              value={annotationNote}
                              disabled={!canSubmitRevision}
                              maxLength={1000}
                              autoSize={{ minRows: 2, maxRows: 4 }}
                              onChange={(event) => setAnnotationNote(event.target.value)}
                            />
                          </label>
                          <Button
                            type="primary"
                            htmlType="submit"
                            loading={isSubmittingRevision}
                            disabled={!canSubmitRevision}
                          >
                            提交 Revision
                          </Button>
                        </div>
                      </form>
                    ) : groupQualityDowngraded ? (
                      <div className="capture-group-annotation-form capture-group-annotation-form-blocked">
                        <div className="capture-group-form-heading">
                          <div>
                            <span className="app-eyebrow">Revision submission</span>
                            <h3>新 Revision 暂不可提交</h3>
                          </div>
                          <span>
                            保留既有 Revision、Review 和 Cleaning 历史；等待服务端恢复组级准入。
                          </span>
                        </div>
                        <div className="capture-group-annotation-form-actions">
                          <Button type="primary" disabled>
                            提交 Revision
                          </Button>
                        </div>
                      </div>
                    ) : null}
                    {task.annotation_revision ? (
                      <div
                        className="capture-group-revision-history"
                        aria-label="Annotation Revision 历史"
                      >
                        <div className="capture-group-form-heading">
                          <div>
                            <span className="app-eyebrow">Revision history</span>
                            <h3>第 {task.annotation_revision.revision_number} 次正式修订</h3>
                          </div>
                          <StatusIndicator
                            tone={
                              task.annotation_revision.review
                                ? task.annotation_revision.review.decision === 'APPROVED'
                                  ? 'success'
                                  : 'destructive'
                                : 'information'
                            }
                          >
                            {task.annotation_revision.review
                              ? task.annotation_revision.review.decision === 'APPROVED'
                                ? 'Review · 已通过'
                                : 'Review · 已退回'
                              : 'Review · 待复核'}
                          </StatusIndicator>
                        </div>
                        <ol className="capture-group-readonly-segments">
                          {task.annotation_revision.annotation_document.segments.map(
                            (segment, index) => (
                              <li key={`${segment.start_ms}-${segment.end_ms}-${segment.label}`}>
                                <span>时间段 {index + 1}</span>
                                <strong>
                                  {segment.start_ms}–{segment.end_ms} 毫秒
                                </strong>
                                <span>
                                  {schema?.labels.find((label) => label.label_id === segment.label)
                                    ?.display_name ?? segment.label}
                                </span>
                              </li>
                            ),
                          )}
                        </ol>
                        {task.annotation_revision.annotation_document.note ? (
                          <p className="form-hint">
                            修订备注：{task.annotation_revision.annotation_document.note}
                          </p>
                        ) : null}
                      </div>
                    ) : null}
                  </>
                ) : (
                  <p className="form-hint">{annotationBlockingReason}</p>
                )}
              </Section>
            </div>

            <aside className="capture-group-inspector" aria-label="CaptureGroup Inspector">
              <InspectorSection
                title="Receipt / capture facts"
                titleId="capture-group-receipt-facts"
              >
                <MetadataList
                  ariaLabel="CaptureGroup 接收事实"
                  items={[
                    {
                      id: 'group-id',
                      label: 'CaptureGroup ID',
                      value: <code>{group.capture_group_id}</code>,
                    },
                    {
                      id: 'capture-key',
                      label: '归组键',
                      value: group.capture_group_key ?? '未提供',
                    },
                    {
                      id: 'key-source',
                      label: '归组来源',
                      value: group.capture_group_key_source ?? '未提供',
                    },
                    {
                      id: 'capture-time',
                      label: '设备采集时间',
                      value: formatDateTime(group.device_time_utc),
                    },
                    {
                      id: 'receive-time',
                      label: '服务端接收时间',
                      value: formatDateTime(group.server_receive_time),
                    },
                    { id: 'batch', label: '上传批次', value: <code>{group.batch_id}</code> },
                    { id: 'device', label: '设备', value: group.device_id },
                  ]}
                />
                <Descriptions
                  column={1}
                  items={[
                    {
                      key: 'expected',
                      label: '相机集合',
                      children: `应 ${group.expected_camera_numbers.map((number) => `Cam ${number}`).join('、')}`,
                    },
                    {
                      key: 'received',
                      label: '已接收',
                      children: group.received_camera_numbers.length
                        ? group.received_camera_numbers.map((number) => `Cam ${number}`).join('、')
                        : '暂无',
                    },
                    {
                      key: 'missing',
                      label: '缺失',
                      children: group.missing_camera_numbers.length
                        ? group.missing_camera_numbers.map((number) => `Cam ${number}`).join('、')
                        : '无',
                    },
                  ]}
                />
              </InspectorSection>

              <InspectorSection title="Group Quality" titleId="capture-group-quality-inspector">
                <div className="capture-group-inspector-status">
                  <StatusIndicator tone={groupQualityTone(currentGroupResult)}>
                    {groupQualityLabels[currentGroupResult]}
                  </StatusIndicator>
                  {groupQuality?.reviewer_actor_id ? (
                    <span>记录人：{groupQuality.reviewer_actor_id}</span>
                  ) : null}
                  {groupQuality?.decided_at ? (
                    <span>{formatDateTime(groupQuality.decided_at)}</span>
                  ) : null}
                </div>
                {groupQuality?.reason ? (
                  <p className="form-hint">理由：{groupQuality.reason}</p>
                ) : null}
                {groupQualityReceiving ? (
                  <Alert
                    type="info"
                    showIcon
                    title="回执仍在接收中"
                    description="最终 QUALIFIED / QUALIFIED_WITH_EXCLUSIONS 操作已禁用；服务器只接受 NEEDS_REVIEW。"
                  />
                ) : null}
                <label className="capture-group-decision-field">
                  <span>记录新的组质量结论</span>
                  <Select
                    aria-label="Group Quality 结论"
                    value={groupDecision}
                    options={[
                      {
                        value: 'QUALIFIED',
                        label: groupQualityLabels.QUALIFIED,
                        disabled: groupQualityReceiving,
                      },
                      {
                        value: 'QUALIFIED_WITH_EXCLUSIONS',
                        label: groupQualityLabels.QUALIFIED_WITH_EXCLUSIONS,
                        disabled: groupQualityReceiving,
                      },
                      { value: 'NEEDS_REVIEW', label: groupQualityLabels.NEEDS_REVIEW },
                      {
                        value: 'REJECTED',
                        label: groupQualityLabels.REJECTED,
                        disabled: groupQualityReceiving,
                      },
                    ]}
                    onChange={(value) =>
                      setGroupDecision(value as CaptureGroupQualityCommandResult)
                    }
                  />
                </label>
                <label className="capture-group-decision-field">
                  <span>结论理由</span>
                  <Input.TextArea
                    value={groupReason}
                    maxLength={1000}
                    autoSize={{ minRows: 2, maxRows: 4 }}
                    onChange={(event) => setGroupReason(event.target.value)}
                  />
                </label>
                <Button
                  type="primary"
                  loading={isSavingGroupQuality}
                  disabled={groupQualitySaveDisabled}
                  onClick={() => void saveGroupQuality()}
                >
                  保存 Group Quality
                </Button>
                {groupQuality?.confirmed_view_set ? (
                  <div className="capture-group-confirmed-view-set">
                    <strong>本次结论使用的 View Set</strong>
                    <span>
                      {groupQuality.confirmed_view_set.views.length} 路 · input version{' '}
                      {groupQuality.input_version ?? '未提供'}
                    </span>
                    <ul>
                      {groupQuality.confirmed_view_set.views.map((view) => (
                        <li key={view.camera_number}>
                          Cam {view.camera_number} ·{' '}
                          {viewQualityLabels[view.quality_review_decision]}
                          {view.quality_review_decision === 'REJECTED' ? ' · 已排除但保留' : ''}
                        </li>
                      ))}
                    </ul>
                  </div>
                ) : null}
                {qualityHistory.length ? (
                  <details className="capture-group-quality-history">
                    <summary>查看追加质量历史（{qualityHistory.length} 条）</summary>
                    <ol>
                      {qualityHistory.map((entry) => (
                        <li
                          key={
                            entry.capture_group_quality_review_id ?? `revision-${entry.revision}`
                          }
                        >
                          <StatusIndicator tone={groupQualityTone(entry.result)}>
                            第 {entry.revision} 次 · {groupQualityLabels[entry.result]}
                          </StatusIndicator>
                          <span>{entry.reason ?? '未提供理由'}</span>
                          <small>{formatDateTime(entry.decided_at)}</small>
                        </li>
                      ))}
                    </ol>
                  </details>
                ) : null}
              </InspectorSection>

              <InspectorSection
                title="Selected View facts"
                titleId="capture-group-selected-view-facts"
              >
                {selectedStream ? (
                  <MetadataList
                    ariaLabel="当前视图事实"
                    items={[
                      {
                        id: 'camera',
                        label: '相机',
                        value: `Cam ${selectedStream.camera_number} · ${selectedStream.camera_role}`,
                      },
                      { id: 'mount', label: '安装位置', value: selectedStream.mount_position },
                      {
                        id: 'asset',
                        label: 'ClipAsset',
                        value: <code>{selectedStream.clip_asset_id}</code>,
                      },
                      {
                        id: 'record-start',
                        label: '拍摄开始',
                        value: formatDateTime(selectedStream.record_start_utc),
                      },
                      {
                        id: 'stream-status',
                        label: '媒体流',
                        value:
                          mediaStatusLabels[selectedStream.stream_status] ??
                          selectedStream.stream_status,
                      },
                      {
                        id: 'technical-quality',
                        label: '技术质量',
                        value:
                          technicalQualityLabels[selectedStream.quality_status] ??
                          selectedStream.quality_status,
                      },
                      {
                        id: 'hand-enabled',
                        label: '手部检测',
                        value: selectedStream.enabled_for_hand_detection ? '已启用' : '未启用',
                      },
                    ]}
                  />
                ) : (
                  <p className="form-hint">当前没有选中的已接收视图。</p>
                )}
                {selectedSync ? (
                  <div className="capture-group-sync-inspector">
                    <strong>Sync evidence</strong>
                    <StatusIndicator tone={selectedSync.tone}>{selectedSync.label}</StatusIndicator>
                    <p>{selectedSync.detail}</p>
                    <span>
                      边端声明偏移：{selectedStream?.time_offset_ms ?? 0} ms（不等同于 validated
                      mapping）
                    </span>
                  </div>
                ) : null}
              </InspectorSection>

              <InspectorSection title="Review / Cleaning" titleId="review-cleaning-context">
                {task?.annotation_revision ? (
                  <>
                    <StatusIndicator
                      tone={
                        task.annotation_revision.review
                          ? task.annotation_revision.review.decision === 'APPROVED'
                            ? 'success'
                            : 'destructive'
                          : 'information'
                      }
                    >
                      Review ·{' '}
                      {task.annotation_revision.review
                        ? task.annotation_revision.review.decision === 'APPROVED'
                          ? '已通过'
                          : '已退回'
                        : '待复核'}
                    </StatusIndicator>
                    {task.annotation_revision.review?.review_note ? (
                      <p className="form-hint">
                        复核意见：{task.annotation_revision.review.review_note}
                      </p>
                    ) : null}
                    {task.annotation_revision.capture_group_cleaning ? (
                      <div className="capture-group-cleaning-fact">
                        <StatusBadge
                          tone={cleaningTone(
                            task.annotation_revision.capture_group_cleaning.outcome,
                          )}
                        >
                          Cleaning ·{' '}
                          {cleaningLabel(task.annotation_revision.capture_group_cleaning.outcome)}
                        </StatusBadge>
                        <span>
                          {formatDateTime(
                            task.annotation_revision.capture_group_cleaning.evaluated_at,
                          )}
                        </span>
                        {task.annotation_revision.capture_group_cleaning.reason_codes.length ? (
                          <p className="form-hint">
                            原因：
                            {task.annotation_revision.capture_group_cleaning.reason_codes.join(
                              '；',
                            )}
                          </p>
                        ) : null}
                      </div>
                    ) : task.annotation_revision.post_cleaning_result ? (
                      <div className="capture-group-cleaning-fact">
                        <StatusBadge
                          tone={cleaningTone(task.annotation_revision.post_cleaning_result.outcome)}
                        >
                          Cleaning ·{' '}
                          {cleaningLabel(task.annotation_revision.post_cleaning_result.outcome)}
                        </StatusBadge>
                        <span>
                          {formatDateTime(
                            task.annotation_revision.post_cleaning_result.evaluated_at,
                          )}
                        </span>
                      </div>
                    ) : (
                      <p className="form-hint">Review 通过后才会形成 Cleaning 事实。</p>
                    )}
                  </>
                ) : (
                  <p className="form-hint">尚无正式 Revision；Review / Cleaning 尚未开始。</p>
                )}
                {task ? (
                  <Link
                    className="text-link"
                    to={paths.annotationTask(projectId, task.annotation_task_id)}
                  >
                    打开现有任务上下文
                  </Link>
                ) : null}
              </InspectorSection>

              <InspectorSection title="Next action" titleId="capture-group-next-action">
                <div className="capture-group-next-action">
                  <Check aria-hidden="true" size={16} strokeWidth={1.8} />
                  <strong>
                    {group.receipt_state === 'RECEIVING'
                      ? '等待回执关闭'
                      : !groupQuality || currentGroupResult === 'UNREVIEWED'
                        ? '记录 Group Quality'
                        : groupQualityDowngraded
                          ? '先恢复组质量准入'
                          : task && !task.primary_clip_asset_id
                            ? '选择 Primary View'
                            : task?.status === 'SUBMITTED'
                              ? '等待 Review'
                              : task?.annotation_revision?.review?.decision === 'APPROVED'
                                ? '查看 Cleaning 事实'
                                : '继续 CaptureGroup Annotation'}
                  </strong>
                  <span>下一步只依据服务端返回的 Receipt、Quality 和 Task 状态。</span>
                </div>
              </InspectorSection>
            </aside>
          </div>
        </>
      ) : null}
    </>
  )
}

function cleaningLabel(outcome: 'PASS' | 'WARNING' | 'REJECT') {
  if (outcome === 'PASS') return '已通过'
  if (outcome === 'WARNING') return '需处理'
  return '未通过'
}

function cleaningTone(outcome: 'PASS' | 'WARNING' | 'REJECT'): StatusBadgeTone {
  if (outcome === 'PASS') return 'success'
  return outcome === 'WARNING' ? 'warning' : 'error'
}
