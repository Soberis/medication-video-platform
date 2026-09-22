import { App as AntdApp, Alert, Button, Input, Tooltip } from 'antd'
import { Check, ChevronDown, ChevronUp, CircleAlert, Plus } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'

import {
  dispatchActionCandidateJob,
  fetchActionCandidateJob,
  fetchActionCandidates,
  type ActionCandidate,
  type ActionCandidateJob,
} from '@/api/action-candidate'
import {
  assignCaptureGroupHumanLabelTask,
  updateAnnotationCameraView,
  submitAnnotationRevision,
  type AnnotationCorrespondence,
  type AnnotationLabelSchema,
  type GroupAnnotationDocument,
  type AnnotationSegment,
  type AnnotationTask,
} from '@/api/annotation'
import { type CaptureGroup, type CaptureGroupQualityReview } from '@/api/capture-groups'
import type { QualityReview } from '@/api/quality'
import { StatusBadge, type StatusBadgeTone } from '@/components/StatusBadge'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { Section } from '@/components/workspace/Section'
import { AnnotationSegmentRow } from '@/features/capture-group/AnnotationSegmentRow'
import { AnnotationTimelineBar } from '@/features/capture-group/AnnotationTimelineBar'
import { CameraExclusionModal } from '@/features/capture-group/CameraExclusionModal'
import {
  createDraftSegment,
  currentGroupQualityAdmitting,
  formatAnnotationTime,
  formatTaskStatus,
  getActionDisplayName,
  initialDraftSegments,
  validActionCandidates,
  sortedSchemaLabels,
  taskStatusTone,
  validateSegments,
  type DraftAnnotationSegment,
  type GroupViewRow,
  type SegmentValidationErrors,
} from '@/features/capture-group/capture-group-detail-model'
import { groupQualityLabels } from '@/features/capture-group-presentation'
import type { CaptureGroupDetailRefreshOptions } from '@/features/capture-group/use-capture-group-detail-data'

interface CaptureGroupAnnotationPanelProps {
  projectId: string
  captureGroupId: string
  group: CaptureGroup
  groupQuality: CaptureGroupQualityReview | null
  task: AnnotationTask | null
  schema: AnnotationLabelSchema | null
  viewRows: GroupViewRow[]
  viewReviews?: Record<string, QualityReview>
  activeCameraNumber: number | null
  activeClipAssetId: string | null
  assigneeActorId?: string
  currentPlaybackTimeMs: number | null
  totalDurationMs?: number | null
  hasNoHandDetected?: boolean
  onPlaySegment?: (startMs: number, endMs: number) => void
  onPlayAtMs?: (timeMs: number) => void
  onSeekToMs?: (timeMs: number) => void
  onTaskChange: (task: AnnotationTask) => void
  onDirtyChange?: (dirty: boolean) => void
  onRefresh: (options?: CaptureGroupDetailRefreshOptions) => Promise<boolean>
  canPrepareTask?: boolean
}

interface CameraDraftState {
  segments: DraftAnnotationSegment[]
  note: string
  dirty: boolean
  context: string
}

function hasOverlapWith(
  seg: DraftAnnotationSegment,
  allSegments: DraftAnnotationSegment[],
): boolean {
  if (
    !Number.isFinite(seg.start_ms) ||
    !Number.isFinite(seg.end_ms) ||
    seg.start_ms >= seg.end_ms
  ) {
    return false
  }
  return allSegments.some(
    (other) =>
      other.draftId !== seg.draftId &&
      Number.isFinite(other.start_ms) &&
      Number.isFinite(other.end_ms) &&
      other.start_ms < other.end_ms &&
      seg.start_ms < other.end_ms &&
      seg.end_ms > other.start_ms,
  )
}

const AUTO_COLLAPSE_SEGMENT_THRESHOLD = 8

function ReadonlyGroupRevision({
  document,
  schema,
}: {
  document: GroupAnnotationDocument
  schema: AnnotationLabelSchema | null
}) {
  return (
    <ol className="capture-group-readonly-segments">
      {document.camera_views.flatMap((view) => {
        const viewSummary = (
          <li key={`camera-${view.camera_number}`}>
            <span>机位 {view.camera_number}</span>
            <div className="capture-group-readonly-time">
              <strong>{view.included ? '已提交' : '已排除'}</strong>
              {view.exclusion_reason ? <small>原因：{view.exclusion_reason}</small> : null}
            </div>
            <span>{view.segments.length ? `${view.segments.length} 段动作` : '无动作片段'}</span>
          </li>
        )
        const segmentRows = view.segments.map((segment, index) => {
          const correspondence = document.correspondences.find(
            (item) => item.correspondence_id === segment.correspondence_id,
          )
          const correspondenceLabel = correspondence
            ? correspondence.status === 'CONFIRMED'
              ? '已确认跨机位对应'
              : `对应不确定${correspondence.reason ? `：${correspondence.reason}` : ''}`
            : '缺少对应关系记录'
          const formattedStart = formatAnnotationTime(segment.start_ms)
          const formattedEnd = formatAnnotationTime(segment.end_ms)
          return (
            <li key={`${view.camera_number}-${segment.segment_id}`}>
              <span>
                机位 {view.camera_number} · 时间段 {index + 1}
              </span>
              <div className="capture-group-readonly-time">
                <strong>
                  {formattedStart && formattedEnd
                    ? `${formattedStart} → ${formattedEnd}`
                    : '时间暂不可用'}
                </strong>
                <small>
                  原始值：{segment.start_ms}–{segment.end_ms} 毫秒（本机位时间轴）
                </small>
              </div>
              <span>
                {getActionDisplayName(segment.label, schema)}
                <small>{correspondenceLabel}</small>
              </span>
            </li>
          )
        })
        return [viewSummary, ...segmentRows]
      })}
    </ol>
  )
}

