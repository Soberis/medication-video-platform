import { App as AntdApp, Alert, Button, Input, Tooltip } from 'antd'
import { Check, ChevronDown, ChevronUp, CircleAlert, Plus, Sparkles } from 'lucide-react'
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
  reviewAnnotationRevision,
  type AnnotationCameraView,
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
  isAdmin?: boolean
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
          <li key={`camera-${view.camera_number}`} className="capture-group-readonly-summary-card">
            <div className="capture-group-readonly-header">
              <span className="capture-group-readonly-camera">机位 {view.camera_number}</span>
              <span className={`capture-group-readonly-status-tag ${view.included ? 'is-included' : 'is-excluded'}`}>
                {view.included ? '已提交' : '已排除'}
              </span>
            </div>
            <div className="capture-group-readonly-time">
              <span>{view.segments.length ? `${view.segments.length} 段动作` : '无动作片段'}</span>
              {view.exclusion_reason ? <small>原因：{view.exclusion_reason}</small> : null}
            </div>
          </li>
        )
        const segmentRows = view.segments.map((segment, index) => {
          const correspondence = document.correspondences.find(
            (item) => item.correspondence_id === segment.correspondence_id,
          )
          const formattedStart = formatAnnotationTime(segment.start_ms)
          const formattedEnd = formatAnnotationTime(segment.end_ms)
          return (
            <li key={`${view.camera_number}-${segment.segment_id}`} className="capture-group-readonly-segment-card">
              <div className="capture-group-readonly-header">
                <span className="capture-group-readonly-camera">
                  机位 {view.camera_number} · 时间段 {index + 1}
                </span>
                <span className="capture-group-readonly-action-badge">
                  {getActionDisplayName(segment.label, schema)}
                </span>
              </div>
              <div className="capture-group-readonly-time">
                <strong>
                  {formattedStart && formattedEnd
                    ? `${formattedStart} → ${formattedEnd}`
                    : '时间暂不可用'}
                </strong>
                <small>
                  原始值：{segment.start_ms}–{segment.end_ms} 毫秒（本机位时间轴）
                </small>
                {correspondence?.status === 'CONFIRMED' ? (
                  <small className="capture-group-readonly-confirmed">已确认跨机位对应</small>
                ) : null}
              </div>
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
  isAdmin = false,
}: CaptureGroupAnnotationPanelProps) {
  const { message } = AntdApp.useApp()
  const canQuickApprove = Boolean(isAdmin || task?.can_review)
  const [cameraDrafts, setCameraDrafts] = useState<Record<string, CameraDraftState>>({})
  const [segmentErrors, setSegmentErrors] = useState<Record<number, SegmentValidationErrors>>({})
  const [isSubmittingRevision, setIsSubmittingRevision] = useState(false)
  const isSubmittingRevisionRef = useRef(false)
  const [isRetryingAction, setIsRetryingAction] = useState(false)
  const [isReawakening, setIsReawakening] = useState(false)
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

  async function handleReawakenAnalysis() {
    setIsReawakening(true)
    try {
      const res = await fetch('/api/dev/reawaken-capture-group', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ captureGroupId, projectId }),
      })
      const result = await res.json()
      if (result.ok) {
        await onRefresh()
        void message.success('已重新唤醒分析任务，正在后台执行媒体探针与 AI 分析…')
      } else {
        void message.warning(result.error ?? '唤醒请求未能成功调度')
      }
    } catch {
      void message.error('唤醒请求失败，请检查服务连接')
    } finally {
      setIsReawakening(false)
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
  const [isNoteOpen, setIsNoteOpen] = useState(false)

  function recordInputVersionConflict(error: unknown) {
    if (!isInputRefreshError(error)) return false
    setInputVersionConflict(true)
    setIsExcludeModalOpen(false)
    setExcludeModalReason('')
    setSegmentErrors({})
    return true
  }

  function buildIndependentCorrespondences(
    sourceViews: AnnotationCameraView[] = cameraViews,
  ): AnnotationCorrespondence[] {
    const submittedViews = sourceViews.filter((view) => view.state === 'SUBMITTED')
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
    : task?.status === 'SUBMITTED'
      ? '整组标注已提交，等待复核。'
      : task && task.status !== 'ASSIGNED'
        ? `当前任务状态：${formatTaskStatus(task)}。`
        : hasUnsavedDraftChanges
          ? '当前机位有未提交修改，请先提交机位。'
          : hasSubmittedCameraWithoutSegments
            ? `机位 ${submittedCameraNumbersWithoutSegments.join('、')} 已提交但没有动作段；请重新编辑补充，或排除机位。`
            : !allCameraViewsResolved
              ? `已确认 ${resolvedCameraCount} / ${allCameraNumbers.length} 路机位`
              : !hasParticipatingSubmittedCamera
                ? '至少保留一个机位提交。'
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
        ? {
            tone: 'destructive',
            label: 'AI 分析失败',
            tooltip: currentActionJob.failure_reason
              ? `失败原因: ${currentActionJob.failure_reason}（可点击右侧重试）`
              : '动作分析未成功完成，可点击右侧按钮重新尝试',
          }
        : currentActionJob?.status === 'RUNNING' || currentActionJob?.status === 'QUEUED'
          ? {
              tone: 'information',
              label: 'AI 分析中',
              tooltip:
                currentActionJob.status === 'RUNNING'
                  ? `4090 正在执行 Qwen 动作分析 (第 ${currentActionJob.attempt} 次尝试，预计需 1~2 分钟)`
                  : '任务已进入消息队列，等待 4090 算力 Worker 拉取并执行',
            }
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
            ? '当前机位已提交但没有动作段；请重新编辑补充至少一段动作，或排除机位。'
            : activeCameraView?.state === 'SUBMITTED'
              ? '当前机位已提交；如需修改，请先重新编辑。'
              : activeCameraView?.state === 'EXCLUDED'
                ? '当前机位已排除；如需继续编辑，请先取消排除。'
                : activeCameraNumber !== null && !activeClipAssetId
                  ? '当前机位缺少可编辑视频；请排除机位。'
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

  async function saveCameraView(
    cameraNumber: number,
    nextState: 'PENDING' | 'DRAFT' | 'SUBMITTED' | 'EXCLUDED',
    reason = '',
    customSegments?: DraftAnnotationSegment[],
  ): Promise<AnnotationTask | null> {
    if (
      isSubmittingRevisionRef.current ||
      isSubmittingRevision ||
      inputVersionConflict ||
      !canUpdateCameraViews()
    ) {
      return null
    }
    if (contextChanged && nextState !== 'EXCLUDED' && nextState !== 'PENDING') {
      void message.warning('此机位已被更新，请先核对最新标注。')
      return null
    }
    const targetCameraView = cameraViews.find((view) => view.camera_number === cameraNumber)
    const targetRow = viewRows.find((view) => view.cameraNumber === cameraNumber)
    const targetClipAssetId = targetRow?.stream?.clip_asset_id ?? null
    const isActiveCamera = cameraNumber === activeCameraNumber
    const draft = getCameraDraft(cameraNumber)
    const effectiveSegments = customSegments ?? draft.segments

    if (nextState !== 'EXCLUDED' && nextState !== 'PENDING' && !targetClipAssetId) {
      void message.warning('此机位没有可用视频，只能排除机位。')
      return null
    }
    if (nextState === 'EXCLUDED' && !reason.trim()) {
      void message.warning('请填写排除原因，便于后续复核追溯。')
      return null
    }
    if (nextState === 'SUBMITTED') {
      if (effectiveSegments.length === 0) {
        void message.warning('请至少添加一段动作后再提交机位；没有可确认动作时请排除机位。')
        return null
      }
      const errors = validateSegments(effectiveSegments, isActiveCamera ? totalDurationMs : null)
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
        return null
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
              : effectiveSegments.map((segment) => ({
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
          ? `机位 ${cameraNumber} 已提交。`
          : nextState === 'EXCLUDED'
            ? `机位 ${cameraNumber} 已排除。`
            : nextState === 'PENDING'
              ? `机位 ${cameraNumber} 已取消排除。`
              : isReediting
                ? `机位 ${cameraNumber} 已恢复为草稿。`
                : `机位 ${cameraNumber} 草稿已保存。`,
      )
      return updatedTask
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
            return null
          }
        } catch {
          // Keep the original conflict message when the current task cannot be loaded.
        }
      }
      recordInputVersionConflict(error)
      void message.error(error instanceof Error ? error.message : '机位标注未保存，请重试。')
      return null
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
    if (isSubmittingRevisionRef.current || isSubmittingRevision || inputVersionConflict) return

    let latestTask = task
    if (
      activeCameraNumber !== null &&
      canEditActiveCamera &&
      activeCameraView?.state !== 'SUBMITTED' &&
      activeCameraView?.state !== 'EXCLUDED'
    ) {
      const activeDraft = getCameraDraft(activeCameraNumber)
      if (activeDraft.segments.length > 0) {
        const savedTask = await saveCameraView(activeCameraNumber, 'SUBMITTED')
        if (!savedTask) return
        latestTask = savedTask
      }
    }

    const targetTask = latestTask
    if (!targetTask) return
    const currentCameraViews = targetTask.camera_views ?? cameraViews
    const unreadyCameraNumbers = allCameraNumbers.filter((num) => {
      const v = currentCameraViews.find((candidate) => candidate.camera_number === num)
      return v?.state !== 'SUBMITTED' && v?.state !== 'EXCLUDED'
    })
    if (unreadyCameraNumbers.length > 0) {
      void message.info(
        `当前机位已保存。还有机位 ${unreadyCameraNumbers.join('、')} 待提交或排除。`,
      )
      return
    }

    setSegmentErrors({})
    isSubmittingRevisionRef.current = true
    setIsSubmittingRevision(true)
    try {
      const finalCorrespondences = buildIndependentCorrespondences(currentCameraViews)
      const revision = await submitAnnotationRevision(projectId, targetTask.annotation_task_id, {
        expectedTaskVersion: targetTask.version,
        inputVersion: targetTask.group_input_version,
        correspondences: finalCorrespondences,
        note: '',
      })

      if (canQuickApprove) {
        try {
          await reviewAnnotationRevision(
            projectId,
            revision.annotation_revision_id,
            'APPROVED',
            '',
            {
              reviewedCameraNumbers: allCameraNumbers,
              returnCameraNumbers: [],
            },
          )
          const refreshed = await onRefresh()
          void (refreshed
            ? message.success('标注已确认，已直接完成。随时可重新编辑。')
            : message.warning('标注已确认，但最新事实暂未刷新；请手动刷新确认。'))
          return
        } catch (reviewError) {
          console.warn('Auto review approval skipped:', reviewError)
        }
      }

      const refreshed = await onRefresh()
      void (refreshed
        ? message.success('动作标注已提交。')
        : message.warning('标注已提交，但最新事实暂未刷新；请手动刷新确认。'))
    } catch (error) {
      await onRefresh().catch(() => undefined)
      recordInputVersionConflict(error)
      void message.error(error instanceof Error ? error.message : '修订暂未提交，请稍后重试。')
    } finally {
      isSubmittingRevisionRef.current = false
      setIsSubmittingRevision(false)
    }
  }

  const submitRevisionRef = useRef(submitRevision)
  useEffect(() => {
    submitRevisionRef.current = submitRevision
  })

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
        const isSingleCameraQuickSubmit =
          activeCameraNumber !== null &&
          canEditActiveCamera &&
          visibleDraftSegments.length > 0 &&
          allCameraNumbers.length === 1
        if (
          !isSubmittingRevisionRef.current &&
          !inputVersionConflict &&
          (canSubmitGroupRevision || isSingleCameraQuickSubmit)
        ) {
          event.preventDefault()
          void submitRevisionRef.current()
        }
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [
    activeCameraNumber,
    allCameraNumbers.length,
    canEditActiveCamera,
    canSubmitGroupRevision,
    inputVersionConflict,
    visibleDraftSegments.length,
  ])

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

  const handleLoadAiCandidates = useCallback(() => {
    if (validCandidates.length === 0 || !canEditActiveCamera) return
    beginDraftEdit()
    const newSegments = validCandidates.map((candidate) =>
      createDraftSegment(
        {
          start_ms: candidate.start_ms,
          end_ms: candidate.end_ms,
          label: candidate.action_label,
        },
        0,
        1000,
        { source_candidate_ids: [candidate.action_candidate_id] },
      ),
    )
    setDraftSegments(newSegments)
    setSegmentErrors({})
    void message.success(`已载入 ${newSegments.length} 段 AI 候选。`)
  }, [
    validCandidates,
    canEditActiveCamera,
    beginDraftEdit,
    setDraftSegments,
    message,
  ])

  async function handleLoadAiCandidatesFromSubmitted() {
    if (activeCameraNumber === null || validCandidates.length === 0) return
    const newSegments = validCandidates.map((candidate) =>
      createDraftSegment(
        {
          start_ms: candidate.start_ms,
          end_ms: candidate.end_ms,
          label: candidate.action_label,
        },
        0,
        1000,
        { source_candidate_ids: [candidate.action_candidate_id] },
      ),
    )
    const ok = await saveCameraView(activeCameraNumber, 'DRAFT', '', newSegments)
    if (ok) {
      void message.success(`已载入 ${newSegments.length} 段 AI 候选并恢复为草稿。`)
    }
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
            {canPrepareTask && (
              currentActionJob?.status === 'FAILED' ? (
                <Button
                  size="small"
                  loading={isRetryingAction}
                  onClick={() => void handleRetryActionJob()}
                >
                  重试动作分析
                </Button>
              ) : (!currentActionJob || aiStatus.label === '等待 AI 自动分析') ? (
                <Button
                  size="small"
                  loading={isReawakening}
                  onClick={() => void handleReawakenAnalysis()}
                >
                  唤醒 AI 分析
                </Button>
              ) : null
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
                    · {visibleDraftSegments.length} 段动作
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
                {activeCameraView?.state === 'SUBMITTED' ? null : (
                  <Button
                    type="primary"
                    size="small"
                    htmlType="button"
                    aria-label="提交机位"
                    className="capture-group-sticky-submit-btn"
                    disabled={visibleDraftSegments.length === 0 || !canEditActiveCamera}
                    loading={isSubmittingRevision}
                    icon={<Check size={13} strokeWidth={2.2} />}
                    title="提交机位"
                    onClick={() => {
                      if (activeCameraNumber !== null) {
                        void saveCameraView(activeCameraNumber, 'SUBMITTED')
                      }
                    }}
                  >
                    提交机位
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
          {visibleDraftSegments.length === 0 && (
            <div
              className="capture-group-empty-segments-hint"
              style={{
                padding: '14px 16px',
                background: 'var(--app-color-surface-hover, #f8fafc)',
                borderRadius: 'var(--app-radius-md, 6px)',
                border: '1px dashed var(--app-color-border-subtle, #e2e8f0)',
                margin: '12px 0',
                fontSize: '13px',
                color: 'var(--app-color-foreground-muted, #64748b)',
              }}
            >
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px',
                  color: 'var(--app-color-foreground, #0f172a)',
                  fontWeight: 500,
                  marginBottom: '4px',
                }}
              >
                <span aria-hidden="true">⚠️</span>
                <span>未检测到有效动作候选</span>
              </div>
              <p style={{ margin: 0, lineHeight: 1.5 }}>
                AI 模型未在该视频中识别出临床配药动作段。若此视频为非配药、输液打针或日常杂拍，请点击右上角【废弃并下一条】直接清退。
              </p>
            </div>
          )}
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
            <div className="capture-group-segment-btn-row">
              <Button
                type="dashed"
                icon={<Plus size={14} />}
                htmlType="button"
                className="capture-group-add-segment-btn"
                disabled={!canEditActiveCamera}
                onClick={handleAddSegment}
              >
                添加时间段
              </Button>
              {canEditActiveCamera && validCandidates.length > 0 ? (
                <Button
                  type="default"
                  icon={<Sparkles size={14} strokeWidth={2} />}
                  htmlType="button"
                  className="capture-group-load-ai-btn"
                  onClick={handleLoadAiCandidates}
                >
                  载入 AI ({validCandidates.length})
                </Button>
              ) : null}
            </div>
            {annotationNote.trim() || isNoteOpen ? (
              <label className="capture-group-note-field">
                <div className="capture-group-note-header">
                  <span>机位 {activeCameraNumber ?? '—'} 标注备注（可选）</span>
                  {!annotationNote.trim() && canEditActiveCamera ? (
                    <Button
                      type="text"
                      size="small"
                      className="capture-group-note-cancel-btn"
                      onClick={() => setIsNoteOpen(false)}
                    >
                      收起
                    </Button>
                  ) : null}
                </div>
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
            ) : canEditActiveCamera ? (
              <div className="capture-group-note-toggle">
                <Button
                  type="text"
                  size="small"
                  className="capture-group-note-toggle-btn"
                  onClick={() => setIsNoteOpen(true)}
                >
                  + 添加机位备注
                </Button>
              </div>
            ) : null}
          </div>

          {/* --- 当前机位操作区 --- */}
          <div className="capture-group-camera-action-bar">
            {activeCameraView?.state === 'EXCLUDED' ? (
              <div className="camera-action-state-banner is-excluded">
                <div className="camera-action-state-info">
                  <span className="camera-action-state-title">
                    <CircleAlert size={14} strokeWidth={2} className="camera-action-alert-icon" />
                    <strong>本机位已排除</strong>
                    {activeCameraView.exclusion_reason ? (
                      <span className="camera-action-count-text">（{activeCameraView.exclusion_reason}）</span>
                    ) : null}
                  </span>
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
                  <span className="camera-action-state-title">
                    <Check size={14} strokeWidth={2.5} className="camera-action-check-icon" />
                    <strong>本机位已提交</strong>
                    <span className="camera-action-count-text">
                      （{activeCameraView.segments.length > 0 ? `${activeCameraView.segments.length}段动作` : '无动作段'}）
                    </span>
                  </span>
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
                    重新编辑
                  </Button>
                  {validCandidates.length > 0 ? (
                    <Button
                      size="small"
                      icon={<Sparkles size={13} strokeWidth={2} />}
                      htmlType="button"
                      className="capture-group-banner-ai-btn"
                      aria-label={`载入 AI 候选 (${validCandidates.length})`}
                      title={`载入 ${validCandidates.length} 段 AI 动作候选`}
                      disabled={
                        isSubmittingRevision ||
                        inputVersionConflict ||
                        contextChanged ||
                        !canUpdateCameraViews()
                      }
                      loading={isSubmittingRevision}
                      onClick={() => void handleLoadAiCandidatesFromSubmitted()}
                    >
                      载入 AI
                    </Button>
                  ) : null}
                  <Button
                    danger
                    htmlType="button"
                    size="small"
                    aria-label="排除机位"
                    title="排除当前机位"
                    disabled={
                      isSubmittingRevision || inputVersionConflict || !canUpdateCameraViews()
                    }
                    onClick={() => {
                      setExcludeModalReason(activeCameraView.exclusion_reason ?? '')
                      setIsExcludeModalOpen(true)
                    }}
                  >
                    排除
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
                  排除机位
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
              title={canQuickApprove ? '提交复核 (快捷通过)' : '提交复核'}
              disabled={
                inputVersionConflict ||
                (!canSubmitGroupRevision &&
                  !(
                    activeCameraNumber !== null &&
                    canEditActiveCamera &&
                    visibleDraftSegments.length > 0 &&
                    allCameraNumbers.length === 1
                  ))
              }
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
                  <li key={`${segment.start_ms}-${segment.end_ms}-${segment.label}`} className="capture-group-readonly-segment-card">
                    <div className="capture-group-readonly-header">
                      <span className="capture-group-readonly-camera">时间段 {index + 1}</span>
                      <span className="capture-group-readonly-action-badge">
                        {getActionDisplayName(segment.label, schema)}
                      </span>
                    </div>
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
