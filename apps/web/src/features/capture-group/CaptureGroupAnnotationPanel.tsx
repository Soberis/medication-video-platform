import { App as AntdApp, Alert, Button, Input, Select, Tag, Tooltip } from 'antd'
import { Check, CircleAlert, Play, Plus, RotateCw, Sparkles } from 'lucide-react'
import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'

import {
  dispatchActionCandidateJob,
  fetchActionCandidateJob,
  fetchActionCandidates,
  getTargetObjectLabel,
  type ActionCandidate,
  type ActionCandidateJob,
} from '@/api/action-candidate'
import {
  assignCaptureGroupHumanLabelTask,
  selectAnnotationPrimaryView,
  submitAnnotationRevision,
  type AnnotationLabelSchema,
  type AnnotationSegment,
  type AnnotationTask,
} from '@/api/annotation'
import {
  recordCaptureGroupQualityReview,
  type CaptureGroup,
  type CaptureGroupQualityReview,
} from '@/api/capture-groups'
import { recordQualityReview, type QualityReview } from '@/api/quality'
import { StatusBadge } from '@/components/StatusBadge'
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
  groupViewDecision,
  initialDraftSegments,
  validActionCandidates,
  sortedSchemaLabels,
  taskStatusTone,
  validateSegments,
  type DraftAnnotationSegment,
  type GroupViewRow,
  type SegmentValidationErrors,
} from '@/features/capture-group/capture-group-detail-model'
import {
  groupQualityLabels,
  viewQualityLabels,
  type CaptureGroupTaskVisibility,
} from '@/features/capture-group-presentation'
import type { CaptureGroupDetailRefreshOptions } from '@/features/capture-group/use-capture-group-detail-data'

interface CaptureGroupAnnotationPanelProps {
  projectId: string
  captureGroupId: string
  group: CaptureGroup
  groupQuality: CaptureGroupQualityReview | null
  task: AnnotationTask | null
  taskVisibility?: CaptureGroupTaskVisibility
  schema: AnnotationLabelSchema | null
  viewRows: GroupViewRow[]
  viewReviews: Record<string, QualityReview>
  assigneeActorId?: string
  currentPlaybackTimeMs: number | null
  totalDurationMs?: number | null
  hasNoHandDetected?: boolean
  onPlaySegment?: (startMs: number, endMs: number) => void
  onSeekToMs?: (timeMs: number) => void
  onTaskChange: (task: AnnotationTask) => void
  onDirtyChange?: (dirty: boolean) => void
  onRefresh: (options?: CaptureGroupDetailRefreshOptions) => Promise<boolean>
  onDiscardVideo?: () => Promise<void> | void
  canPrepareTask?: boolean
}

