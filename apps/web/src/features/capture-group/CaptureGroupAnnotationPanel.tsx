import { App as AntdApp, Alert, Button, Input, Select, Tooltip } from 'antd'
import { Check, ChevronDown, ChevronUp, CircleAlert, Clock, Play, Plus, Trash2 } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'

import {
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
import {
  recordCaptureGroupQualityReview,
  type CaptureGroup,
  type CaptureGroupQualityReview,
} from '@/api/capture-groups'
import { recordQualityReview, type QualityReview } from '@/api/quality'
import { StatusBadge, type StatusBadgeTone } from '@/components/StatusBadge'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { Section } from '@/components/workspace/Section'
import {
  createDraftSegment,
  currentGroupQualityAdmitting,
  formatAnnotationTime,
  formatHumanFriendlyTime,
  formatSegmentDurationLabel,
  formatTaskStatus,
  getActionDisplayName,
  getActionStyle,
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
  onDiscardVideo?: () => Promise<void> | void
  canPrepareTask?: boolean
}

interface CameraDraftState {
  segments: DraftAnnotationSegment[]
  note: string
  exclusionReason: string
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
  onDiscardVideo,
  canPrepareTask = false,
  viewReviews,
  activeCameraNumber,
  activeClipAssetId,
}: CaptureGroupAnnotationPanelProps) {
  const { message, modal } = AntdApp.useApp()
  const [cameraDrafts, setCameraDrafts] = useState<Record<string, CameraDraftState>>({})
  const [segmentErrors, setSegmentErrors] = useState<Record<number, SegmentValidationErrors>>({})
  const [isSubmittingRevision, setIsSubmittingRevision] = useState(false)
  const [isDiscarding, setIsDiscarding] = useState(false)
  const [actionCandidates, setActionCandidates] = useState<ActionCandidate[]>([])
  const [actionJob, setActionJob] = useState<ActionCandidateJob | null>(null)
  const [actionReadFailed, setActionReadFailed] = useState(false)
  const [loadedActionContext, setLoadedActionContext] = useState<string | null>(null)
  const [correspondences, setCorrespondences] = useState<AnnotationCorrespondence[]>([])
  const trackBarRef = useRef<HTMLDivElement | null>(null)
  const annotationFormRef = useRef<HTMLFormElement | null>(null)
  const [isAllCollapsed, setIsAllCollapsed] = useState(false)
  const [collapsedSegments, setCollapsedSegments] = useState<Record<number, boolean>>({})
  const autoCollapsedDraftContext = useRef<string | null>(null)

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
  const exclusionReason =
    activeCameraDraft?.exclusionReason ?? activeCameraView?.exclusion_reason ?? ''
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
  const allSubmittedSegmentsMapped = cameraViews
    .filter((view) => view.state === 'SUBMITTED')
    .every((view) =>
      view.segments.every((segment) =>
        correspondences.some((correspondence) =>
          correspondence.segments.some(
            (item) =>
              item.camera_number === view.camera_number && item.segment_id === segment.segment_id,
          ),
        ),
      ),
    )
  function correspondencesComplete() {
    const submittedSegments = cameraViews
      .filter((view) => view.state === 'SUBMITTED')
      .flatMap((view) =>
        view.segments.map((segment) => ({
          camera_number: view.camera_number,
          segment_id: segment.segment_id,
        })),
      )
    const refs = correspondences.flatMap((correspondence) => correspondence.segments)
    const keys = refs.map((item) => `${item.camera_number}:${item.segment_id}`)
    if (new Set(keys).size !== keys.length) return false
    if (
      refs.some(
        (item) =>
          !submittedSegments.some(
            (segment) =>
              segment.camera_number === item.camera_number &&
              segment.segment_id === item.segment_id,
          ),
      )
    )
      return false
    if (
      !submittedSegments.every((segment) =>
        keys.includes(`${segment.camera_number}:${segment.segment_id}`),
      )
    ) {
      return false
    }
    return correspondences.every((correspondence) => {
      const distinctCameras = new Set(correspondence.segments.map((item) => item.camera_number))
      return (
        correspondence.segments.length > 0 &&
        (correspondence.status === 'UNCERTAIN'
          ? Boolean(correspondence.reason?.trim())
          : distinctCameras.size >= 2)
      )
    })
  }
  const canSubmitGroupRevision = Boolean(
    allCameraViewsResolved &&
      allSubmittedSegmentsMapped &&
      correspondencesComplete() &&
      !groupQualityDowngraded &&
      !groupQualityReceiving &&
      sortedLabels.length > 0 &&
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
    Object.values(cameraDrafts).some((draft) => draft.dirty) || correspondences.length > 0
  const updateActiveCameraDraft = useCallback(
    (update: (current: CameraDraftState) => CameraDraftState) => {
      setCameraDrafts((previous) => {
        const current = previous[activeCameraKey] ?? {
          segments: pristineDraftSegments,
          note: activeCameraView?.note ?? '',
          exclusionReason: activeCameraView?.exclusion_reason ?? '',
          dirty: false,
          context: draftContext,
        }
        return { ...previous, [activeCameraKey]: update(current) }
      })
    },
    [
      activeCameraKey,
      activeCameraView?.exclusion_reason,
      activeCameraView?.note,
      draftContext,
      pristineDraftSegments,
    ],
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
  const setExclusionReason = useCallback(
    (reason: string) => {
      updateActiveCameraDraft((current) => ({ ...current, exclusionReason: reason, dirty: true }))
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
            : task && task.assignee_actor_id !== assigneeActorId
              ? '此任务已分配给其他执行人，当前账号不能修改机位标注。'
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
      exclusionReason: stored?.exclusionReason ?? cameraView?.exclusion_reason ?? '',
    }
  }

  async function saveCameraView(
    cameraNumber: number,
    nextState: 'PENDING' | 'DRAFT' | 'SUBMITTED' | 'EXCLUDED',
  ) {
    if (isSubmittingRevision || !canUpdateCameraViews()) return
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
    if (nextState === 'EXCLUDED' && !draft.exclusionReason.trim()) {
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
            nextState === 'EXCLUDED'
              ? []
              : draft.segments.map((segment) => ({
                  segment_id: segment.segment_id ?? crypto.randomUUID(),
                  start_ms: segment.start_ms,
                  end_ms: segment.end_ms,
                  label: segment.label,
                  source_candidate_ids: segment.source_candidate_ids ?? [],
                })),
          note: draft.note,
          exclusion_reason: nextState === 'EXCLUDED' ? draft.exclusionReason.trim() : null,
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
            exclusionReason: savedView.exclusion_reason ?? '',
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
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '机位标注未保存，请重试。')
    } finally {
      setIsSubmittingRevision(false)
    }
  }

  function canUpdateCameraViews() {
    return (
      currentGroupQualityAdmitting(groupQuality) &&
      (task
        ? task.capture_group_id === captureGroupId &&
          task.status === 'ASSIGNED' &&
          task.assignee_actor_id === assigneeActorId
        : canPrepareTask)
    )
  }

  function updateCameraExclusionReason(cameraNumber: number, reason: string) {
    const key = String(cameraNumber)
    const cameraView = cameraViews.find((view) => view.camera_number === cameraNumber)
    setCameraDrafts((previous) => {
      const current = previous[key] ?? {
        segments: cameraNumber === activeCameraNumber ? visibleDraftSegments : [],
        note: cameraView?.note ?? '',
        exclusionReason: cameraView?.exclusion_reason ?? '',
        dirty: false,
        context: [
          task?.annotation_task_id ?? 'no-task',
          task?.group_input_version ?? 'v1',
          cameraNumber,
          viewRows.find((view) => view.cameraNumber === cameraNumber)?.stream?.clip_asset_id ??
            'no-clip',
          task?.label_schema_id ?? schema?.label_schema_id ?? 'no-schema',
          task?.label_schema_digest ?? schema?.schema_digest ?? 'no-digest',
        ].join(':'),
      }
      return { ...previous, [key]: { ...current, exclusionReason: reason, dirty: true } }
    })
  }

  function addCorrespondence() {
    setCorrespondences((previous) => [
      ...previous,
      {
        correspondence_id: `correspondence-${crypto.randomUUID()}`,
        status: 'UNCERTAIN',
        segments: [],
      },
    ])
  }

  function updateCorrespondence(
    correspondenceId: string,
    update: (correspondence: AnnotationCorrespondence) => AnnotationCorrespondence,
  ) {
    setCorrespondences((previous) =>
      previous.map((item) => (item.correspondence_id === correspondenceId ? update(item) : item)),
    )
  }

  async function submitRevision(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!canSubmitGroupRevision || isSubmittingRevision) return
    if (!correspondencesComplete()) {
      void message.warning('请为所有已提交动作建立明确的跨机位对应关系，或标记为不确定。')
      return
    }
    setSegmentErrors({})
    setIsSubmittingRevision(true)
    try {
      if (!task) throw new Error('标注任务尚未创建。')
      await submitAnnotationRevision(projectId, task.annotation_task_id, {
        expectedTaskVersion: task.version,
        inputVersion: task.group_input_version,
        correspondences,
        note: '',
      })
      const refreshed = await onRefresh()
      void (refreshed
        ? message.success('整组多机位标注已提交。')
        : message.warning('修订已提交，但最新事实暂未刷新；请手动刷新确认。'))
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '修订暂未提交，请稍后重试。')
    } finally {
      setIsSubmittingRevision(false)
    }
  }

  async function handleDiscardVideo() {
    if (isDiscarding || groupQualityReceiving) return
    setIsDiscarding(true)
    try {
      await recordCaptureGroupQualityReview(
        projectId,
        captureGroupId,
        'REJECTED',
        '视频画面不符合动作标注要求，标记废弃',
        groupQuality?.revision ?? 0,
      )
      void message.info('该视频已标记为废弃（不合格）。')
      if (onDiscardVideo) {
        await onDiscardVideo()
      } else {
        await onRefresh()
      }
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '标记废弃失败，请稍后重试。')
    } finally {
      setIsDiscarding(false)
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
        throw new Error('请由项目管理员分配标注任务。')
      }
      if (
        groupQuality &&
        (groupQuality.result === 'REJECTED' || groupQuality.result === 'NEEDS_REVIEW')
      ) {
        throw new Error('该视频当前整体质量不满足提交条件，请恢复后再继续。')
      }
      if (!currentGroupQualityAdmitting(groupQuality)) {
        const availableStreams = (group.streams || []).filter(
          (s) => s.clip_asset_id && s.stream_status === 'INGESTED',
        )
        const targetStreams =
          availableStreams.length > 0
            ? availableStreams
            : viewRows
                .map((r) => r.stream)
                .filter((s): s is NonNullable<typeof s> => Boolean(s?.clip_asset_id))

        for (const stream of targetStreams) {
          const clipId = stream.clip_asset_id
          const existingReview = viewReviews?.[clipId]
          if (existingReview?.decision === 'QUALIFIED' || existingReview?.decision === 'REJECTED') {
            continue
          }
          try {
            await recordQualityReview(projectId, clipId, {
              decision: 'QUALIFIED',
              reason_code: 'MEETS_REQUIREMENTS',
              expected_version: existingReview?.version ?? 0,
            })
          } catch (err) {
            if (err instanceof Error && err.message.includes('CONFLICT')) {
              // 忽略版本冲突，已在别处提交
            } else {
              throw err
            }
          }
        }

        const anyRejected = targetStreams.some(
          (s) => viewReviews?.[s.clip_asset_id]?.decision === 'REJECTED',
        )
        const anyMissing =
          group.receipt_state === 'INCOMPLETE' ||
          targetStreams.some((s) => s.stream_status !== 'INGESTED')
        const groupDecision = anyRejected || anyMissing ? 'QUALIFIED_WITH_EXCLUSIONS' : 'QUALIFIED'
        const groupReason = anyRejected
          ? '排除部分不合格机位，其余符合配药标注要求'
          : '符合配药动作标注要求'

        await recordCaptureGroupQualityReview(
          projectId,
          captureGroupId,
          groupDecision,
          groupReason,
          groupQuality?.revision ?? 0,
        )
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
                  setCorrespondences([])
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
      <div className="capture-group-camera-annotation-progress">
        <div className="capture-group-camera-annotation-progress-heading">
          <div>
            <strong>整组机位标注</strong>
            <p>每路机位独立编辑、保存和提交；不同机位时间轴彼此独立，不按时间重叠推断对应关系。</p>
          </div>
          <StatusIndicator tone={allCameraViewsResolved ? 'success' : 'information'}>
            {
              allCameraNumbers.filter((cameraNumber) => {
                const state = cameraViews.find((view) => view.camera_number === cameraNumber)?.state
                return state === 'SUBMITTED' || state === 'EXCLUDED'
              }).length
            }{' '}
            / {allCameraNumbers.length} 路已处理
          </StatusIndicator>
        </div>
        <ul className="capture-group-camera-annotation-lanes" aria-label="各机位标注状态">
          {viewRows.map((view) => {
            const cameraView = cameraViews.find((item) => item.camera_number === view.cameraNumber)
            const stateLabel =
              cameraView?.state === 'SUBMITTED'
                ? '已提交'
                : cameraView?.state === 'EXCLUDED'
                  ? '已排除'
                  : cameraView?.state === 'DRAFT'
                    ? '草稿已保存'
                    : '待处理'
            const laneKey = String(view.cameraNumber)
            const laneDraft = cameraDrafts[laneKey]
            const missing = !view.stream?.clip_asset_id
            const stateTone =
              cameraView?.state === 'SUBMITTED'
                ? 'success'
                : cameraView?.state === 'EXCLUDED'
                  ? 'warning'
                  : cameraView?.state === 'DRAFT'
                    ? 'information'
                    : 'neutral'
            return (
              <li key={view.cameraNumber} className="capture-group-camera-annotation-lane">
                <strong>机位 {view.cameraNumber}</strong>
                <StatusIndicator tone={stateTone}>
                  {missing ? `缺失 · ${stateLabel}` : stateLabel}
                </StatusIndicator>
                {missing && cameraView?.state !== 'EXCLUDED' ? (
                  <div className="capture-group-camera-exclusion-control">
                    <Input
                      aria-label={`机位 ${view.cameraNumber} 排除原因`}
                      value={laneDraft?.exclusionReason ?? cameraView?.exclusion_reason ?? ''}
                      maxLength={500}
                      placeholder="填写缺失原因"
                      onChange={(event) =>
                        updateCameraExclusionReason(view.cameraNumber, event.target.value)
                      }
                    />
                    <Button
                      size="small"
                      disabled={
                        !canUpdateCameraViews() ||
                        !(laneDraft?.exclusionReason ?? cameraView?.exclusion_reason ?? '').trim()
                      }
                      loading={isSubmittingRevision}
                      onClick={() => void saveCameraView(view.cameraNumber, 'EXCLUDED')}
                    >
                      确认排除
                    </Button>
                  </div>
                ) : null}
                {cameraView?.state === 'EXCLUDED' ? (
                  <div className="capture-group-camera-exclusion-control">
                    {cameraView.exclusion_reason ? (
                      <span className="form-hint">{cameraView.exclusion_reason}</span>
                    ) : null}
                    <Button
                      size="small"
                      disabled={!canUpdateCameraViews()}
                      loading={isSubmittingRevision}
                      onClick={() => void saveCameraView(view.cameraNumber, 'PENDING')}
                    >
                      恢复此机位
                    </Button>
                  </div>
                ) : null}
                {cameraView?.state === 'SUBMITTED' && task?.status === 'ASSIGNED' ? (
                  <Button
                    size="small"
                    disabled={!canUpdateCameraViews()}
                    loading={isSubmittingRevision}
                    onClick={() => void saveCameraView(view.cameraNumber, 'DRAFT')}
                  >
                    重新打开编辑
                  </Button>
                ) : null}
              </li>
            )
          })}
        </ul>
        {activeCameraNumber !== null ? (
          <p className="capture-group-camera-active-lane" role="status">
            当前编辑：机位 {activeCameraNumber}
            {activeClipAssetId ? '' : ' · 无可用视频'}
          </p>
        ) : (
          <p className="form-hint">请选择一个可用机位开始编辑。</p>
        )}
      </div>
      {annotationBlockingReason ? (
        <p className="capture-group-annotation-blocking" role="status">
          <CircleAlert aria-hidden="true" size={16} strokeWidth={1.8} />
          {annotationBlockingReason}
        </p>
      ) : null}
      {!task || task.status === 'ASSIGNED' ? (
        <form
          ref={annotationFormRef}
          className="capture-group-annotation-form"
          onSubmit={(event) => void submitRevision(event)}
        >
          {/* --- STICKY TOOLBAR: Action Segments Heading & Quick Submit --- */}
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
                <Button
                  size="small"
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
                  size="small"
                  type="default"
                  htmlType="button"
                  disabled={!canEditActiveCamera}
                  loading={isSubmittingRevision}
                  onClick={() =>
                    activeCameraNumber !== null &&
                    void saveCameraView(activeCameraNumber, 'SUBMITTED')
                  }
                >
                  提交本机位
                </Button>
                <Button
                  type="primary"
                  size="small"
                  htmlType="submit"
                  aria-label="提交整组标注"
                  className="capture-group-sticky-submit-btn"
                  icon={<Check size={13} strokeWidth={2.2} />}
                  loading={isSubmittingRevision}
                  disabled={!canSubmitGroupRevision}
                >
                  提交整组
                </Button>
              </div>
            </div>
            {activeCameraNumber !== null && activeClipAssetId ? (
              <div className="capture-group-camera-exclusion-control capture-group-active-camera-exclusion">
                <Input
                  aria-label={`机位 ${activeCameraNumber} 排除原因`}
                  value={exclusionReason}
                  maxLength={500}
                  placeholder="如需排除本机位，请填写原因"
                  disabled={!canUpdateCameraViews()}
                  onChange={(event) => setExclusionReason(event.target.value)}
                />
                {activeCameraView?.state === 'EXCLUDED' ? (
                  <Button
                    htmlType="button"
                    disabled={!canUpdateCameraViews()}
                    loading={isSubmittingRevision}
                    onClick={() => void saveCameraView(activeCameraNumber, 'PENDING')}
                  >
                    恢复本机位
                  </Button>
                ) : (
                  <Button
                    htmlType="button"
                    disabled={!canUpdateCameraViews() || !exclusionReason.trim()}
                    loading={isSubmittingRevision}
                    onClick={() => void saveCameraView(activeCameraNumber, 'EXCLUDED')}
                  >
                    排除此机位
                  </Button>
                )}
              </div>
            ) : null}
            {totalDurationMs && totalDurationMs > 0 && visibleDraftSegments.length > 0 ? (
              <div className="capture-group-timeline-overview" aria-label="全片动作时间轨">
                <div
                  ref={trackBarRef}
                  className="capture-group-timeline-track-bar"
                  onClick={(e) => {
                    if (totalDurationMs > 0) {
                      const rect = e.currentTarget.getBoundingClientRect()
                      const clickX = e.clientX - rect.left
                      const percent = Math.max(0, Math.min(1, clickX / rect.width))
                      const targetMs = Math.round(percent * totalDurationMs)
                      if (onPlayAtMs) {
                        onPlayAtMs(targetMs)
                      } else {
                        onSeekToMs?.(targetMs)
                      }
                    }
                  }}
                >
                  {visibleDraftSegments.map((seg, idx) => {
                    const startSec = (seg.start_ms || 0) / 1000
                    const endSec = (seg.end_ms || 0) / 1000
                    const durSec = totalDurationMs / 1000
                    const leftPercent = Math.max(0, Math.min(100, (startSec / durSec) * 100))
                    const widthPercent = Math.max(
                      0.8,
                      Math.min(100 - leftPercent, (Math.max(0, endSec - startSec) / durSec) * 100),
                    )
                    const style = getActionStyle(seg.label)
                    const displayName = getActionDisplayName(seg.label, schema)
                    const isSliceActive =
                      currentPlaybackTimeMs !== null &&
                      Number.isFinite(seg.start_ms) &&
                      Number.isFinite(seg.end_ms) &&
                      currentPlaybackTimeMs >= seg.start_ms &&
                      currentPlaybackTimeMs < seg.end_ms
                    const segOverlaps = hasOverlapWith(seg, visibleDraftSegments)
                    return (
                      <Tooltip
                        key={seg.draftId}
                        title={`${idx + 1}. ${displayName} (${formatHumanFriendlyTime(seg.start_ms)} ~ ${formatHumanFriendlyTime(seg.end_ms)})${segOverlaps ? ' ⚠ 与其他区间重叠' : ''} · 点击跳转播放`}
                      >
                        <div
                          role="button"
                          aria-label={`播放时间段 ${idx + 1}：${displayName}`}
                          tabIndex={0}
                          className={`capture-group-timeline-slice${isSliceActive ? ' is-active-slice' : ''}${segOverlaps ? ' has-overlap-warning' : ''}`}
                          style={{
                            left: `${leftPercent}%`,
                            width: `${widthPercent}%`,
                            backgroundColor: style.dot,
                          }}
                          onClick={(e) => {
                            e.stopPropagation()
                            if (onPlayAtMs) {
                              onPlayAtMs(seg.start_ms)
                            } else {
                              onSeekToMs?.(seg.start_ms)
                              onPlaySegment?.(seg.start_ms, seg.end_ms)
                            }
                            const rowEl = document.getElementById(`segment-row-${seg.draftId}`)
                            if (rowEl) {
                              rowEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
                            }
                          }}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter' || e.key === ' ') {
                              e.preventDefault()
                              e.stopPropagation()
                              if (onPlayAtMs) {
                                onPlayAtMs(seg.start_ms)
                              } else {
                                onSeekToMs?.(seg.start_ms)
                                onPlaySegment?.(seg.start_ms, seg.end_ms)
                              }
                              const rowEl = document.getElementById(`segment-row-${seg.draftId}`)
                              if (rowEl) {
                                rowEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
                              }
                            }
                          }}
                        />
                      </Tooltip>
                    )
                  })}
                  {currentPlaybackTimeMs !== null && currentPlaybackTimeMs >= 0 ? (
                    <div
                      className="capture-group-timeline-playhead"
                      style={{
                        left: `${Math.max(0, Math.min(100, (currentPlaybackTimeMs / totalDurationMs) * 100))}%`,
                      }}
                      title={`当前播放: ${formatHumanFriendlyTime(currentPlaybackTimeMs)}`}
                    />
                  ) : null}
                </div>
              </div>
            ) : null}
            <div className="capture-group-playback-position" aria-label="当前播放位置">
              <div className="capture-group-playback-counter">
                <span className="capture-group-playback-label">当前进度</span>
                <strong className="capture-group-playback-time">
                  {currentPlaybackTimeMs !== null && currentPlaybackTimeMs >= 0
                    ? formatHumanFriendlyTime(currentPlaybackTimeMs)
                    : '00:00'}
                </strong>
                {totalDurationMs && totalDurationMs > 0 ? (
                  <span className="capture-group-playback-total">
                    / {formatHumanFriendlyTime(totalDurationMs)}
                  </span>
                ) : null}
              </div>
              <span
                className="capture-group-keyboard-hints"
                title="空格：播放/暂停；←/→：快退/快进 1 秒（按住 Shift 5 秒）；输入框内 ↑/↓ 步进 100ms"
              >
                <kbd className="capture-group-kbd">空格</kbd> 播放 ·{' '}
                <kbd className="capture-group-kbd">←/→</kbd> 快进退
              </span>
            </div>
          </div>
          {visibleDraftSegments.map((segment, index) => {
            const errors = segmentErrors[segment.draftId] ?? {}
            const formattedStart = formatAnnotationTime(segment.start_ms)
            const formattedEnd = formatAnnotationTime(segment.end_ms)
            const startReadableId = `segment-${segment.draftId}-start-readable`
            const endReadableId = `segment-${segment.draftId}-end-readable`
            const startErrorId = `segment-${segment.draftId}-start-error`
            const endErrorId = `segment-${segment.draftId}-end-error`
            const startDescribedBy =
              [formattedStart ? startReadableId : '', errors.start ? startErrorId : '']
                .filter(Boolean)
                .join(' ') || undefined
            const endDescribedBy =
              [formattedEnd ? endReadableId : '', errors.end ? endErrorId : '']
                .filter(Boolean)
                .join(' ') || undefined
            const segmentStyle = getActionStyle(segment.label)
            const isPlayingActive =
              currentPlaybackTimeMs !== null &&
              Number.isFinite(segment.start_ms) &&
              Number.isFinite(segment.end_ms) &&
              currentPlaybackTimeMs >= segment.start_ms &&
              currentPlaybackTimeMs < segment.end_ms
            const collapsed = isSegmentCollapsed(segment.draftId)
            return (
              <div
                id={`segment-row-${segment.draftId}`}
                className={`capture-group-segment-row ${isPlayingActive ? 'is-playing-active' : ''} ${collapsed ? 'is-collapsed' : 'is-expanded'}`}
                key={segment.draftId}
              >
                <div className="capture-group-segment-fields">
                  <div className="capture-group-segment-card-header">
                    <div className="capture-group-segment-card-main">
                      <button
                        type="button"
                        className="capture-group-segment-badge-group"
                        aria-expanded={!collapsed}
                        onClick={() => toggleSegmentCollapsed(segment.draftId)}
                        title={collapsed ? '点击展开微调' : '点击折叠'}
                      >
                        <span
                          className="capture-group-segment-dot"
                          style={{
                            display: 'inline-block',
                            width: 7,
                            height: 7,
                            borderRadius: '50%',
                            backgroundColor: segmentStyle.dot,
                            flexShrink: 0,
                          }}
                          aria-hidden="true"
                        />
                        <strong className="capture-group-segment-index-title">
                          #{String(index + 1).padStart(2, '0')}
                        </strong>
                        <span
                          className="capture-group-segment-action-name"
                          title={
                            segment.label
                              ? getActionDisplayName(segment.label, schema)
                              : '未选择动作'
                          }
                        >
                          {segment.label
                            ? getActionDisplayName(segment.label, schema)
                            : '未选择动作'}
                        </span>
                        <span
                          className="capture-group-segment-human-time"
                          title={formatSegmentDurationLabel(segment.start_ms, segment.end_ms)}
                        >
                          {collapsed
                            ? `${formatHumanFriendlyTime(segment.start_ms)} ~ ${formatHumanFriendlyTime(segment.end_ms)}`
                            : formatSegmentDurationLabel(segment.start_ms, segment.end_ms)}
                        </span>
                      </button>
                      <div className="capture-group-segment-card-actions">
                        {onPlaySegment &&
                        Number.isFinite(segment.start_ms) &&
                        Number.isFinite(segment.end_ms) &&
                        segment.end_ms > segment.start_ms ? (
                          <Button
                            type="text"
                            size="small"
                            htmlType="button"
                            className="capture-group-segment-play-btn"
                            icon={<Play size={12} fill="currentColor" />}
                            aria-label={`播放第 ${index + 1} 个时间段`}
                            onClick={(e) => {
                              e.stopPropagation()
                              onPlaySegment(segment.start_ms, segment.end_ms)
                            }}
                            title={`播放时间段 (${formatHumanFriendlyTime(segment.start_ms)} ~ ${formatHumanFriendlyTime(segment.end_ms)})`}
                          />
                        ) : null}
                        {visibleDraftSegments.length > 1 ? (
                          <Button
                            type="text"
                            size="small"
                            htmlType="button"
                            className="capture-group-segment-delete-btn"
                            icon={<Trash2 size={12} />}
                            disabled={!canEditActiveCamera}
                            aria-label={`删除第 ${index + 1} 个时间段`}
                            onClick={(e) => {
                              e.stopPropagation()
                              beginDraftEdit()
                              const sourceSegments = isDirty ? draftSegments : visibleDraftSegments
                              setDraftSegments(
                                sourceSegments.filter(
                                  (candidate) => candidate.draftId !== segment.draftId,
                                ),
                              )
                            }}
                            title={`删除第 ${index + 1} 个时间段`}
                          />
                        ) : null}
                      </div>
                    </div>
                    <label
                      className="capture-group-segment-inline-label"
                      htmlFor={`segment-${segment.draftId}-label`}
                    >
                      <span>动作标签</span>
                      <Select
                        id={`segment-${segment.draftId}-label`}
                        size="small"
                        aria-label={`第 ${index + 1} 个时间段动作标签`}
                        aria-invalid={errors.label ? true : undefined}
                        aria-describedby={
                          errors.label ? `segment-${segment.draftId}-label-error` : undefined
                        }
                        value={segment.label || undefined}
                        placeholder="选择标签"
                        disabled={!canEditActiveCamera || !sortedLabels.length}
                        options={sortedLabels.map((label) => ({
                          value: label.label_id,
                          label: label.display_name,
                        }))}
                        onChange={(value) => updateSegment(segment.draftId, 'label', value)}
                      />
                      {errors.label ? (
                        <small id={`segment-${segment.draftId}-label-error`} role="alert">
                          {errors.label}
                        </small>
                      ) : null}
                    </label>
                  </div>
                  <div className="capture-group-segment-edit-body">
                    <fieldset className="capture-group-segment-time-range">
                      <legend className="capture-group-segment-legend">时间范围</legend>
                      <div className="capture-group-segment-time-controls">
                        <div className="capture-group-segment-time-field">
                          <div className="capture-group-segment-time-field-header">
                            <label htmlFor={`segment-${segment.draftId}-start`}>
                              开始时间（毫秒）
                            </label>
                            {formattedStart ? (
                              <span
                                id={startReadableId}
                                className="capture-group-segment-time-readable"
                              >
                                {formattedStart}
                              </span>
                            ) : null}
                          </div>
                          <div className="capture-group-segment-time-input-wrap">
                            <input
                              id={`segment-${segment.draftId}-start`}
                              name={`segment-${segment.draftId}-start-ms`}
                              type="number"
                              min={0}
                              step={1}
                              inputMode="numeric"
                              autoComplete="off"
                              value={Number.isFinite(segment.start_ms) ? segment.start_ms : ''}
                              aria-invalid={errors.start ? true : undefined}
                              aria-describedby={startDescribedBy}
                              disabled={!canEditActiveCamera}
                              onChange={(event) =>
                                updateSegment(
                                  segment.draftId,
                                  'start_ms',
                                  event.target.value === ''
                                    ? Number.NaN
                                    : Number(event.target.value),
                                )
                              }
                              onKeyDown={(event) => {
                                if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
                                  event.preventDefault()
                                  const multiplier = event.key === 'ArrowUp' ? 1 : -1
                                  const delta = (event.shiftKey ? 500 : 100) * multiplier
                                  nudgeSegmentTime(segment.draftId, 'start_ms', delta)
                                }
                              }}
                            />
                            <button
                              type="button"
                              className="capture-group-segment-nudge-btn"
                              disabled={!canEditActiveCamera}
                              aria-label={`第 ${index + 1} 个时间段：开始时间减少 100 毫秒`}
                              title="微调 -100ms（按住 Shift 微调 -500ms）"
                              onClick={(e) => {
                                const delta = e.shiftKey ? -500 : -100
                                nudgeSegmentTime(segment.draftId, 'start_ms', delta)
                              }}
                            >
                              -100
                            </button>
                            <button
                              type="button"
                              className="capture-group-segment-nudge-btn"
                              disabled={!canEditActiveCamera}
                              aria-label={`第 ${index + 1} 个时间段：开始时间增加 100 毫秒`}
                              title="微调 +100ms（按住 Shift 微调 +500ms）"
                              onClick={(e) => {
                                const delta = e.shiftKey ? 500 : 100
                                nudgeSegmentTime(segment.draftId, 'start_ms', delta)
                              }}
                            >
                              +100
                            </button>
                            <Button
                              type="text"
                              size="small"
                              htmlType="button"
                              className="capture-group-segment-set-current-btn"
                              icon={<Clock size={11} strokeWidth={2} />}
                              disabled={!canUseCurrentPlaybackTime}
                              aria-label={`第 ${index + 1} 个时间段：使用当前时间作为开始`}
                              title="使用当前播放时间作为开始时间"
                              onClick={() => setSegmentPlaybackTime(segment.draftId, 'start_ms')}
                            >
                              当前
                            </Button>
                          </div>
                          {errors.start ? (
                            <small id={startErrorId} role="alert">
                              {errors.start}
                            </small>
                          ) : null}
                        </div>
                        <span className="capture-group-segment-time-arrow" aria-hidden="true">
                          →
                        </span>
                        <div className="capture-group-segment-time-field">
                          <div className="capture-group-segment-time-field-header">
                            <label htmlFor={`segment-${segment.draftId}-end`}>
                              结束时间（毫秒）
                            </label>
                            {formattedEnd ? (
                              <span
                                id={endReadableId}
                                className="capture-group-segment-time-readable"
                              >
                                {formattedEnd}
                              </span>
                            ) : null}
                          </div>
                          <div className="capture-group-segment-time-input-wrap">
                            <input
                              id={`segment-${segment.draftId}-end`}
                              name={`segment-${segment.draftId}-end-ms`}
                              type="number"
                              min={1}
                              step={1}
                              inputMode="numeric"
                              autoComplete="off"
                              value={Number.isFinite(segment.end_ms) ? segment.end_ms : ''}
                              aria-invalid={errors.end ? true : undefined}
                              aria-describedby={endDescribedBy}
                              disabled={!canEditActiveCamera}
                              onChange={(event) =>
                                updateSegment(
                                  segment.draftId,
                                  'end_ms',
                                  event.target.value === ''
                                    ? Number.NaN
                                    : Number(event.target.value),
                                )
                              }
                              onKeyDown={(event) => {
                                if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
                                  event.preventDefault()
                                  const multiplier = event.key === 'ArrowUp' ? 1 : -1
                                  const delta = (event.shiftKey ? 500 : 100) * multiplier
                                  nudgeSegmentTime(segment.draftId, 'end_ms', delta)
                                }
                              }}
                            />
                            <button
                              type="button"
                              className="capture-group-segment-nudge-btn"
                              disabled={!canEditActiveCamera}
                              aria-label={`第 ${index + 1} 个时间段：结束时间减少 100 毫秒`}
                              title="微调 -100ms（按住 Shift 微调 -500ms）"
                              onClick={(e) => {
                                const delta = e.shiftKey ? -500 : -100
                                nudgeSegmentTime(segment.draftId, 'end_ms', delta)
                              }}
                            >
                              -100
                            </button>
                            <button
                              type="button"
                              className="capture-group-segment-nudge-btn"
                              disabled={!canEditActiveCamera}
                              aria-label={`第 ${index + 1} 个时间段：结束时间增加 100 毫秒`}
                              title="微调 +100ms（按住 Shift 微调 +500ms）"
                              onClick={(e) => {
                                const delta = e.shiftKey ? 500 : 100
                                nudgeSegmentTime(segment.draftId, 'end_ms', delta)
                              }}
                            >
                              +100
                            </button>
                            <Button
                              type="text"
                              size="small"
                              htmlType="button"
                              className="capture-group-segment-set-current-btn"
                              icon={<Clock size={11} strokeWidth={2} />}
                              disabled={!canUseCurrentPlaybackTime}
                              aria-label={`第 ${index + 1} 个时间段：使用当前时间作为结束`}
                              title="使用当前播放时间作为结束时间"
                              onClick={() => setSegmentPlaybackTime(segment.draftId, 'end_ms')}
                            >
                              当前
                            </Button>
                          </div>
                          {errors.end ? (
                            <small id={endErrorId} role="alert">
                              {errors.end}
                            </small>
                          ) : null}
                        </div>
                      </div>
                    </fieldset>
                  </div>
                </div>
              </div>
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
            <div className="capture-group-verdict-actions">
              <Button
                type="text"
                danger
                size="small"
                className="capture-group-discard-btn"
                loading={isDiscarding}
                disabled={isSubmittingRevision || groupQualityReceiving}
                onClick={() => {
                  modal.confirm({
                    title: '确认废弃此视频？',
                    content: '该视频将被标记为不合格（画面异常/黑屏/拍坏）并归档，系统将自动流转。',
                    okText: '确认废弃',
                    okType: 'danger',
                    cancelText: '取消',
                    onOk: () => handleDiscardVideo(),
                  })
                }}
              >
                废弃视频
              </Button>
            </div>
          </div>
          {task && allCameraViewsResolved ? (
            <section
              className="capture-group-correspondence-editor"
              aria-label="跨机位动作对应关系"
            >
              <div className="capture-group-correspondence-heading">
                <div>
                  <span className="app-eyebrow">整组提交前</span>
                  <h3>确认跨机位动作对应</h3>
                  <p>
                    逐项选择实际对应的片段；各机位时间各自独立。无法确认时选择“不确定”并说明原因。
                  </p>
                </div>
                <Button htmlType="button" icon={<Plus size={14} />} onClick={addCorrespondence}>
                  添加对应项
                </Button>
              </div>
              {correspondences.map((correspondence, correspondenceIndex) => (
                <div
                  className="capture-group-correspondence-row"
                  key={correspondence.correspondence_id}
                >
                  <div className="capture-group-correspondence-row-heading">
                    <strong>对应项 {correspondenceIndex + 1}</strong>
                    <Select
                      aria-label={`对应项 ${correspondenceIndex + 1} 状态`}
                      value={correspondence.status}
                      options={[
                        { value: 'CONFIRMED', label: '已确认对应' },
                        { value: 'UNCERTAIN', label: '对应不确定' },
                      ]}
                      onChange={(status: 'CONFIRMED' | 'UNCERTAIN') =>
                        updateCorrespondence(correspondence.correspondence_id, (current) => ({
                          ...current,
                          status,
                        }))
                      }
                    />
                    <Button
                      htmlType="button"
                      type="text"
                      danger
                      onClick={() =>
                        setCorrespondences((previous) =>
                          previous.filter(
                            (item) => item.correspondence_id !== correspondence.correspondence_id,
                          ),
                        )
                      }
                    >
                      删除
                    </Button>
                  </div>
                  <div className="capture-group-correspondence-cameras">
                    {cameraViews
                      .filter((view) => view.state === 'SUBMITTED')
                      .map((cameraView) => {
                        const selectedSegmentId = correspondence.segments.find(
                          (item) => item.camera_number === cameraView.camera_number,
                        )?.segment_id
                        const usedByOtherRows = new Set(
                          correspondences
                            .filter(
                              (item) => item.correspondence_id !== correspondence.correspondence_id,
                            )
                            .flatMap((item) =>
                              item.segments.map(
                                (segment) => `${segment.camera_number}:${segment.segment_id}`,
                              ),
                            ),
                        )
                        return (
                          <label key={cameraView.camera_number}>
                            <span>机位 {cameraView.camera_number} 片段</span>
                            <Select
                              allowClear
                              aria-label={`对应项 ${correspondenceIndex + 1} 机位 ${cameraView.camera_number} 片段`}
                              placeholder="选择此机位片段"
                              value={selectedSegmentId}
                              options={cameraView.segments
                                .filter(
                                  (segment) =>
                                    !usedByOtherRows.has(
                                      `${cameraView.camera_number}:${segment.segment_id}`,
                                    ),
                                )
                                .map((segment, segmentIndex) => ({
                                  value: segment.segment_id,
                                  label: `${segmentIndex + 1}. ${getActionDisplayName(segment.label, schema)} · ${formatHumanFriendlyTime(segment.start_ms)}–${formatHumanFriendlyTime(segment.end_ms)}`,
                                }))}
                              onChange={(segmentId?: string) =>
                                updateCorrespondence(
                                  correspondence.correspondence_id,
                                  (current) => ({
                                    ...current,
                                    segments: [
                                      ...current.segments.filter(
                                        (item) => item.camera_number !== cameraView.camera_number,
                                      ),
                                      ...(segmentId
                                        ? [
                                            {
                                              camera_number: cameraView.camera_number,
                                              segment_id: segmentId,
                                            },
                                          ]
                                        : []),
                                    ],
                                  }),
                                )
                              }
                            />
                          </label>
                        )
                      })}
                  </div>
                  {correspondence.status === 'UNCERTAIN' ? (
                    <Input.TextArea
                      aria-label={`对应项 ${correspondenceIndex + 1} 不确定原因`}
                      value={correspondence.reason ?? ''}
                      placeholder="说明无法确认对应关系的原因"
                      maxLength={500}
                      autoSize={{ minRows: 1, maxRows: 3 }}
                      onChange={(event) =>
                        updateCorrespondence(correspondence.correspondence_id, (current) => ({
                          ...current,
                          reason: event.target.value,
                        }))
                      }
                    />
                  ) : null}
                </div>
              ))}
              {cameraViews
                .filter((view) => view.state === 'SUBMITTED')
                .some((view) =>
                  view.segments.some(
                    (segment) =>
                      !correspondences.some((correspondence) =>
                        correspondence.segments.some(
                          (item) =>
                            item.camera_number === view.camera_number &&
                            item.segment_id === segment.segment_id,
                        ),
                      ),
                  ),
                ) ? (
                <p className="form-hint">尚有已提交片段未归入对应项，整组提交会保持禁用。</p>
              ) : null}
              <Button
                type="primary"
                htmlType="submit"
                loading={isSubmittingRevision}
                disabled={!canSubmitGroupRevision}
              >
                提交整组标注
              </Button>
            </section>
          ) : null}
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
