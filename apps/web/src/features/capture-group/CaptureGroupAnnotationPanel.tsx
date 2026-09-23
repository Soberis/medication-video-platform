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
  AnnotationTaskApiError,
  assignCaptureGroupHumanLabelTask,
  listAnnotationTasks,
  updateAnnotationCameraView,
  submitAnnotationRevision,
  type AnnotationCorrespondence,
  type AnnotationLabelSchema,
  type GroupAnnotationDocument,
  type AnnotationSegment,
  type AnnotationTask,
} from '@/api/annotation'
import type { CaptureGroup } from '@/api/capture-groups'
import { StatusBadge, type StatusBadgeTone } from '@/components/StatusBadge'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { Section } from '@/components/workspace/Section'
import { AnnotationSegmentRow } from '@/features/capture-group/AnnotationSegmentRow'
import { AnnotationTimelineBar } from '@/features/capture-group/AnnotationTimelineBar'
import { CameraExclusionModal } from '@/features/capture-group/CameraExclusionModal'
import {
  createDraftSegment,
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
import type { CaptureGroupDetailRefreshOptions } from '@/features/capture-group/use-capture-group-detail-data'

interface CaptureGroupAnnotationPanelProps {
  projectId: string
  captureGroupId: string
  group: CaptureGroup
  task: AnnotationTask | null
  schema: AnnotationLabelSchema | null
  viewRows: GroupViewRow[]
  activeCameraNumber: number | null
  activeClipAssetId: string | null
  assigneeActorId?: string
  currentPlaybackTimeMs: number | null
  totalDurationMs?: number | null
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

/** 多机位未声明跨机位对应时的系统说明：各路按独立时间轴送审，不需要人工填写。 */
const INDEPENDENT_TIMELINE_REASON = '各路机位按独立时间轴送审，未确认跨机位对应。'

/** 仅一个参与机位时没有跨机位对应可确认。 */
const INDEPENDENT_SINGLE_VIEW_REASON = '仅一个参与机位，无跨机位对应'

const INPUT_REFRESH_ERROR_CODES = new Set([
  'ANNOTATION_CAMERA_VIEW_INPUT_VERSION_CONFLICT',
  'ANNOTATION_TASK_INPUT_VERSION_CONFLICT',
  'ANNOTATION_PRIMARY_VIEW_INPUT_CHANGED',
])

function isInputRefreshError(error: unknown): error is AnnotationTaskApiError {
  return error instanceof AnnotationTaskApiError && INPUT_REFRESH_ERROR_CODES.has(error.errorCode)
}

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
                {correspondence?.status === 'CONFIRMED' ? <small>已确认跨机位对应</small> : null}
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
  task,
  schema,
  viewRows,
  assigneeActorId = '',
  currentPlaybackTimeMs,
  totalDurationMs,
  onPlaySegment,
  onPlayAtMs,
  onSeekToMs,
  onDirtyChange,
  onTaskChange,
  onRefresh,
  canPrepareTask = false,
  activeCameraNumber,
  activeClipAssetId,
}: CaptureGroupAnnotationPanelProps) {
  const { message } = AntdApp.useApp()
  const [cameraDrafts, setCameraDrafts] = useState<Record<string, CameraDraftState>>({})
  const [segmentErrors, setSegmentErrors] = useState<Record<number, SegmentValidationErrors>>({})
  const [isSubmittingRevision, setIsSubmittingRevision] = useState(false)
  const isSubmittingRevisionRef = useRef(false)
  const [isRetryingAction, setIsRetryingAction] = useState(false)
  const [inputVersionConflict, setInputVersionConflict] = useState(false)
  const [isRefreshingTask, setIsRefreshingTask] = useState(false)

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
  const annotationFormRef = useRef<HTMLFormElement | null>(null)
  const [collapseState, setCollapseState] = useState<{
    context: string
    allCollapsed: boolean
    bySegment: Record<number, boolean>
  } | null>(null)
  const [isExcludeModalOpen, setIsExcludeModalOpen] = useState(false)
  const [excludeModalReason, setExcludeModalReason] = useState('')

  function recordInputVersionConflict(error: unknown) {
    if (!isInputRefreshError(error)) return false
    setInputVersionConflict(true)
    setIsExcludeModalOpen(false)
    setExcludeModalReason('')
    setSegmentErrors({})
    return true
  }

  function buildIndependentCorrespondences(): AnnotationCorrespondence[] {
    const submittedViews = cameraViews.filter((view) => view.state === 'SUBMITTED')
    const reason =
      submittedViews.length === 1 ? INDEPENDENT_SINGLE_VIEW_REASON : INDEPENDENT_TIMELINE_REASON
    return submittedViews.flatMap((view) =>
      view.segments.map((segment) => ({
        correspondence_id: crypto.randomUUID(),
        status: 'UNCERTAIN' as const,
        reason,
        segments: [{ camera_number: view.camera_number, segment_id: segment.segment_id }],
      })),
    )
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
    activeCameraView?.version ?? 0,
  ].join(':')
  const activeCameraDraft = cameraDrafts[activeCameraKey]
  const isDirty = activeCameraDraft?.dirty ?? false
  const contextChanged = isDirty && activeCameraDraft?.context !== draftContext
  const draftSegments = activeCameraDraft?.segments ?? []
  const annotationNote = isDirty ? (activeCameraDraft?.note ?? '') : (activeCameraView?.note ?? '')
  const hasAnnotationToDiscard = Boolean(
    activeCameraView?.state === 'DRAFT' ||
      (activeCameraView?.state === 'SUBMITTED' &&
        (activeCameraView.segments.length > 0 || activeCameraView.note.trim().length > 0)) ||
      (isDirty && (draftSegments.length > 0 || annotationNote.trim().length > 0)),
  )
  const sortedLabels = useMemo(() => sortedSchemaLabels(schema), [schema])
  const groupQualityReceiving = group.receipt_state === 'RECEIVING'
  const canEditActiveCamera = Boolean(
    !inputVersionConflict &&
      !isSubmittingRevision &&
      !contextChanged &&
      !groupQualityReceiving &&
      activeCameraNumber !== null &&
      activeClipAssetId &&
      (!activeCameraView ||
        activeCameraView.state === 'PENDING' ||
        activeCameraView.state === 'DRAFT') &&
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
  const submittedCameraNumbersWithoutSegments = cameraViews
    .filter((view) => view.state === 'SUBMITTED' && view.segments.length === 0)
    .map((view) => view.camera_number)
  const hasSubmittedCameraWithoutSegments = submittedCameraNumbersWithoutSegments.length > 0
  const hasUnsavedDraftChanges = Object.values(cameraDrafts).some((draft) => draft.dirty)
  const canSubmitGroupRevision = Boolean(
    !inputVersionConflict &&
      allCameraViewsResolved &&
      hasParticipatingSubmittedCamera &&
      !hasSubmittedCameraWithoutSegments &&
      !hasUnsavedDraftChanges &&
      !groupQualityReceiving &&
      task?.status === 'ASSIGNED' &&
      task.can_submit,
  )
  const resolvedCameraCount = cameraViews.filter(
    (view) => view.state === 'SUBMITTED' || view.state === 'EXCLUDED',
  ).length
  const groupSubmissionHint = canSubmitGroupRevision
    ? '全部机位已确认'
    : hasUnsavedDraftChanges
      ? '当前机位有未提交修改，请先提交本机位标注。'
      : hasSubmittedCameraWithoutSegments
        ? `机位 ${submittedCameraNumbersWithoutSegments.join('、')} 已提交但没有动作段；请重新编辑补充，或排除本机位。`
        : !allCameraViewsResolved
          ? `已确认 ${resolvedCameraCount} / ${allCameraNumbers.length} 路机位`
          : !hasParticipatingSubmittedCamera
            ? '至少保留一个机位提交本机位标注。'
            : ''
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
  const activeCollapseState = collapseState?.context === draftContext ? collapseState : null
  const isAllCollapsed =
    activeCollapseState?.allCollapsed ??
    visibleDraftSegments.length > AUTO_COLLAPSE_SEGMENT_THRESHOLD

  function isSegmentCollapsed(draftId: number) {
    return activeCollapseState?.bySegment[draftId] ?? isAllCollapsed
  }

  function toggleSegmentCollapsed(draftId: number) {
    setCollapseState((previous) => {
      const current =
        previous?.context === draftContext
          ? previous
          : { context: draftContext, allCollapsed: isAllCollapsed, bySegment: {} }
      return {
        ...current,
        bySegment: {
          ...current.bySegment,
          [draftId]: !(current.bySegment[draftId] ?? current.allCollapsed),
        },
      }
    })
  }

  function handleToggleAllCollapse() {
    setCollapseState({ context: draftContext, allCollapsed: !isAllCollapsed, bySegment: {} })
  }
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

  const aiStatus: {
    tone: StatusBadgeTone
    label: string
    tooltip?: string
  } = currentActionReadFailed
    ? { tone: 'warning', label: 'AI 状态读取中' }
    : validCandidates.length > 0
      ? {
          tone: 'success',
          label: 'AI 预填待核对',
          tooltip: `AI 已生成 ${validCandidates.length} 段候选，可直接核对微调`,
        }
      : currentActionJob?.status === 'FAILED'
        ? { tone: 'destructive', label: 'AI 分析失败' }
        : currentActionJob?.status === 'RUNNING' || currentActionJob?.status === 'QUEUED'
          ? { tone: 'information', label: 'AI 分析中' }
          : currentActionJob?.status === 'SUCCEEDED'
            ? { tone: 'neutral', label: 'AI 已完成 · 可直接标注' }
            : { tone: 'neutral', label: '等待 AI 自动分析' }

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
    onDirtyChange?.(hasUnsavedDraftChanges)
  }, [hasUnsavedDraftChanges, onDirtyChange])

  useEffect(() => {
    if (!Object.keys(segmentErrors).length) return
    annotationFormRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
  }, [segmentErrors])

  const annotationBlockingReason = groupQualityReceiving
    ? '接收仍在进行中，暂不能开始人工标签。'
    : !sortedLabels.length
      ? '标签规范暂时无法读取，当前不能提交新的修订。'
      : task && task.status !== 'ASSIGNED'
        ? `当前任务状态：${formatTaskStatus(task)}。`
        : task && allCameraViewsResolved && !task.can_submit
          ? '当前账号暂无提交整组修订权限。'
          : activeCameraView?.state === 'SUBMITTED' && activeCameraView.segments.length === 0
            ? '当前机位已提交但没有动作段；请重新编辑补充至少一段动作，或排除本机位。'
            : activeCameraView?.state === 'SUBMITTED'
              ? '当前机位已提交；如需修改，请先重新编辑本机位。'
              : activeCameraView?.state === 'EXCLUDED'
                ? '当前机位已排除；如需继续编辑，请先取消排除。'
                : activeCameraNumber !== null && !activeClipAssetId
                  ? '当前机位缺少可编辑视频；请排除本机位。'
                  : ''

  function getCameraDraft(cameraNumber: number) {
    const key = String(cameraNumber)
    const stored = cameraDrafts[key]
    const cameraView = cameraViews.find((view) => view.camera_number === cameraNumber)
    return {
      segments:
        (stored?.dirty ? stored.segments : undefined) ??
        (cameraNumber === activeCameraNumber
          ? visibleDraftSegments
          : (cameraView?.segments.map((segment) =>
              createDraftSegment(segment, 0, 1000, {
                segment_id: segment.segment_id,
                source_candidate_ids: segment.source_candidate_ids,
              }),
            ) ?? [])),
      note: stored?.dirty ? stored.note : (cameraView?.note ?? ''),
    }
  }

  async function saveCameraView(
    cameraNumber: number,
    nextState: 'PENDING' | 'DRAFT' | 'SUBMITTED' | 'EXCLUDED',
    reason = '',
  ) {
    if (
      isSubmittingRevisionRef.current ||
      isSubmittingRevision ||
      inputVersionConflict ||
      !canUpdateCameraViews()
    ) {
      return false
    }
    if (contextChanged && nextState !== 'EXCLUDED' && nextState !== 'PENDING') {
      void message.warning('此机位已被更新，请先核对最新标注。')
      return false
    }
    const targetCameraView = cameraViews.find((view) => view.camera_number === cameraNumber)
    const targetRow = viewRows.find((view) => view.cameraNumber === cameraNumber)
    const targetClipAssetId = targetRow?.stream?.clip_asset_id ?? null
    const isActiveCamera = cameraNumber === activeCameraNumber
    const draft = getCameraDraft(cameraNumber)

    if (nextState !== 'EXCLUDED' && nextState !== 'PENDING' && !targetClipAssetId) {
      void message.warning('此机位没有可用视频，只能排除本机位。')
      return false
    }
    if (nextState === 'EXCLUDED' && !reason.trim()) {
      void message.warning('请填写排除原因，便于后续复核追溯。')
      return false
    }
    if (nextState === 'SUBMITTED') {
      if (draft.segments.length === 0) {
        void message.warning('请至少添加一段动作后再提交本机位标注；没有可确认动作时请排除本机位。')
        return false
      }
      const errors = validateSegments(draft.segments, isActiveCamera ? totalDurationMs : null)
      if (Object.keys(errors).length) {
        setSegmentErrors(errors)
        setCollapseState((previous) => ({
          context: draftContext,
          allCollapsed: previous?.context === draftContext ? previous.allCollapsed : isAllCollapsed,
          bySegment: {
            ...(previous?.context === draftContext ? previous.bySegment : {}),
            ...Object.fromEntries(Object.keys(errors).map((draftId) => [draftId, false])),
          },
        }))
        return false
      }
    }

    isSubmittingRevisionRef.current = true
    setIsSubmittingRevision(true)
    try {
      const activeTask = await prepareAnnotationTask()
      const resolvedTargetView =
        (activeTask.camera_views ?? cameraViews).find(
          (view) => view.camera_number === cameraNumber,
        ) ?? targetCameraView
      const updatedTask = await updateAnnotationCameraView(
        projectId,
        activeTask.annotation_task_id,
        cameraNumber,
        {
          expected_version: resolvedTargetView?.version ?? 0,
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
          note: nextState === 'PENDING' || nextState === 'EXCLUDED' ? '' : draft.note,
          exclusion_reason: nextState === 'EXCLUDED' ? reason.trim() : null,
        },
      )
      onTaskChange(updatedTask)
      setCameraDrafts((previous) => {
        const next = { ...previous }
        delete next[String(cameraNumber)]
        return next
      })
      setSegmentErrors({})
      const isReediting = nextState === 'DRAFT' && targetCameraView?.state === 'SUBMITTED'
      void message.success(
        nextState === 'SUBMITTED'
          ? `机位 ${cameraNumber} 标注已提交。`
          : nextState === 'EXCLUDED'
            ? `机位 ${cameraNumber} 已排除，本次不纳入标注。`
            : nextState === 'PENDING'
              ? `机位 ${cameraNumber} 已取消排除并恢复为待处理，请重新确认标注。`
              : isReediting
                ? `机位 ${cameraNumber} 已恢复为草稿，可继续编辑。`
                : `机位 ${cameraNumber} 草稿已保存。`,
      )
      return true
    } catch (error) {
      if (
        error instanceof AnnotationTaskApiError &&
        error.errorCode === 'ANNOTATION_CAMERA_VIEW_VERSION_CONFLICT'
      ) {
        try {
          const taskList = await listAnnotationTasks(projectId).catch(() => null)
          const latest = taskList?.items.find(
            (candidate) =>
              candidate.capture_group_id === captureGroupId ||
              candidate.annotation_task_id === task?.annotation_task_id,
          )
          if (latest) {
            onTaskChange(latest)
            if (latest.group_input_version !== task?.group_input_version) {
              setInputVersionConflict(true)
              setIsExcludeModalOpen(false)
              void message.warning('采集输入已变化，请刷新并重新准备任务。')
            } else {
              void message.warning('此机位已被更新，已加载最新状态；请核对后再次确认。')
            }
            return false
          }
        } catch {
          // Keep the original conflict message when the current task cannot be loaded.
        }
      }
      recordInputVersionConflict(error)
      void message.error(error instanceof Error ? error.message : '机位标注未保存，请重试。')
      return false
    } finally {
      isSubmittingRevisionRef.current = false
      setIsSubmittingRevision(false)
    }
  }

  function canUpdateCameraViews() {
    return task
      ? task.capture_group_id === captureGroupId && task.status === 'ASSIGNED'
      : canPrepareTask
  }

  async function handleConfirmExclusion() {
    if (activeCameraNumber === null) return
    const finalReason = excludeModalReason.trim()
    if (await saveCameraView(activeCameraNumber, 'EXCLUDED', finalReason)) {
      setIsExcludeModalOpen(false)
      setExcludeModalReason('')
    }
  }

  async function submitRevision(event?: FormEvent<HTMLFormElement>) {
    if (event) event.preventDefault()
    if (isSubmittingRevisionRef.current || !canSubmitGroupRevision || isSubmittingRevision) return
    setSegmentErrors({})
    isSubmittingRevisionRef.current = true
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
      const refreshed = await onRefresh()
      void (refreshed
        ? message.success('整组多机位标注已提交，已进入待复核状态。')
        : message.warning('修订已提交，但最新事实暂未刷新；请手动刷新确认。'))
    } catch (error) {
      recordInputVersionConflict(error)
      void message.error(error instanceof Error ? error.message : '修订暂未提交，请稍后重试。')
    } finally {
      isSubmittingRevisionRef.current = false
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
      try {
        prepared = await assignCaptureGroupHumanLabelTask(
          projectId,
          captureGroupId,
          assigneeActorId,
        )
        onTaskChange(prepared)
      } catch (err: unknown) {
        const errMsg = err instanceof Error ? err.message : String(err)
        if (errMsg.includes('已有标注任务') || errMsg.includes('ALREADY_EXISTS')) {
          await onRefresh()
          const taskList = await listAnnotationTasks(projectId).catch(() => null)
          const found = taskList?.items.find(
            (candidate) => candidate.capture_group_id === captureGroupId,
          )
          if (found) {
            onTaskChange(found)
            return found
          }
        }
        throw err
      }
    }
    return prepared
  }

  async function refreshAnnotationTask() {
    if (isRefreshingTask) return
    if (!canPrepareTask || !assigneeActorId.trim()) {
      void message.error('当前账号不能重新准备任务，请联系项目管理员处理采集输入变更。')
      return
    }
    setIsRefreshingTask(true)
    try {
      // This explicit assignment call also refreshes an existing stale task on the server.
      // A normal GET is not enough because task listing intentionally remains read-only.
      const refreshedTask = await assignCaptureGroupHumanLabelTask(
        projectId,
        captureGroupId,
        assigneeActorId,
      )
      const refreshedGroup = await onRefresh()
      setCameraDrafts({})
      setSegmentErrors({})
      setIsExcludeModalOpen(false)
      setExcludeModalReason('')
      onTaskChange(refreshedTask)
      setInputVersionConflict(false)
      void (refreshedGroup
        ? message.success('任务已按最新采集输入重新准备，AI 候选已重新加载，请核对后提交。')
        : message.warning('任务已重新准备，但页面其他事实暂未刷新；请手动刷新后继续。'))
    } catch (error) {
      void message.error(
        error instanceof Error ? error.message : '任务刷新失败，请联系项目管理员后重试。',
      )
    } finally {
      setIsRefreshingTask(false)
    }
  }

  return (
    <Section
      className="capture-group-annotation-section capture-group-workflow-focus-target"
      id="capture-group-annotation"
      tabIndex={-1}
      title="动作标注"
      titleId="capture-group-annotation-title"
    >
      {inputVersionConflict ? (
        <Alert
          type="error"
          showIcon
          title="采集输入已变化，当前标注任务已过期。"
          description="采集源数据已更新。重新准备任务将加载最新输入与 AI 候选，原视频与历史记录完整保留。"
          action={
            canPrepareTask ? (
              <Button
                size="small"
                loading={isRefreshingTask}
                onClick={() => void refreshAnnotationTask()}
              >
                刷新并重新准备任务
              </Button>
            ) : (
              <span>请联系项目管理员刷新任务</span>
            )
          }
        />
      ) : null}
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
            ) : canPrepareTask ? (
              <StatusIndicator tone="information">就绪可标注</StatusIndicator>
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
          {/* --- STICKY TOOLBAR: Action Segments Heading & Top Submit --- */}
          <div className="capture-group-annotation-sticky-bar">
            <div className="capture-group-annotation-sticky-main">
              <div className="capture-group-annotation-sticky-summary">
                <h3 className="capture-group-annotation-sticky-title">
                  机位 {activeCameraNumber ?? '—'}
                </h3>
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
                    htmlType="button"
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
                    htmlType="button"
                    disabled={
                      isSubmittingRevision ||
                      inputVersionConflict ||
                      contextChanged ||
                      !canUpdateCameraViews()
                    }
                    loading={isSubmittingRevision}
                    onClick={() =>
                      activeCameraNumber !== null &&
                      void saveCameraView(activeCameraNumber, 'DRAFT')
                    }
                  >
                    重新编辑本机位
                  </Button>
                ) : (
                  <Button
                    type="primary"
                    size="small"
                    htmlType="button"
                    aria-label="提交本机位标注"
                    className="capture-group-sticky-submit-btn"
                    disabled={visibleDraftSegments.length === 0 || !canEditActiveCamera}
                    loading={isSubmittingRevision}
                    icon={<Check size={13} strokeWidth={2.2} />}
                    onClick={() => {
                      if (activeCameraNumber !== null) {
                        void saveCameraView(activeCameraNumber, 'SUBMITTED')
                      }
                    }}
                  >
                    提交本机位标注
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
            <p className="capture-group-camera-disposition-hint">
              已提交机位的动作区间将纳入整组修订；无可确认动作时请选择排除（原视频与 AI
              候选不受影响，可随时取消排除）。
            </p>
            {activeCameraView?.state === 'EXCLUDED' ? (
              <div className="camera-action-state-banner is-excluded">
                <div className="camera-action-state-info">
                  <strong>本机位已排除（本次不纳入标注）</strong>
                  <p>{activeCameraView.exclusion_reason || '未记录排除原因'}</p>
                </div>
                <Button
                  type="primary"
                  ghost
                  htmlType="button"
                  disabled={isSubmittingRevision || !canUpdateCameraViews()}
                  loading={isSubmittingRevision}
                  onClick={() =>
                    activeCameraNumber !== null &&
                    void saveCameraView(activeCameraNumber, 'PENDING')
                  }
                >
                  取消排除
                </Button>
              </div>
            ) : activeCameraView?.state === 'SUBMITTED' && task?.status === 'ASSIGNED' ? (
              <div className="camera-action-state-banner is-submitted">
                <div className="camera-action-state-info">
                  <strong>本机位标注已提交</strong>
                  <p>
                    {activeCameraView.segments.length > 0
                      ? `已确认 ${activeCameraView.segments.length} 段动作；如需修改，请先重新编辑本机位。`
                      : '当前没有动作段，请重新编辑补充，或排除本机位。'}
                  </p>
                </div>
                <div className="camera-action-banner-actions">
                  <Button
                    size="small"
                    htmlType="button"
                    disabled={
                      isSubmittingRevision ||
                      inputVersionConflict ||
                      contextChanged ||
                      !canUpdateCameraViews()
                    }
                    loading={isSubmittingRevision}
                    onClick={() =>
                      activeCameraNumber !== null &&
                      void saveCameraView(activeCameraNumber, 'DRAFT')
                    }
                  >
                    重新编辑本机位
                  </Button>
                  <Button
                    danger
                    htmlType="button"
                    size="small"
                    disabled={
                      isSubmittingRevision || inputVersionConflict || !canUpdateCameraViews()
                    }
                    onClick={() => {
                      setExcludeModalReason(activeCameraView.exclusion_reason ?? '')
                      setIsExcludeModalOpen(true)
                    }}
                  >
                    排除本机位
                  </Button>
                </div>
              </div>
            ) : (
              <div className="camera-action-button-group">
                <Button
                  danger
                  type="text"
                  htmlType="button"
                  disabled={
                    isSubmittingRevision ||
                    inputVersionConflict ||
                    !canUpdateCameraViews() ||
                    activeCameraNumber === null
                  }
                  onClick={() => {
                    setExcludeModalReason(activeCameraView?.exclusion_reason ?? '')
                    setIsExcludeModalOpen(true)
                  }}
                >
                  排除本机位
                </Button>
              </div>
            )}
          </div>

          {/* --- 整组送审操作栏 --- */}
          <div className="capture-group-group-submission-panel">
            <div className="group-submission-info">
              {groupSubmissionHint ? (
                <p className="submission-helper-text">{groupSubmissionHint}</p>
              ) : null}
            </div>
            <Button
              type="primary"
              size="middle"
              className="group-submit-btn"
              htmlType="submit"
              icon={<Check size={14} strokeWidth={2.2} />}
              loading={isSubmittingRevision}
              disabled={inputVersionConflict || !canSubmitGroupRevision}
            >
              提交复核
            </Button>
          </div>

          <CameraExclusionModal
            open={isExcludeModalOpen}
            cameraNumber={activeCameraNumber}
            reason={excludeModalReason}
            hasAnnotationToDiscard={hasAnnotationToDiscard}
            isSubmitted={activeCameraView?.state === 'SUBMITTED'}
            isSubmitting={isSubmittingRevision}
            onReasonChange={setExcludeModalReason}
            onConfirm={handleConfirmExclusion}
            onCancel={() => {
              setIsExcludeModalOpen(false)
              setExcludeModalReason('')
            }}
          />
        </form>
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