export function CaptureGroupAnnotationPanel({
  projectId,
  captureGroupId,
  group,
  groupQuality,
  task,
  schema,
  viewRows,
  assigneeActorId = '',
  currentPlaybackTimeMs,
  totalDurationMs,
  hasNoHandDetected,
  onPlaySegment,
  onPlayAtMs,
  onSeekToMs,
  onDirtyChange,
  onTaskChange,
  onRefresh,
  canPrepareTask = false,
  viewReviews,
  activeCameraNumber,
  activeClipAssetId,
}: CaptureGroupAnnotationPanelProps) {
  const { message } = AntdApp.useApp()
  const [cameraDrafts, setCameraDrafts] = useState<Record<string, CameraDraftState>>({})
  const [segmentErrors, setSegmentErrors] = useState<Record<number, SegmentValidationErrors>>({})
  const [isSubmittingRevision, setIsSubmittingRevision] = useState(false)
  const [isRetryingAction, setIsRetryingAction] = useState(false)

  async function handleRetryActionJob() {
    setIsRetryingAction(true)
    try {
      await dispatchActionCandidateJob(projectId, captureGroupId)
      await onRefresh()
      void message.success('已重新发起动作候选分析。')
    } catch (err) {
      void message.error(err instanceof Error ? err.message : '重试动作分析失败，请重试。')
    } finally {
      setIsRetryingAction(false)
    }
  }
  const [actionCandidates, setActionCandidates] = useState<ActionCandidate[]>([])
  const [actionJob, setActionJob] = useState<ActionCandidateJob | null>(null)
  const [actionReadFailed, setActionReadFailed] = useState(false)
  const [loadedActionContext, setLoadedActionContext] = useState<string | null>(null)
  const [correspondenceReason, setCorrespondenceReason] = useState('')
  const annotationFormRef = useRef<HTMLFormElement | null>(null)
  const [isAllCollapsed, setIsAllCollapsed] = useState(false)
  const [collapsedSegments, setCollapsedSegments] = useState<Record<number, boolean>>({})
  const autoCollapsedDraftContext = useRef<string | null>(null)
  const [isExcludeModalOpen, setIsExcludeModalOpen] = useState(false)
  const [excludeModalReason, setExcludeModalReason] = useState('')

  function buildIndependentCorrespondences(): AnnotationCorrespondence[] {
    const submittedViews = cameraViews.filter((view) => view.state === 'SUBMITTED')
    const reason =
      submittedViews.length === 1 ? '仅一个参与机位，无跨机位对应' : correspondenceReason.trim()
    return submittedViews.flatMap((view) =>
      view.segments.map((segment) => ({
        correspondence_id: crypto.randomUUID(),
        status: 'UNCERTAIN' as const,
        reason,
        segments: [{ camera_number: view.camera_number, segment_id: segment.segment_id }],
      })),
    )
  }

  function isSegmentCollapsed(draftId: number) {
    if (collapsedSegments[draftId] !== undefined) {
      return collapsedSegments[draftId]
    }
    return isAllCollapsed
  }

  function toggleSegmentCollapsed(draftId: number) {
    setCollapsedSegments((prev) => ({
      ...prev,
      [draftId]: !isSegmentCollapsed(draftId),
    }))
  }

  function handleToggleAllCollapse() {
    setIsAllCollapsed((prev) => {
      const next = !prev
      setCollapsedSegments({})
      return next
    })
  }

  const activeCameraView = task?.camera_views?.find(
    (view) => view.camera_number === activeCameraNumber,
  )
  const actionQueryContext = `${projectId}:${captureGroupId}:${activeClipAssetId ?? 'no-clip'}`
  const currentActionCandidates = useMemo(
    () => (actionQueryContext === loadedActionContext ? actionCandidates : []),
    [actionCandidates, actionQueryContext, loadedActionContext],
  )
  const currentActionJob = actionQueryContext === loadedActionContext ? actionJob : null
  const currentActionReadFailed = actionQueryContext === loadedActionContext && actionReadFailed
  const activeCameraKey = activeCameraNumber === null ? 'unselected' : String(activeCameraNumber)
  const fallbackContext = useMemo(
    () => ({
      projectId,
      captureGroupId,
      activeCameraNumber,
      activeClipAssetId,
    }),
    [projectId, captureGroupId, activeCameraNumber, activeClipAssetId],
  )

  const draftContext = [
    task?.annotation_task_id ?? 'no-task',
    task?.group_input_version ?? 'v1',
    activeCameraNumber ?? 'no-camera',
    activeClipAssetId ?? 'no-clip',
    task?.label_schema_id ?? schema?.label_schema_id ?? 'no-schema',
    task?.label_schema_digest ?? schema?.schema_digest ?? 'no-digest',
  ].join(':')
  const activeCameraDraft = cameraDrafts[activeCameraKey]
  const isDirty = activeCameraDraft?.dirty ?? false
  const contextChanged = isDirty && activeCameraDraft?.context !== draftContext
  const draftSegments = activeCameraDraft?.segments ?? []
  const annotationNote = activeCameraDraft?.note ?? activeCameraView?.note ?? ''
  const sortedLabels = useMemo(() => sortedSchemaLabels(schema), [schema])
  const groupQualityDowngraded =
    groupQuality?.result === 'NEEDS_REVIEW' || groupQuality?.result === 'REJECTED'
  const groupQualityReceiving = group.receipt_state === 'RECEIVING'
  const isCurrentCameraQualityExcluded = Boolean(
    activeClipAssetId && viewReviews?.[activeClipAssetId]?.decision === 'REJECTED',
  )
  const canEditActiveCamera = Boolean(
    !contextChanged &&
      !groupQualityDowngraded &&
      !groupQualityReceiving &&
      activeCameraNumber !== null &&
      activeClipAssetId &&
      (!activeCameraView ||
        activeCameraView.state === 'PENDING' ||
        activeCameraView.state === 'DRAFT') &&
      !isCurrentCameraQualityExcluded &&
      sortedLabels.length > 0 &&
      canUpdateCameraViews(),
  )
  const cameraViews = (task?.camera_views ?? [])
    .slice()
    .sort((a, b) => a.camera_number - b.camera_number)
  const allCameraNumbers = viewRows.map((view) => view.cameraNumber).sort((a, b) => a - b)
  const allCameraViewsResolved = Boolean(
    allCameraNumbers.length > 0 &&
      allCameraNumbers.every((cameraNumber) => {
        const view = cameraViews.find((candidate) => candidate.camera_number === cameraNumber)
        return view?.state === 'SUBMITTED' || view?.state === 'EXCLUDED'
      }),
  )
  const submittedCameraCount = cameraViews.filter((view) => view.state === 'SUBMITTED').length
  const hasParticipatingSubmittedCamera = submittedCameraCount > 0
  const needsCorrespondenceReason = submittedCameraCount > 1
  const hasUnsavedDraftChanges = Object.values(cameraDrafts).some((d) => d.dirty)
  const canSubmitGroupRevision = Boolean(
    allCameraViewsResolved &&
      hasParticipatingSubmittedCamera &&
      !hasUnsavedDraftChanges &&
      (!needsCorrespondenceReason || Boolean(correspondenceReason.trim())) &&
      !groupQualityDowngraded &&
      !groupQualityReceiving &&
      task?.status === 'ASSIGNED' &&
      task.can_submit,
  )
  const canUseCurrentPlaybackTime = Boolean(
    canEditActiveCamera && currentPlaybackTimeMs !== null && Number.isFinite(currentPlaybackTimeMs),
  )
  const validCandidates = useMemo(
    () =>
      validActionCandidates(
        task,
        currentActionCandidates,
        schema,
        totalDurationMs,
        fallbackContext,
      ),
    [task, currentActionCandidates, schema, totalDurationMs, fallbackContext],
  )
  const pristineDraftSegments = useMemo(
    () =>
      initialDraftSegments(task, currentActionCandidates, schema, totalDurationMs, fallbackContext),
    [task, currentActionCandidates, schema, totalDurationMs, fallbackContext],
  )
  const visibleDraftSegments = isDirty ? draftSegments : pristineDraftSegments
  const hasUnsavedDraft =
    Object.values(cameraDrafts).some((draft) => draft.dirty) ||
    correspondenceReason.trim().length > 0
  const updateActiveCameraDraft = useCallback(
    (update: (current: CameraDraftState) => CameraDraftState) => {
      setCameraDrafts((previous) => {
        const current = previous[activeCameraKey] ?? {
          segments: pristineDraftSegments,
          note: activeCameraView?.note ?? '',
          dirty: false,
          context: draftContext,
        }
        return { ...previous, [activeCameraKey]: update(current) }
      })
    },
    [activeCameraKey, activeCameraView?.note, draftContext, pristineDraftSegments],
  )
  const setDraftSegments = useCallback(
    (
      next:
        | DraftAnnotationSegment[]
        | ((previous: DraftAnnotationSegment[]) => DraftAnnotationSegment[]),
    ) => {
      updateActiveCameraDraft((current) => ({
        ...current,
        segments: typeof next === 'function' ? next(current.segments) : next,
        dirty: true,
      }))
    },
    [updateActiveCameraDraft],
  )
  const setAnnotationNote = useCallback(
    (note: string) => {
      updateActiveCameraDraft((current) => ({ ...current, note, dirty: true }))
    },
    [updateActiveCameraDraft],
  )

  const hasAutoLoadedAiDraft = Boolean(
    !isDirty &&
      (!activeCameraView || activeCameraView.state === 'PENDING') &&
      validCandidates.length > 0,
  )
  const hasUnusableActionCandidates =
    currentActionCandidates.length > 0 && validCandidates.length === 0
  const aiStatus: {
    tone: StatusBadgeTone
    label: string
    tooltip?: string
  } = currentActionReadFailed
    ? { tone: 'warning', label: 'AI 状态读取中' }
    : validCandidates.length > 0 && hasAutoLoadedAiDraft
      ? {
          tone: 'success',
          label: 'AI 预填待核对',
          tooltip: `AI 已预填 ${validCandidates.length} 段候选，可直接核对微调`,
        }
      : validCandidates.length > 0
        ? { tone: 'neutral', label: `AI 已生成 ${validCandidates.length} 条候选` }
        : currentActionJob?.status === 'FAILED'
          ? { tone: 'destructive', label: 'AI 分析失败' }
          : currentActionJob?.status === 'QUEUED'
            ? { tone: 'information', label: 'AI 等待处理' }
            : currentActionJob?.status === 'RUNNING'
              ? { tone: 'information', label: 'AI 分析中' }
              : currentActionJob?.status === 'SUCCEEDED'
                ? hasUnusableActionCandidates
                  ? { tone: 'warning', label: 'AI 候选未载入' }
                  : { tone: 'neutral', label: 'AI 已完成 · 可直接标注' }
                : { tone: 'neutral', label: '等待 AI 自动分析' }

  useEffect(() => {
    if (
      isDirty ||
      !hasAutoLoadedAiDraft ||
      validCandidates.length <= AUTO_COLLAPSE_SEGMENT_THRESHOLD ||
      autoCollapsedDraftContext.current === draftContext
    ) {
      return
    }
    autoCollapsedDraftContext.current = draftContext
    setIsAllCollapsed(true)
    setCollapsedSegments({})
  }, [draftContext, hasAutoLoadedAiDraft, isDirty, validCandidates.length])

  const actionJobStatus = currentActionJob?.status
  const lastActionQueryContext = useRef<string | null>(null)
  useEffect(() => {
    if (!activeClipAssetId) {
      lastActionQueryContext.current = actionQueryContext
      return
    }
    if (
      lastActionQueryContext.current === actionQueryContext &&
      (actionJobStatus === 'SUCCEEDED' || actionJobStatus === 'FAILED') &&
      !actionReadFailed
    ) {
      return
    }

    let cancelled = false
    let timer: ReturnType<typeof setTimeout>
    async function refresh() {
      let shouldScheduleNext = true
      try {
        if (document.hidden) return
        const [job, candidates] = await Promise.all([
          fetchActionCandidateJob(projectId, captureGroupId, activeClipAssetId),
          fetchActionCandidates(projectId, captureGroupId, activeClipAssetId),
        ])
        if (cancelled) return
        lastActionQueryContext.current = actionQueryContext
        setActionJob(job)
        setActionCandidates(candidates)
        setActionReadFailed(false)
        setLoadedActionContext(actionQueryContext)
        if (job?.status === 'SUCCEEDED' || job?.status === 'FAILED') {
          shouldScheduleNext = false
          if (
            job.status === 'SUCCEEDED' &&
            (actionJobStatus === 'QUEUED' || actionJobStatus === 'RUNNING')
          ) {
            void message.success('动作预标注任务已完成，已刷新候选列表，请逐段核对')
          }
        }
      } catch {
        if (!cancelled) setActionReadFailed(true)
      } finally {
        if (!cancelled && shouldScheduleNext) {
          timer = setTimeout(() => void refresh(), 5000)
        }
      }
    }
    void refresh()
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [
    projectId,
    captureGroupId,
    activeClipAssetId,
    actionQueryContext,
    actionJobStatus,
    actionReadFailed,
    message,
  ])

  useEffect(() => {
    onDirtyChange?.(hasUnsavedDraft)
  }, [hasUnsavedDraft, onDirtyChange])

  useEffect(() => {
    if (!Object.keys(segmentErrors).length) return
    annotationFormRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
  }, [segmentErrors])

  const annotationBlockingReason = groupQualityReceiving
    ? '接收仍在进行中，暂不能开始人工标签。'
    : groupQualityDowngraded
      ? '当前整体质量为需复核或不合格；任务和历史仍保留，但不能提交新的修订。'
      : task && !currentGroupQualityAdmitting(groupQuality)
        ? '当前整体质量尚未通过准入，暂不能编辑或提交机位标注。'
        : !sortedLabels.length
          ? '标签规范暂时无法读取，当前不能提交新的修订。'
          : task && task.status !== 'ASSIGNED'
            ? `当前任务状态：${formatTaskStatus(task)}。`
            : task && !allCameraViewsResolved
              ? '请先完成并提交或排除所有预期机位，再提交整组修订。'
              : task && !task.can_submit
                ? '当前账号不允许提交整组修订；请查看服务端任务状态与项目权限。'
                : activeCameraNumber !== null && !activeClipAssetId
                  ? '当前机位缺少可编辑视频；请在机位清单中标记排除。'
                  : activeCameraView?.state === 'SUBMITTED'
                    ? '当前机位已单独提交；如需修改，请先在机位清单中重新打开。'
                    : activeCameraView?.state === 'EXCLUDED'
                      ? '当前机位已排除；如需继续编辑，请先恢复此机位。'
                      : isCurrentCameraQualityExcluded
                        ? '当前机位已被质量检查排除，不能提交该机位标注。'
                        : ''

  function getCameraDraft(cameraNumber: number) {
    const key = String(cameraNumber)
    const stored = cameraDrafts[key]
    const cameraView = cameraViews.find((view) => view.camera_number === cameraNumber)
    return {
      segments:
        stored?.segments ??
        (cameraNumber === activeCameraNumber
          ? visibleDraftSegments
          : (cameraView?.segments.map((segment) =>
              createDraftSegment(segment, 0, 1000, {
                segment_id: segment.segment_id,
                source_candidate_ids: segment.source_candidate_ids,
              }),
            ) ?? [])),
      note: stored?.note ?? cameraView?.note ?? '',
    }
  }

  async function saveCameraView(
    cameraNumber: number,
    nextState: 'PENDING' | 'DRAFT' | 'SUBMITTED' | 'EXCLUDED',
    reason = '',
  ) {
    if (isSubmittingRevision || !canUpdateCameraViews()) return false
    const targetCameraView = cameraViews.find((view) => view.camera_number === cameraNumber)
    const targetRow = viewRows.find((view) => view.cameraNumber === cameraNumber)
    const targetClipAssetId = targetRow?.stream?.clip_asset_id ?? null
    const isActiveCamera = cameraNumber === activeCameraNumber
    const draft = getCameraDraft(cameraNumber)

    if (nextState !== 'EXCLUDED' && nextState !== 'PENDING' && !targetClipAssetId) {
      void message.warning('此机位没有可用视频，只能标记为排除。')
      return
    }
    if (
      nextState === 'SUBMITTED' &&
      targetClipAssetId &&
      viewReviews?.[targetClipAssetId]?.decision === 'REJECTED'
    ) {
      void message.warning('机位质量已排除，不能提交标注。')
      return
    }
    if (nextState === 'EXCLUDED' && !reason.trim()) {
      void message.warning('请填写排除此机位的原因。')
      return
    }
    if (nextState === 'SUBMITTED') {
      const errors = validateSegments(draft.segments, isActiveCamera ? totalDurationMs : null)
      if (Object.keys(errors).length) {
        setSegmentErrors(errors)
        return
      }
    }

    setIsSubmittingRevision(true)
    try {
      const activeTask = await prepareAnnotationTask()
      const updatedTask = await updateAnnotationCameraView(
        projectId,
        activeTask.annotation_task_id,
        cameraNumber,
        {
          expected_version: targetCameraView?.version ?? 0,
          expected_input_version: activeTask.group_input_version,
          state: nextState,
          segments:
            nextState === 'EXCLUDED' || nextState === 'PENDING'
              ? []
              : draft.segments.map((segment) => ({
                  segment_id: segment.segment_id ?? crypto.randomUUID(),
                  start_ms: segment.start_ms,
                  end_ms: segment.end_ms,
                  label: segment.label,
                  source_candidate_ids: segment.source_candidate_ids ?? [],
                })),
          note: nextState === 'PENDING' ? '' : draft.note,
          exclusion_reason: nextState === 'EXCLUDED' ? reason.trim() : null,
        },
      )
      onTaskChange(updatedTask)
      const savedView = updatedTask.camera_views?.find(
        (view) => view.camera_number === cameraNumber,
      )
      if (savedView) {
        setCameraDrafts((previous) => ({
          ...previous,
          [String(cameraNumber)]: {
            segments: savedView.segments.map((segment) =>
              createDraftSegment(segment, 0, 1000, {
                segment_id: segment.segment_id,
                source_candidate_ids: segment.source_candidate_ids,
              }),
            ),
            note: savedView.note,

            dirty: false,
            context: [
              updatedTask.annotation_task_id,
              updatedTask.group_input_version,
              cameraNumber,
              savedView.clip_asset_id ?? 'no-clip',
              updatedTask.label_schema_id,
              updatedTask.label_schema_digest,
            ].join(':'),
          },
        }))
      }
      setSegmentErrors({})
      void message.success(
        nextState === 'SUBMITTED'
          ? `机位 ${cameraNumber} 标注已提交。`
          : nextState === 'EXCLUDED'
            ? `机位 ${cameraNumber} 已排除。`
            : nextState === 'PENDING'
              ? `机位 ${cameraNumber} 已恢复为待处理。`
              : `机位 ${cameraNumber} 草稿已保存。`,
      )
      return true
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '机位标注未保存，请重试。')
      return false
    } finally {
      setIsSubmittingRevision(false)
    }
  }

  function canUpdateCameraViews() {
    return (
      currentGroupQualityAdmitting(groupQuality) &&
      (task
        ? task.capture_group_id === captureGroupId && task.status === 'ASSIGNED'
        : canPrepareTask)
    )
  }

  async function handleConfirmExclusion() {
    if (activeCameraNumber === null || !excludeModalReason.trim()) return
    if (await saveCameraView(activeCameraNumber, 'EXCLUDED', excludeModalReason)) {
      setIsExcludeModalOpen(false)
      setExcludeModalReason('')
    }
  }

  async function submitRevision(event?: FormEvent<HTMLFormElement>) {
    if (event) event.preventDefault()
    if (!canSubmitGroupRevision || isSubmittingRevision) return
    setSegmentErrors({})
    setIsSubmittingRevision(true)
    try {
      if (!task) throw new Error('标注任务尚未创建。')
      const finalCorrespondences = buildIndependentCorrespondences()
      await submitAnnotationRevision(projectId, task.annotation_task_id, {
        expectedTaskVersion: task.version,
        inputVersion: task.group_input_version,
        correspondences: finalCorrespondences,
        note: '',
      })
      setCorrespondenceReason('')
      const refreshed = await onRefresh()
      void (refreshed
        ? message.success('整组多机位标注已提交，已进入待复核状态。')
        : message.warning('修订已提交，但最新事实暂未刷新；请手动刷新确认。'))
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '修订暂未提交，请稍后重试。')
    } finally {
      setIsSubmittingRevision(false)
    }
  }

  const beginDraftEdit = useCallback(() => {
    updateActiveCameraDraft((current) => ({ ...current, dirty: true, context: draftContext }))
  }, [draftContext, updateActiveCameraDraft])

  const updateSegment = useCallback(
    (draftId: number, field: keyof AnnotationSegment, value: string | number) => {
      beginDraftEdit()
      setDraftSegments((prev) => {
        const sourceSegments = prev.length > 0 ? prev : visibleDraftSegments
        return sourceSegments.map((segment) =>
          segment.draftId === draftId ? { ...segment, [field]: value } : segment,
        )
      })
    },
    [beginDraftEdit, setDraftSegments, visibleDraftSegments],
  )

  function setSegmentPlaybackTime(draftId: number, field: 'start_ms' | 'end_ms') {
    if (!canUseCurrentPlaybackTime || currentPlaybackTimeMs === null) return
    updateSegment(draftId, field, Math.max(0, Math.round(currentPlaybackTimeMs)))
  }

  function nudgeSegmentTime(draftId: number, field: 'start_ms' | 'end_ms', delta: number) {
    if (!canEditActiveCamera) return
    let updatedMs = 0
    beginDraftEdit()
    setDraftSegments((prev) => {
      const sourceSegments = prev.length > 0 ? prev : visibleDraftSegments
      const segment = sourceSegments.find((candidate) => candidate.draftId === draftId)
      if (!segment) return sourceSegments
      const currentVal = segment[field]
      const base = Number.isFinite(currentVal) ? currentVal : 0
      updatedMs = Math.max(0, base + delta)
      return sourceSegments.map((candidate) =>
        candidate.draftId === draftId ? { ...candidate, [field]: updatedMs } : candidate,
      )
    })
    onSeekToMs?.(updatedMs)
  }

  // I/O mark-in / mark-out hotkey listener (CustomEvent dispatched from FocusedView keyboard handler)
  useEffect(() => {
    if (!canEditActiveCamera) return

    function findActiveSegmentDraftId(): number | null {
      if (currentPlaybackTimeMs !== null) {
        const active = visibleDraftSegments.find(
          (seg) =>
            Number.isFinite(seg.start_ms) &&
            Number.isFinite(seg.end_ms) &&
            currentPlaybackTimeMs >= seg.start_ms &&
            currentPlaybackTimeMs <= seg.end_ms,
        )
        if (active) return active.draftId
      }
      const last =
        visibleDraftSegments.length > 0
          ? visibleDraftSegments[visibleDraftSegments.length - 1]
          : undefined
      return last?.draftId ?? null
    }

    function handleMarkIn(e: Event) {
      const { timeMs } = (e as CustomEvent<{ timeMs: number }>).detail
      const targetId = findActiveSegmentDraftId()
      if (targetId !== null) {
        updateSegment(targetId, 'start_ms', Math.max(0, Math.round(timeMs)))
      }
    }

    function handleMarkOut(e: Event) {
      const { timeMs } = (e as CustomEvent<{ timeMs: number }>).detail
      const targetId = findActiveSegmentDraftId()
      if (targetId !== null) {
        updateSegment(targetId, 'end_ms', Math.max(0, Math.round(timeMs)))
      }
    }

    window.addEventListener('annotation:mark-in', handleMarkIn)
    window.addEventListener('annotation:mark-out', handleMarkOut)
    return () => {
      window.removeEventListener('annotation:mark-in', handleMarkIn)
      window.removeEventListener('annotation:mark-out', handleMarkOut)
    }
  }, [canEditActiveCamera, currentPlaybackTimeMs, updateSegment, visibleDraftSegments])

  function handleAddSegment() {
    const sourceSegments = isDirty ? draftSegments : visibleDraftSegments
    const last = sourceSegments[sourceSegments.length - 1]
    let nextStart = 0
    if (last && Number.isFinite(last.end_ms) && last.end_ms > 0) {
      nextStart = last.end_ms
    } else if (
      currentPlaybackTimeMs !== null &&
      Number.isFinite(currentPlaybackTimeMs) &&
      currentPlaybackTimeMs > 0
    ) {
      nextStart = Math.round(currentPlaybackTimeMs)
    }
    const defaultDuration = 3000
    let nextEnd = nextStart + defaultDuration
    if (totalDurationMs && totalDurationMs > nextStart) {
      nextEnd = Math.min(nextStart + defaultDuration, totalDurationMs)
    }
    beginDraftEdit()
    setDraftSegments([...sourceSegments, createDraftSegment(undefined, nextStart, nextEnd)])
  }

  async function prepareAnnotationTask(): Promise<AnnotationTask> {
    let prepared = task
    if (!prepared) {
      if (!canPrepareTask || !assigneeActorId.trim()) {
        throw new Error('当前账号没有项目标注权限。')
      }
      if (!currentGroupQualityAdmitting(groupQuality)) {
        throw new Error('请先完成视频质量确认，再提交标注。')
      }
      prepared = await assignCaptureGroupHumanLabelTask(projectId, captureGroupId, assigneeActorId)
      onTaskChange(prepared)
    }
    return prepared
  }

  return (
    <Section
      className="capture-group-annotation-section capture-group-workflow-focus-target"
      id="capture-group-annotation"
      tabIndex={-1}
      title="动作标注"
      titleId="capture-group-annotation-title"
    >
      {contextChanged ? (
        <Alert
          type="warning"
          showIcon
          title="标注输入已变化，当前草稿已保留但不能提交。"
          action={
            <Button
              size="small"
              onClick={() => {
                if (window.confirm('丢弃当前草稿并读取最新输入？')) {
                  setCameraDrafts((previous) => {
                    const next = { ...previous }
                    delete next[activeCameraKey]
                    return next
                  })
                  setCorrespondenceReason('')
                  setSegmentErrors({})
                }
              }}
            >
              丢弃草稿并更新
            </Button>
          }
        />
      ) : null}
      <div className="capture-group-task-banner">
        <div className="capture-group-task-banner-inner">
          <div className="capture-group-task-banner-left">
            {task ? (
              <StatusIndicator tone={taskStatusTone(task)}>
                {formatTaskStatus(task)}
              </StatusIndicator>
            ) : (
              <StatusIndicator tone="neutral">尚未分配标注任务</StatusIndicator>
            )}
          </div>
          <div className="capture-group-task-banner-right">
            <Tooltip title={aiStatus.tooltip}>
              <span className="capture-group-ai-inline-status" role="status">
                <StatusBadge tone={aiStatus.tone}>{aiStatus.label}</StatusBadge>
              </span>
            </Tooltip>
            {currentActionJob?.status === 'FAILED' && canPrepareTask && (
              <Button
                size="small"
                loading={isRetryingAction}
                onClick={() => void handleRetryActionJob()}
              >
                重试动作分析
              </Button>
            )}
          </div>
        </div>
      </div>
      {hasNoHandDetected && (
        <div className="capture-group-alert-banner">
          <Alert
            type="warning"
            showIcon
            title="MediaPipe 在采样帧中未检测到手"
            description="请核对视频，动作标注仍可继续。"
          />
        </div>
      )}
      {groupQualityDowngraded ? (
        <Alert
          type="warning"
          showIcon
          title={`当前整体质量为“${groupQualityLabels[groupQuality?.result ?? 'UNREVIEWED']}”`}
          description="任务和既有修订历史仍可审计；当前整体质量不满足提交条件，恢复后再继续。"
        />
      ) : null}
      {annotationBlockingReason ? (
        <p className="capture-group-annotation-blocking" role="status">
          <CircleAlert aria-hidden="true" size={16} strokeWidth={1.8} />
          {annotationBlockingReason}
        </p>
      ) : null}
      {!task || task.status !== 'COMPLETED' ? (
        <form
          ref={annotationFormRef}
          className="capture-group-annotation-form"
          onSubmit={(event) => void submitRevision(event)}
        >
          {/* --- Multi-camera intro banner --- */}
          <div className="capture-group-multicam-banner">
            <div className="multicam-banner-left">
              <strong>整组机位标注</strong>
              <span className="multicam-banner-hint">
                每路机位独立编辑、保存和提交；不同机位时间轴彼此独立。
              </span>
            </div>
            <div className="multicam-banner-right">
              <span className="current-camera-indicator">机位 {activeCameraNumber ?? 1}</span>
            </div>
          </div>

          {/* --- STICKY TOOLBAR: Action Segments Heading & Top Submit --- */}
          <div className="capture-group-annotation-sticky-bar">
            <div className="capture-group-annotation-sticky-main">
              <div className="capture-group-annotation-sticky-summary">
                <h3 className="capture-group-annotation-sticky-title">动作时间段</h3>
                {visibleDraftSegments.length > 0 && (
                  <span className="capture-group-annotation-summary-text">
                    {visibleDraftSegments.length} 段
                  </span>
                )}
              </div>
              <div className="capture-group-annotation-sticky-actions">
                {visibleDraftSegments.length > 1 && (
                  <Button
                    type="text"
                    size="small"
                    className="capture-group-collapse-btn"
                    onClick={handleToggleAllCollapse}
                    icon={
                      isAllCollapsed ? (
                        <ChevronDown size={13} strokeWidth={2} />
                      ) : (
                        <ChevronUp size={13} strokeWidth={2} />
                      )
                    }
                    title={isAllCollapsed ? '展开全部时间段微调' : '折叠为紧凑列表'}
                  >
                    {isAllCollapsed ? '展开全部' : '折叠全部'}
                  </Button>
                )}
                {activeCameraView?.state === 'SUBMITTED' ? (
                  <Button
                    size="small"
                    disabled={!canUpdateCameraViews()}
                    loading={isSubmittingRevision}
                    onClick={() =>
                      activeCameraNumber !== null &&
                      void saveCameraView(activeCameraNumber, 'DRAFT')
                    }
                  >
                    重新编辑
                  </Button>
                ) : (
                  <Button
                    type="primary"
                    size="small"
                    htmlType="button"
                    aria-label="提交标注"
                    className="capture-group-sticky-submit-btn"
                    disabled={!canEditActiveCamera || visibleDraftSegments.length === 0}
                    loading={isSubmittingRevision}
                    icon={<Check size={13} strokeWidth={2.2} />}
                    onClick={() =>
                      activeCameraNumber !== null &&
                      void saveCameraView(activeCameraNumber, 'SUBMITTED')
                    }
                  >
                    提交标注
                  </Button>
                )}
              </div>
            </div>
            <AnnotationTimelineBar
              totalDurationMs={totalDurationMs ?? 0}
              currentPlaybackTimeMs={currentPlaybackTimeMs}
              visibleDraftSegments={visibleDraftSegments}
              schema={schema}
              hasOverlapWith={hasOverlapWith}
              onPlaySegment={onPlaySegment}
              onPlayAtMs={onPlayAtMs}
              onSeekToMs={onSeekToMs}
            />
          </div>
          {visibleDraftSegments.map((segment, index) => {
            const isPlayingActive =
              currentPlaybackTimeMs !== null &&
              Number.isFinite(segment.start_ms) &&
              Number.isFinite(segment.end_ms) &&
              currentPlaybackTimeMs >= segment.start_ms &&
              currentPlaybackTimeMs < segment.end_ms
            return (
              <AnnotationSegmentRow
                key={segment.draftId}
                segment={segment}
                index={index}
                totalCount={visibleDraftSegments.length}
                schema={schema}
                sortedLabels={sortedLabels}
                errors={segmentErrors[segment.draftId] ?? {}}
                collapsed={isSegmentCollapsed(segment.draftId)}
                canEdit={canEditActiveCamera}
                isPlayingActive={isPlayingActive}
                canUseCurrentPlaybackTime={canUseCurrentPlaybackTime}
                onToggleCollapse={toggleSegmentCollapsed}
                onPlaySegment={onPlaySegment}
                onDeleteSegment={(draftId) => {
                  beginDraftEdit()
                  const sourceSegments = isDirty ? draftSegments : visibleDraftSegments
                  setDraftSegments(
                    sourceSegments.filter((candidate) => candidate.draftId !== draftId),
                  )
                }}
                onUpdateField={(draftId, field, value) => {
                  updateSegment(draftId, field, value)
                }}
                onNudgeTime={nudgeSegmentTime}
                onSetCurrentTime={setSegmentPlaybackTime}
              />
            )
          })}
          <div className="capture-group-annotation-form-actions">
            <Button
              type="dashed"
              block
              icon={<Plus size={14} />}
              htmlType="button"
              className="capture-group-add-segment-btn"
              disabled={!canEditActiveCamera}
              onClick={handleAddSegment}
            >
              添加时间段
            </Button>
            <label className="capture-group-note-field">
              <span>机位 {activeCameraNumber ?? '—'} 标注备注（可选）</span>
              <Input.TextArea
                value={annotationNote}
                placeholder="输入修订说明或异常情况..."
                disabled={!canEditActiveCamera}
                maxLength={1000}
                autoSize={{ minRows: 1, maxRows: 3 }}
                onChange={(event) => {
                  beginDraftEdit()
                  setAnnotationNote(event.target.value)
                }}
              />
            </label>
          </div>

          {/* --- 当前机位操作区 --- */}
          <div className="capture-group-camera-action-bar">
            {activeCameraView?.state === 'EXCLUDED' ? (
              <div className="camera-action-state-banner is-excluded">
                <div className="camera-action-state-info">
                  <strong>本机位已排除</strong>
                  <p>{activeCameraView.exclusion_reason || '明确不参与本次数据使用与导出'}</p>
                </div>
                <Button
                  type="primary"
                  ghost
                  disabled={!canUpdateCameraViews()}
                  loading={isSubmittingRevision}
                  onClick={() =>
                    activeCameraNumber !== null &&
                    void saveCameraView(activeCameraNumber, 'PENDING')
                  }
                >
                  恢复此机位
                </Button>
              </div>
            ) : activeCameraView?.state === 'SUBMITTED' && task?.status === 'ASSIGNED' ? (
              <div className="camera-action-state-banner is-submitted">
                <div className="camera-action-state-info">
                  <strong>本机位标注已确认提交</strong>
                  <p>已确认 {activeCameraView.segments.length} 段动作，随时可重新编辑</p>
                </div>
                <Button
                  disabled={!canUpdateCameraViews()}
                  loading={isSubmittingRevision}
                  onClick={() =>
                    activeCameraNumber !== null && void saveCameraView(activeCameraNumber, 'DRAFT')
                  }
                >
                  重新编辑
                </Button>
              </div>
            ) : (
              <div className="camera-action-button-group">
                <Button
                  htmlType="button"
                  disabled={activeCameraNumber === null || !canUpdateCameraViews()}
                  loading={isSubmittingRevision}
                  onClick={() =>
                    activeCameraNumber !== null && void saveCameraView(activeCameraNumber, 'DRAFT')
                  }
                >
                  保存本机位草稿
                </Button>
                <Button
                  type="primary"
                  htmlType="button"
                  disabled={!canEditActiveCamera || visibleDraftSegments.length === 0}
                  loading={isSubmittingRevision}
                  icon={<Check size={14} strokeWidth={2.2} />}
                  onClick={() =>
                    activeCameraNumber !== null &&
                    void saveCameraView(activeCameraNumber, 'SUBMITTED')
                  }
                >
                  提交本机位
                </Button>
                <Button
                  danger
                  type="text"
                  disabled={!canUpdateCameraViews() || activeCameraNumber === null}
                  onClick={() => {
                    setExcludeModalReason(activeCameraView?.exclusion_reason ?? '')
                    setIsExcludeModalOpen(true)
                  }}
                >
                  排除此机位
                </Button>
              </div>
            )}
          </div>

          {needsCorrespondenceReason && task?.status === 'ASSIGNED' ? (
            <label className="capture-group-note-field">
              <span>跨机位对应未确认的原因</span>
              <Input.TextArea
                aria-label="跨机位对应未确认的原因"
                placeholder="例如：测试视频来自不同操作，不能确认跨机位对应"
                maxLength={500}
                rows={2}
                value={correspondenceReason}
                onChange={(event) => setCorrespondenceReason(event.target.value)}
              />
            </label>
          ) : null}

          {/* --- 整组送审操作栏 --- */}
          <div className="capture-group-group-submission-panel">
            <div className="group-submission-info">
              <span className="app-eyebrow">整组复核</span>
              <p className="submission-helper-text">
                {canSubmitGroupRevision
                  ? `全部 ${allCameraNumbers.length} 路机位处理明确，可整组送审。`
                  : !hasParticipatingSubmittedCamera
                    ? '至少需要保留一个参与机位提交标注，方可送审。'
                    : needsCorrespondenceReason && !correspondenceReason.trim()
                      ? '跨机位对应尚未确认，请说明原因；各路标注将按独立时间轴送审。'
                      : hasUnsavedDraftChanges
                        ? '当前机位有未提交的草稿修改，请先提交或保存本机位草稿。'
                        : '需所有机位均已提交标注或明确排除，方可整组送审。'}
              </p>
            </div>
            <Button
              type="primary"
              size="middle"
              className="group-submit-btn"
              htmlType="submit"
              aria-label="提交整组标注"
              icon={<Check size={14} strokeWidth={2.2} />}
              loading={isSubmittingRevision}
              disabled={!canSubmitGroupRevision}
            >
              提交整组标注
            </Button>
          </div>

          <CameraExclusionModal
            open={isExcludeModalOpen}
            cameraNumber={activeCameraNumber}
            reason={excludeModalReason}
            isSubmitting={isSubmittingRevision}
            onReasonChange={setExcludeModalReason}
            onConfirm={handleConfirmExclusion}
            onCancel={() => setIsExcludeModalOpen(false)}
          />
        </form>
      ) : groupQualityDowngraded ? (
        <div className="capture-group-annotation-form capture-group-annotation-form-blocked">
          <div className="capture-group-form-heading">
            <div>
              <span className="app-eyebrow">修订提交</span>
              <h3>新修订暂不可提交</h3>
            </div>
            <span>保留既有修订、复核和标注检查历史；等待服务端允许继续提交。</span>
          </div>
          <div className="capture-group-annotation-form-actions">
            <Button type="primary" disabled>
              提交标注
            </Button>
          </div>
        </div>
      ) : null}
      {task?.annotation_revision ? (
        <div className="capture-group-revision-history" aria-label="配药动作修订历史">
          <div className="capture-group-form-heading">
            <div>
              <span className="app-eyebrow">修订历史</span>
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
                  ? '复核 · 已通过'
                  : '复核 · 已退回'
                : '复核 · 待复核'}
            </StatusIndicator>
          </div>
          {task.annotation_revision.annotation_document.schema_version ===
          'annotation-document.v2' ? (
            <ReadonlyGroupRevision
              document={task.annotation_revision.annotation_document}
              schema={schema}
            />
          ) : (
            <ol className="capture-group-readonly-segments">
              {task.annotation_revision.annotation_document.segments.map((segment, index) => {
                const formattedStart = formatAnnotationTime(segment.start_ms)
                const formattedEnd = formatAnnotationTime(segment.end_ms)
                return (
                  <li key={`${segment.start_ms}-${segment.end_ms}-${segment.label}`}>
                    <span>时间段 {index + 1}</span>
                    <div className="capture-group-readonly-time">
                      <strong>
                        {formattedStart && formattedEnd
                          ? `${formattedStart} → ${formattedEnd}`
                          : '时间暂不可用'}
                      </strong>
                      <small>
                        原始值：{segment.start_ms}–{segment.end_ms} 毫秒
                      </small>
                    </div>
                    <span>{getActionDisplayName(segment.label, schema)}</span>
                  </li>
                )
              })}
            </ol>
          )}
          {task.annotation_revision.annotation_document.note ? (
            <p className="form-hint">
              修订备注：{task.annotation_revision.annotation_document.note}
            </p>
          ) : null}
        </div>
      ) : null}
    </Section>
  )
}