export function CaptureGroupAnnotationPanel({
  projectId,
  captureGroupId,
  group,
  groupQuality,
  task,
  schema,
  viewRows,
  viewReviews,
  assigneeActorId = '',
  currentPlaybackTimeMs,
  totalDurationMs,
  hasNoHandDetected,
  onPlaySegment,
  onSeekToMs,
  onDirtyChange,
  onTaskChange,
  onRefresh,
  onDiscardVideo,
  canPrepareTask = false,
}: CaptureGroupAnnotationPanelProps) {
  const { message, modal } = AntdApp.useApp()
  const [isSelectingPrimary, setIsSelectingPrimary] = useState(false)
  const [selectedPrimaryClipAssetId, setSelectedPrimaryClipAssetId] = useState<string | null>(null)
  const [draftSegments, setDraftSegments] = useState<DraftAnnotationSegment[]>([])
  const [segmentErrors, setSegmentErrors] = useState<Record<number, SegmentValidationErrors>>({})
  const [annotationNote, setAnnotationNote] = useState('')
  const [isSubmittingRevision, setIsSubmittingRevision] = useState(false)
  const [isDiscarding, setIsDiscarding] = useState(false)
  const [isLoadingAi, setIsLoadingAi] = useState(false)
  const [actionCandidates, setActionCandidates] = useState<ActionCandidate[]>([])
  const [actionJob, setActionJob] = useState<ActionCandidateJob | null>(null)
  const [actionReadFailed, setActionReadFailed] = useState(false)
  const [isDispatchingJob, setIsDispatchingJob] = useState(false)
  const trackBarRef = useRef<HTMLDivElement | null>(null)
  const [hasAutoLoadedAiDraft, setHasAutoLoadedAiDraft] = useState(false)
  const annotationFormRef = useRef<HTMLFormElement | null>(null)
  const [isDirty, setIsDirty] = useState(false)
  const [showCandidateDetails, setShowCandidateDetails] = useState(false)
  const [isAllCollapsed, setIsAllCollapsed] = useState(false)
  const [collapsedSegments, setCollapsedSegments] = useState<Record<number, boolean>>({})

  const allCandidatesAdopted = useMemo(() => {
    if (!actionCandidates.length) return false
    return actionCandidates.every((candidate) =>
      draftSegments.some(
        (s) =>
          s.label === candidate.action_label &&
          s.start_ms === candidate.start_ms &&
          s.end_ms === candidate.end_ms,
      ),
    )
  }, [actionCandidates, draftSegments])

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

  const fallbackPrimaryStream = viewRows.find((view) => view.stream?.clip_asset_id)?.stream
  const fallbackPrimaryClipAssetId =
    selectedPrimaryClipAssetId ?? fallbackPrimaryStream?.clip_asset_id ?? null
  const effectivePrimaryClipAssetId = task?.primary_clip_asset_id ?? fallbackPrimaryClipAssetId

  const fallbackContext = useMemo(
    () => ({
      projectId,
      captureGroupId,
      primaryClipAssetId: effectivePrimaryClipAssetId,
    }),
    [projectId, captureGroupId, effectivePrimaryClipAssetId],
  )

  const draftContext = [
    task?.annotation_task_id ?? 'no-task',
    task?.group_input_version ?? 'v1',
    effectivePrimaryClipAssetId ?? 'no-clip',
    task?.label_schema_id ?? schema?.label_schema_id ?? 'no-schema',
    task?.label_schema_digest ?? schema?.schema_digest ?? 'no-digest',
    task?.annotation_revision?.annotation_revision_id ?? 'no-revision',
  ].join(':')
  const [appliedContext, setAppliedContext] = useState<string | null>(null)
  const latestDraft = useRef({ draftContext, isDirty })
  useEffect(() => {
    latestDraft.current = { draftContext, isDirty }
  }, [draftContext, isDirty])
  const contextChanged = isDirty && appliedContext !== draftContext
  const sortedLabels = useMemo(() => sortedSchemaLabels(schema), [schema])
  const groupQualityDowngraded =
    groupQuality?.result === 'NEEDS_REVIEW' || groupQuality?.result === 'REJECTED'
  const groupQualityReceiving = group.receipt_state === 'RECEIVING'
  const primaryLocked = Boolean(task?.annotation_revision)
  const primaryCameraNumber =
    task?.annotation_revision?.primary_camera_number ??
    task?.primary_camera_number ??
    viewRows.find((view) => view.stream?.clip_asset_id === effectivePrimaryClipAssetId)
      ?.cameraNumber ??
    null
  const canSubmitRevision = Boolean(
    !contextChanged &&
      !groupQualityDowngraded &&
      !groupQualityReceiving &&
      effectivePrimaryClipAssetId &&
      sortedLabels.length > 0 &&
      (!task ||
        (task.capture_group_id === captureGroupId &&
          task.status === 'ASSIGNED' &&
          task.can_submit)),
  )
  const canUseCurrentPlaybackTime = Boolean(
    canSubmitRevision && currentPlaybackTimeMs !== null && Number.isFinite(currentPlaybackTimeMs),
  )

  const actionJobStatus = actionJob?.status
  const actionQueryContext = `${projectId}:${captureGroupId}:${effectivePrimaryClipAssetId}`
  const lastActionQueryContext = useRef<string | null>(null)
  useEffect(() => {
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
          fetchActionCandidateJob(projectId, captureGroupId),
          fetchActionCandidates(projectId, captureGroupId),
        ])
        if (cancelled) return
        lastActionQueryContext.current = actionQueryContext
        setActionJob(job)
        setActionCandidates(candidates)
        setActionReadFailed(false)
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
    effectivePrimaryClipAssetId,
    actionQueryContext,
    actionJobStatus,
    actionReadFailed,
    message,
  ])

  if (!isDirty && appliedContext !== draftContext) {
    setAppliedContext(draftContext)
    const initial = initialDraftSegments(
      task,
      actionCandidates,
      schema,
      totalDurationMs,
      fallbackContext,
    )
    setDraftSegments(initial)
    setSegmentErrors({})
    setAnnotationNote('')
    setHasAutoLoadedAiDraft(false)
  } else if (
    !isDirty &&
    (!task || !task.annotation_revision) &&
    canSubmitRevision &&
    validActionCandidates(task, actionCandidates, schema, totalDurationMs, fallbackContext).length >
      0 &&
    !hasAutoLoadedAiDraft
  ) {
    setDraftSegments(
      initialDraftSegments(task, actionCandidates, schema, totalDurationMs, fallbackContext),
    )
    setHasAutoLoadedAiDraft(true)
  }

  useEffect(() => {
    onDirtyChange?.(isDirty)
  }, [isDirty, onDirtyChange])

  useEffect(() => {
    if (!Object.keys(segmentErrors).length) return
    annotationFormRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
  }, [segmentErrors])

  const primaryOptions = viewRows.map((view) => {
    const decision = groupViewDecision(view, viewReviews)
    const enabled = Boolean(
      view.stream &&
        view.stream.stream_status === 'INGESTED' &&
        viewReviews[view.stream.clip_asset_id]?.decision === 'QUALIFIED',
    )
    return {
      value: view.stream?.clip_asset_id ?? `missing-${view.cameraNumber}`,
      label: `机位 ${view.cameraNumber} · ${view.stream ? viewQualityLabels[decision] : '缺失'}`,
      disabled: !enabled,
    }
  })

  const annotationBlockingReason = groupQualityReceiving
    ? '接收仍在进行中，暂不能开始人工标签。'
    : groupQualityDowngraded
      ? '当前整体质量为需复核或不合格；任务和历史仍保留，但不能提交新的修订。'
      : !effectivePrimaryClipAssetId
        ? '请选择一路已接收、媒体可用且机位质量合格的主视图。'
        : !sortedLabels.length
          ? '标签规范暂时无法读取，当前不能提交新的修订。'
          : task && task.status !== 'ASSIGNED'
            ? `当前任务状态：${formatTaskStatus(task)}。`
            : task && !task.can_submit
              ? '当前账号不允许提交该任务的修订；请查看服务端任务状态与项目权限。'
              : ''

  async function choosePrimaryView(clipAssetId: string) {
    if (primaryLocked || effectivePrimaryClipAssetId === clipAssetId || isSelectingPrimary) {
      return
    }
    if (task && !task.can_select_primary) return
    if (isDirty && !window.confirm('切换标注主视图会丢弃当前未提交的时间段和备注，继续？')) return
    setSelectedPrimaryClipAssetId(clipAssetId)
    if (task) {
      setIsSelectingPrimary(true)
      try {
        const updatedTask = await selectAnnotationPrimaryView(
          projectId,
          task.annotation_task_id,
          clipAssetId,
        )
        onTaskChange(updatedTask)
        setIsDirty(false)
        void message.success('主视图已保存；动作时间段将以该机位为准。')
      } catch (error) {
        void message.error(
          error instanceof Error ? error.message : '主视图暂未保存，请刷新后重试。',
        )
      } finally {
        setIsSelectingPrimary(false)
      }
    }
  }

  async function submitRevision(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!canSubmitRevision || isSubmittingRevision) return
    const errors = validateSegments(draftSegments, totalDurationMs)
    if (Object.keys(errors).length) {
      setSegmentErrors(errors)
      return
    }
    setSegmentErrors({})
    setIsSubmittingRevision(true)
    try {
      const activeTask = await prepareAnnotationTask()

      await submitAnnotationRevision(
        projectId,
        activeTask.annotation_task_id,
        draftSegments.map((segment) => ({
          start_ms: segment.start_ms,
          end_ms: segment.end_ms,
          label: segment.label,
        })),
        annotationNote,
      )
      setIsDirty(false)
      const refreshed = await onRefresh()
      void (refreshed
        ? message.success('配药动作修订已提交；主视图现在锁定。')
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

  function updateSegment(draftId: number, field: keyof AnnotationSegment, value: string | number) {
    setIsDirty(true)
    setDraftSegments((current) =>
      current.map((segment) =>
        segment.draftId === draftId ? { ...segment, [field]: value } : segment,
      ),
    )
  }

  function setSegmentPlaybackTime(draftId: number, field: 'start_ms' | 'end_ms') {
    if (!canUseCurrentPlaybackTime || currentPlaybackTimeMs === null) return
    updateSegment(draftId, field, Math.max(0, Math.round(currentPlaybackTimeMs)))
  }

  function handleAddSegment() {
    setIsDirty(true)
    setDraftSegments((current) => {
      const last = current[current.length - 1]
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
      return [...current, createDraftSegment(undefined, nextStart, nextEnd)]
    })
  }

  async function handleLoadAiCandidates() {
    if (!canSubmitRevision || isDirty || task?.annotation_revision || isLoadingAi) return
    setIsLoadingAi(true)
    try {
      // A newly ingested clip has no action job yet.  The load action is the
      // explicit user opt-in for Qwen inference, so create the job before
      // looking for candidates instead of reporting an empty result as a
      // worker failure.
      let currentJob = actionJob
      if (!currentJob) {
        currentJob = await fetchActionCandidateJob(projectId, captureGroupId)
        if (currentJob) setActionJob(currentJob)
      }
      if (!currentJob) {
        await handleDispatchActionJob()
        return
      }
      if (currentJob.status === 'QUEUED' || currentJob.status === 'RUNNING') {
        void message.info('动作预标注任务仍在处理中，请稍候再载入候选。')
        return
      }
      if (currentJob.status === 'FAILED') {
        void message.info('上一次动作预标注未完成，请点击“重试检测”重新投递。')
        return
      }
      const fetched = await fetchActionCandidates(projectId, captureGroupId)
      if (latestDraft.current.isDirty || latestDraft.current.draftContext !== draftContext) return
      const candidates = validActionCandidates(
        task,
        fetched,
        schema,
        totalDurationMs,
        fallbackContext,
      )
      if (candidates.length > 0) {
        setActionCandidates(candidates)
        setIsDirty(true)
        setDraftSegments(
          candidates.map((candidate) =>
            createDraftSegment({
              start_ms: candidate.start_ms,
              end_ms: candidate.end_ms,
              label: candidate.action_label,
            }),
          ),
        )
        setHasAutoLoadedAiDraft(true)
        if (candidates[0]) {
          onSeekToMs?.(candidates[0].start_ms)
        }
        void message.success(`成功载入 ${candidates.length} 个动作预标注候选片段，请逐段核对`)
      } else {
        void message.info(
          fetched.length > 0
            ? '候选来源、标签或时间区间与当前任务不匹配，请继续人工标注。'
            : '动作预标注已完成但没有可采纳候选，请继续人工标注。',
        )
      }
    } catch {
      void message.error('获取 AI 预标注候选失败，请稍后重试。')
    } finally {
      setIsLoadingAi(false)
    }
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
        if (!effectivePrimaryClipAssetId) {
          throw new Error('请先为标注任务选择主视频。')
        }
        await recordQualityReview(projectId, effectivePrimaryClipAssetId, {
          decision: 'QUALIFIED',
          reason_code: 'MEETS_REQUIREMENTS',
          expected_version: 0,
        })
        await recordCaptureGroupQualityReview(
          projectId,
          captureGroupId,
          'QUALIFIED',
          '符合配药动作标注要求',
          groupQuality?.revision ?? 0,
        )
      }
      prepared = await assignCaptureGroupHumanLabelTask(projectId, captureGroupId, assigneeActorId)
      onTaskChange(prepared)
    }
    if (
      !prepared.primary_clip_asset_id &&
      effectivePrimaryClipAssetId &&
      prepared.can_select_primary
    ) {
      prepared = await selectAnnotationPrimaryView(
        projectId,
        prepared.annotation_task_id,
        effectivePrimaryClipAssetId,
      )
      onTaskChange(prepared)
    }
    if (!prepared.primary_clip_asset_id) throw new Error('请先为标注任务选择主视频。')
    return prepared
  }

  async function handleDispatchActionJob(): Promise<ActionCandidateJob | null> {
    if (isDispatchingJob) return null
    if (!effectivePrimaryClipAssetId) {
      void message.warning('请先指定主视图机位')
      return null
    }
    setIsDispatchingJob(true)
    try {
      const job = await dispatchActionCandidateJob(projectId, captureGroupId)
      setActionJob(job)
      void message.success('已提交动作预标注任务，等待算力机返回结果...')
      return job
    } catch (err) {
      void message.error(err instanceof Error ? err.message : '动作预标注请求未完成，请稍后重试。')
      return null
    } finally {
      setIsDispatchingJob(false)
    }
  }

  function handleAdoptCandidate(candidate: ActionCandidate) {
    if (!canSubmitRevision) return
    setShowCandidateDetails(true)
    setIsDirty(true)
    onSeekToMs?.(candidate.start_ms)
    setDraftSegments((prev) => {
      const newSegment = createDraftSegment({
        start_ms: candidate.start_ms,
        end_ms: candidate.end_ms,
        label: candidate.action_label,
      })
      if (
        prev.length === 1 &&
        prev[0] &&
        !prev[0].label &&
        (prev[0].start_ms === 0 || prev[0].start_ms === null)
      ) {
        return [newSegment]
      }
      return [...prev, newSegment]
    })
    void message.success(
      `已采纳动作建议：${formatHumanFriendlyTime(candidate.start_ms)} ~ ${formatHumanFriendlyTime(candidate.end_ms)}`,
    )
  }

  function renderCandidateCard(candidate: ActionCandidate) {
    const style = getActionStyle(candidate.action_label)
    const labelName = getActionDisplayName(candidate.action_label, schema)
    const targetObjectLabel = getTargetObjectLabel(candidate.target_object)
    const durationSeconds = ((candidate.end_ms - candidate.start_ms) / 1000).toFixed(1)
    const isAdopted = draftSegments.some(
      (s) =>
        s.label === candidate.action_label &&
        s.start_ms === candidate.start_ms &&
        s.end_ms === candidate.end_ms,
    )
    return (
      <div
        key={candidate.action_candidate_id}
        className={`capture-group-ai-candidate-card${isAdopted ? ' is-adopted' : ''}`}
      >
        <div className="capture-group-ai-candidate-info">
          <span className="capture-group-ai-candidate-dot" style={{ backgroundColor: style.dot }} />
          <span className="capture-group-ai-candidate-name">{labelName}</span>
          <Tag className="capture-group-ai-candidate-tag">{candidate.action_label}</Tag>
          {targetObjectLabel && (
            <Tag color="cyan" className="capture-group-ai-candidate-object">
              {targetObjectLabel}
            </Tag>
          )}
          <span className="capture-group-ai-candidate-time">
            {formatHumanFriendlyTime(candidate.start_ms)} ~{' '}
            {formatHumanFriendlyTime(candidate.end_ms)}
            <span className="capture-group-ai-candidate-duration">({durationSeconds}s)</span>
          </span>
          {typeof candidate.confidence === 'number' && (
            <Tag color="blue" variant="filled" className="capture-group-ai-candidate-confidence">
              模型分数 {candidate.confidence.toFixed(2)}（仅供参考）
            </Tag>
          )}
        </div>
        <div className="capture-group-ai-candidate-actions">
          <Button
            type="text"
            size="small"
            icon={<Play size={12} />}
            onClick={() => {
              onSeekToMs?.(candidate.start_ms)
              onPlaySegment?.(candidate.start_ms, candidate.end_ms)
            }}
          >
            播放核对
          </Button>
          <Button
            type={isAdopted ? 'default' : 'primary'}
            ghost={!isAdopted}
            size="small"
            disabled={!canSubmitRevision || isAdopted}
            onClick={() => handleAdoptCandidate(candidate)}
            icon={isAdopted ? <Check size={12} /> : <Plus size={12} />}
          >
            {isAdopted ? '已采纳' : '采纳此段'}
          </Button>
        </div>
      </div>
    )
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
                if (window.confirm('丢弃当前草稿并读取最新输入？')) setIsDirty(false)
              }}
            >
              丢弃草稿并更新
            </Button>
          }
        />
      ) : null}
      <div className="capture-group-task-banner">
        <div>
          {task ? (
            <StatusIndicator tone={taskStatusTone(task)}>{formatTaskStatus(task)}</StatusIndicator>
          ) : (
            <StatusIndicator tone="neutral">尚未分配标注任务</StatusIndicator>
          )}
        </div>
      </div>
      {groupQualityDowngraded ? (
        <Alert
          type="warning"
          showIcon
          title={`当前整体质量为“${groupQualityLabels[groupQuality?.result ?? 'UNREVIEWED']}”`}
          description="任务和既有修订历史仍可审计；当前整体质量不满足提交条件，恢复后再继续。"
        />
      ) : null}
      {viewRows.length > 1 || !effectivePrimaryClipAssetId ? (
        <div className="capture-group-primary-view-control">
          <div>
            <strong>
              {primaryLocked
                ? `${primaryCameraNumber === null ? '主视图' : `机位 ${primaryCameraNumber}`} · 已锁定`
                : '主视图 · 时间基准'}
            </strong>
            {!primaryLocked ? <p>当前输入提交后锁定</p> : null}
          </div>
          <Select
            aria-label="主视图"
            value={effectivePrimaryClipAssetId ?? undefined}
            placeholder="选择主视图"
            disabled={
              primaryLocked || isSelectingPrimary || (task ? !task.can_select_primary : false)
            }
            loading={isSelectingPrimary}
            options={primaryOptions}
            onChange={(value) => void choosePrimaryView(value)}
          />
        </div>
      ) : null}
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
          {/* AI 动作预标注（Qwen3-VL）建议卡片 */}
          <div className="capture-group-ai-preannotation-card" aria-label="AI 动作预标注">
            <div className="capture-group-ai-preannotation-header">
              <div className="capture-group-ai-title-row">
                <Sparkles size={15} style={{ color: 'var(--app-color-primary)' }} />
                <strong>AI 动作预标注建议</strong>
                {actionJob?.status === 'SUCCEEDED' ? (
                  <StatusBadge tone="success">
                    {actionCandidates.length > 0
                      ? `已生成 ${actionCandidates.length} 条建议`
                      : '检测已完成'}
                  </StatusBadge>
                ) : actionJob?.status === 'RUNNING' ? (
                  <StatusBadge tone="info">动作分析中</StatusBadge>
                ) : actionJob?.status === 'QUEUED' ? (
                  <StatusBadge tone="info">等待处理</StatusBadge>
                ) : actionJob?.status === 'FAILED' ? (
                  <StatusBadge tone="error">动作分析失败</StatusBadge>
                ) : (
                  <StatusBadge tone="default">等待自动分析</StatusBadge>
                )}
              </div>
              <div className="capture-group-ai-header-actions">
                {actionJob?.status === 'SUCCEEDED' &&
                !allCandidatesAdopted &&
                actionCandidates.length > 0 ? (
                  <Button
                    type="default"
                    size="small"
                    disabled={!canSubmitRevision || isDirty || Boolean(task?.annotation_revision)}
                    loading={isLoadingAi}
                    onClick={() => void handleLoadAiCandidates()}
                    icon={<Sparkles size={12} style={{ color: 'var(--app-color-primary)' }} />}
                    title={
                      isDirty || task?.annotation_revision
                        ? '保留当前人工草稿或历史修订，不能用 AI 候选覆盖'
                        : '将 AI 识别的动作片段一键载入为草稿'
                    }
                  >
                    载入动作草稿
                  </Button>
                ) : null}
                {actionJob?.status === 'FAILED' ? (
                  <Button
                    type="primary"
                    danger
                    size="small"
                    loading={isDispatchingJob}
                    onClick={() => void handleDispatchActionJob()}
                    icon={<RotateCw size={12} />}
                  >
                    重试动作分析
                  </Button>
                ) : null}
              </div>
            </div>

            {actionReadFailed ? (
              <div role="status" className="capture-group-ai-banner">
                动作分析状态暂时无法读取，正在重试；这不表示 AI 作业失败。
              </div>
            ) : null}
            {actionCandidates.length > 0 ? (
              allCandidatesAdopted ? (
                <div className="capture-group-ai-adopted-box">
                  <div className="capture-group-ai-adopted-header">
                    <span className="capture-group-ai-adopted-text">
                      <Check size={14} style={{ color: 'var(--app-color-success)' }} />
                      已自动载入全部 {actionCandidates.length} 条动作建议至时间轴草稿
                    </span>
                    <Button
                      type="link"
                      size="small"
                      className="capture-group-ai-toggle-btn"
                      onClick={() => setShowCandidateDetails((prev) => !prev)}
                    >
                      {showCandidateDetails
                        ? '收起候选明细 ▴'
                        : `查看候选明细 (${actionCandidates.length}) ▾`}
                    </Button>
                  </div>
                  {showCandidateDetails ? (
                    <div className="capture-group-ai-candidate-list capture-group-ai-candidate-scroll">
                      {actionCandidates.map((candidate) => renderCandidateCard(candidate))}
                    </div>
                  ) : null}
                </div>
              ) : (
                <div className="capture-group-ai-candidate-list capture-group-ai-candidate-scroll">
                  {actionCandidates.map((candidate) => renderCandidateCard(candidate))}
                </div>
              )
            ) : actionJob?.status === 'FAILED' ? (
              <div className="capture-group-ai-banner capture-group-ai-banner-failed">
                <span>动作分析失败，可尝试“重试动作分析”，也可继续人工标注。</span>
              </div>
            ) : actionJob?.status === 'QUEUED' ? (
              <div className="capture-group-ai-banner capture-group-ai-banner-queued">
                <span>任务已在队列等待，计算节点接入后将自动处理并预填草稿。</span>
              </div>
            ) : actionJob?.status === 'RUNNING' ? (
              <div className="capture-group-ai-banner">
                <span>AI 模型推理中，正在分析主视图视频动作切片，请稍候...</span>
              </div>
            ) : (
              <div className="capture-group-ai-banner">
                <span>
                  {actionJob?.status === 'SUCCEEDED'
                    ? '动作分析已完成，暂无候选。可继续人工标注；空结果不代表视频没有动作。'
                    : '媒体就绪后后台自动生成动作草稿，无需分配负责人或手动启动。手部检测独立运行；最终标注仍需提交。'}
                </span>
              </div>
            )}
          </div>

          {/* --- STICKY TOOLBAR: Action Segments Heading & Quick Submit --- */}
          <div className="capture-group-annotation-sticky-bar">
            <div className="capture-group-annotation-sticky-summary">
              <h3 className="capture-group-annotation-sticky-title">动作时间段</h3>
              {draftSegments.length > 0 && (
                <span className="capture-group-annotation-summary-text">
                  ({draftSegments.length}段
                  {totalDurationMs ? ` · ${formatHumanFriendlyTime(totalDurationMs)}` : ''})
                </span>
              )}
            </div>
            <div className="capture-group-annotation-sticky-actions">
              {draftSegments.length > 1 && (
                <Button
                  type="text"
                  size="small"
                  onClick={handleToggleAllCollapse}
                  title={isAllCollapsed ? '展开全部时间段微调' : '折叠为紧凑列表'}
                >
                  {isAllCollapsed ? '展开全部' : '折叠全部'}
                </Button>
              )}
              <Button
                type="primary"
                size="middle"
                htmlType="submit"
                aria-label="提交标注"
                className="capture-group-sticky-submit-btn"
                loading={isSubmittingRevision}
                disabled={!canSubmitRevision}
              >
                提交标注
              </Button>
            </div>
          </div>
          {hasAutoLoadedAiDraft &&
            actionCandidates.length > 0 &&
            (!task || !task.annotation_revision) && (
              <div style={{ marginBottom: 16 }}>
                <Alert
                  type="info"
                  showIcon
                  message={`已自动载入动作预标注草稿（共 ${actionCandidates.length} 个候选片段）。草稿未经人工确认，请在时间轴上核对起止时间与标签后提交。`}
                />
              </div>
            )}
          {hasNoHandDetected && (
            <div style={{ marginBottom: 16 }}>
              <Alert
                type="warning"
                showIcon
                title="MediaPipe 在采样帧中未检测到手"
                description={
                  <span>
                    这不代表没有动作；遮挡、出画或漏检均可能导致该结果。请核对视频，动作标注仍可继续。
                  </span>
                }
              />
            </div>
          )}
          <div className="capture-group-playback-position" aria-label="当前播放位置">
            <div>
              <span className="app-eyebrow">当前播放位置</span>
              <strong>
                {formatAnnotationTime(
                  currentPlaybackTimeMs === null ? null : Math.round(currentPlaybackTimeMs),
                ) ?? '未读取'}
              </strong>
            </div>
          </div>
          {totalDurationMs && totalDurationMs > 0 && draftSegments.length > 0 ? (
            <div className="capture-group-timeline-overview" aria-label="全片动作时间轨">
              <div
                ref={trackBarRef}
                className="capture-group-timeline-track-bar"
                onClick={(e) => {
                  if (e.target === e.currentTarget && totalDurationMs > 0) {
                    const rect = e.currentTarget.getBoundingClientRect()
                    const clickX = e.clientX - rect.left
                    const percent = Math.max(0, Math.min(1, clickX / rect.width))
                    const targetMs = Math.round(percent * totalDurationMs)
                    onSeekToMs?.(targetMs)
                  }
                }}
              >
                {draftSegments.map((seg, idx) => {
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
                  return (
                    <Tooltip
                      key={seg.draftId}
                      title={`${idx + 1}. ${displayName} (${formatHumanFriendlyTime(seg.start_ms)} ~ ${formatHumanFriendlyTime(seg.end_ms)}) · 点击跳转播放`}
                    >
                      <div
                        role="button"
                        aria-label={`播放时间段 ${idx + 1}：${displayName}`}
                        tabIndex={0}
                        className={`capture-group-timeline-slice ${isSliceActive ? 'is-active-slice' : ''}`}
                        style={{
                          left: `${leftPercent}%`,
                          width: `${widthPercent}%`,
                          backgroundColor: style.dot,
                        }}
                        onClick={() => {
                          onSeekToMs?.(seg.start_ms)
                          onPlaySegment?.(seg.start_ms, seg.end_ms)
                        }}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault()
                            onSeekToMs?.(seg.start_ms)
                            onPlaySegment?.(seg.start_ms, seg.end_ms)
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
              <div className="capture-group-timeline-scale">
                <span>00:00</span>
                <span>当前播放: {formatHumanFriendlyTime(currentPlaybackTimeMs)}</span>
                <span>{formatHumanFriendlyTime(totalDurationMs)}</span>
              </div>
            </div>
          ) : null}
          {draftSegments.map((segment, index) => {
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
                className={`capture-group-segment-row ${isPlayingActive ? 'is-playing-active' : ''} ${collapsed ? 'is-collapsed' : 'is-expanded'}`}
                key={segment.draftId}
              >
                <div className="capture-group-segment-fields">
                  <div className="capture-group-segment-card-header">
                    <div
                      className="capture-group-segment-badge-group"
                      style={{ cursor: 'pointer' }}
                      onClick={() => toggleSegmentCollapsed(segment.draftId)}
                      title="点击展开/折叠微调编辑"
                    >
                      <span
                        className="capture-group-segment-dot"
                        style={{
                          display: 'inline-block',
                          width: 8,
                          height: 8,
                          borderRadius: '50%',
                          backgroundColor: segmentStyle.dot,
                          flexShrink: 0,
                        }}
                        aria-hidden="true"
                      />
                      <strong className="capture-group-segment-index-title">
                        时间段 {index + 1}
                      </strong>
                      <span className="capture-group-segment-human-time">
                        {formatSegmentDurationLabel(segment.start_ms, segment.end_ms)}
                      </span>
                    </div>
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
                          icon={<Play size={11} fill="currentColor" />}
                          onClick={() => onPlaySegment(segment.start_ms, segment.end_ms)}
                          title={`播放 ${formatHumanFriendlyTime(segment.start_ms)} ~ ${formatHumanFriendlyTime(segment.end_ms)}`}
                        >
                          播放
                        </Button>
                      ) : null}
                      {draftSegments.length > 1 ? (
                        <Button
                          type="text"
                          danger
                          size="small"
                          htmlType="button"
                          disabled={!canSubmitRevision}
                          aria-label={`删除第 ${index + 1} 个时间段`}
                          onClick={() => {
                            setIsDirty(true)
                            setDraftSegments((current) =>
                              current.filter((candidate) => candidate.draftId !== segment.draftId),
                            )
                          }}
                        >
                          删除
                        </Button>
                      ) : null}
                    </div>
                  </div>
                  <div className="capture-group-segment-edit-body">
                    <label
                      className="capture-group-segment-label"
                      htmlFor={`segment-${segment.draftId}-label`}
                    >
                      <span>动作标签</span>
                      <Select
                        id={`segment-${segment.draftId}-label`}
                        aria-label={`第 ${index + 1} 个时间段动作标签`}
                        aria-invalid={errors.label ? true : undefined}
                        aria-describedby={
                          errors.label ? `segment-${segment.draftId}-label-error` : undefined
                        }
                        value={segment.label || undefined}
                        placeholder="选择标签"
                        disabled={!canSubmitRevision || !sortedLabels.length}
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
                              disabled={!canSubmitRevision}
                              onChange={(event) =>
                                updateSegment(
                                  segment.draftId,
                                  'start_ms',
                                  event.target.value === ''
                                    ? Number.NaN
                                    : Number(event.target.value),
                                )
                              }
                            />
                            <Button
                              type="text"
                              size="small"
                              htmlType="button"
                              className="capture-group-segment-set-current-btn"
                              disabled={!canUseCurrentPlaybackTime}
                              aria-label={`第 ${index + 1} 个时间段：使用当前时间作为开始`}
                              onClick={() => setSegmentPlaybackTime(segment.draftId, 'start_ms')}
                            >
                              设为当前
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
                              disabled={!canSubmitRevision}
                              onChange={(event) =>
                                updateSegment(
                                  segment.draftId,
                                  'end_ms',
                                  event.target.value === ''
                                    ? Number.NaN
                                    : Number(event.target.value),
                                )
                              }
                            />
                            <Button
                              type="text"
                              size="small"
                              htmlType="button"
                              className="capture-group-segment-set-current-btn"
                              disabled={!canUseCurrentPlaybackTime}
                              aria-label={`第 ${index + 1} 个时间段：使用当前时间作为结束`}
                              onClick={() => setSegmentPlaybackTime(segment.draftId, 'end_ms')}
                            >
                              设为当前
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
              disabled={!canSubmitRevision}
              onClick={handleAddSegment}
            >
              添加时间段
            </Button>
            <label className="capture-group-note-field">
              <span>修订备注（可选）</span>
              <Input.TextArea
                value={annotationNote}
                placeholder="输入修订说明或异常情况..."
                disabled={!canSubmitRevision}
                maxLength={1000}
                autoSize={{ minRows: 1, maxRows: 3 }}
                onChange={(event) => {
                  setIsDirty(true)
                  setAnnotationNote(event.target.value)
                }}
              />
            </label>
            <div className="capture-group-verdict-actions">
              <Button
                danger
                block
                size="middle"
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
