import { App as AntdApp, Alert, Button, Input, Select, Tag, Tooltip } from 'antd'
import { Check, CircleAlert, Play, Plus, Sparkles } from 'lucide-react'
import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'

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
  const [isDispatchingJob, setIsDispatchingJob] = useState(false)
  const trackBarRef = useRef<HTMLDivElement | null>(null)
  const [hasAutoLoadedAiDraft, setHasAutoLoadedAiDraft] = useState(false)
  const annotationFormRef = useRef<HTMLFormElement | null>(null)
  const [isDirty, setIsDirty] = useState(false)

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

  useEffect(() => {
    let isSubscribed = true
    fetchActionCandidates(projectId, captureGroupId)
      .then((candidates) => {
        if (isSubscribed) {
          setActionCandidates(candidates)
        }
      })
      .catch(() => {
        // AI candidates are auxiliary
      })

    fetchActionCandidateJob(projectId, captureGroupId)
      .then((job) => {
        if (!isSubscribed) return
        setActionJob(job)
        // Auto-dispatch if no job exists yet and stream is ready
        if (!job && group.receipt_state !== 'RECEIVING' && effectivePrimaryClipAssetId) {
          setIsDispatchingJob(true)
          dispatchActionCandidateJob(projectId, captureGroupId)
            .then((newJob) => {
              if (isSubscribed) setActionJob(newJob)
            })
            .catch(() => {
              // 4090 or worker may be offline
            })
            .finally(() => {
              if (isSubscribed) setIsDispatchingJob(false)
            })
        }
      })
      .catch(() => {
        // AI job is auxiliary
      })

    return () => {
      isSubscribed = false
    }
  }, [projectId, captureGroupId, group.receipt_state, effectivePrimaryClipAssetId])

  useEffect(() => {
    if (!actionJob || (actionJob.status !== 'QUEUED' && actionJob.status !== 'RUNNING')) {
      return
    }
    const timer = setInterval(() => {
      fetchActionCandidateJob(projectId, captureGroupId)
        .then((job) => {
          setActionJob(job)
          if (job?.status === 'SUCCEEDED') {
            fetchActionCandidates(projectId, captureGroupId)
              .then((candidates) => {
                setActionCandidates(candidates)
                void message.success('动作预标注任务已完成，已刷新候选列表，请逐段核对')
              })
              .catch(() => {})
          }
        })
        .catch(() => {})
    }, 3000)
    return () => clearInterval(timer)
  }, [actionJob, projectId, captureGroupId, message])

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
      let activeTaskId = task?.annotation_task_id
      if (!activeTaskId) {
        if (effectivePrimaryClipAssetId) {
          const existingReview = viewReviews[effectivePrimaryClipAssetId]
          if (existingReview?.decision !== 'QUALIFIED') {
            await recordQualityReview(projectId, effectivePrimaryClipAssetId, {
              decision: 'QUALIFIED',
              reason_code: 'MEETS_REQUIREMENTS',
              expected_version: existingReview?.version ?? 0,
            })
          }
        }

        let currentRevision = groupQuality?.revision ?? 0
        if (!currentGroupQualityAdmitting(groupQuality)) {
          const groupReview = await recordCaptureGroupQualityReview(
            projectId,
            captureGroupId,
            'QUALIFIED',
            '视频画面合格，AI检测通过，自动建单并开启动作标注',
            currentRevision,
          )
          currentRevision = groupReview.revision
        }

        const assigned = await assignCaptureGroupHumanLabelTask(
          projectId,
          captureGroupId,
          assigneeActorId || 'reviewer',
        )
        activeTaskId = assigned.annotation_task_id
        onTaskChange(assigned)

        if (
          !assigned.primary_clip_asset_id &&
          effectivePrimaryClipAssetId &&
          assigned.can_select_primary
        ) {
          const updated = await selectAnnotationPrimaryView(
            projectId,
            assigned.annotation_task_id,
            effectivePrimaryClipAssetId,
          )
          onTaskChange(updated)
        }
      }

      await submitAnnotationRevision(
        projectId,
        activeTaskId,
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
      void message.error(
        error instanceof AnnotationTaskApiError ? error.message : '修订暂未提交，请稍后重试。',
      )
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
        void message.success(`成功载入 ${candidates.length} 个动作预标注候选片段，请逐段核对`)
      } else {
        void message.info(
          fetched.length > 0
            ? '候选来源、标签或时间区间与当前任务不匹配，请继续人工标注。'
            : '暂无可用 AI 动作候选（4090 节点尚未完成推理），请稍候重试或进行人工标注。',
        )
      }
    } catch {
      void message.error('获取 AI 预标注候选失败，请稍后重试。')
    } finally {
      setIsLoadingAi(false)
    }
  }

  async function handleDispatchActionJob() {
    if (!effectivePrimaryClipAssetId) {
      void message.warning('请先指定主视图机位')
      return
    }
    setIsDispatchingJob(true)
    try {
      const job = await dispatchActionCandidateJob(projectId, captureGroupId)
      setActionJob(job)
      void message.success('已提交动作预标注任务，等待算力机返回结果...')
    } catch (err) {
      void message.error(
        err instanceof Error ? err.message : '启动动作预标注失败，请检查 4090 算力机与网络',
      )
    } finally {
      setIsDispatchingJob(false)
    }
  }

  function handleAdoptCandidate(candidate: ActionCandidate) {
    if (!canSubmitRevision) return
    setIsDirty(true)
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
            <StatusIndicator tone="neutral">动作核验中 · 提交后自动归档</StatusIndicator>
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
                  <StatusBadge tone="info">4090 推理中...</StatusBadge>
                ) : actionJob?.status === 'QUEUED' ? (
                  <StatusBadge tone="warning">4090 等待连接...</StatusBadge>
                ) : actionJob?.status === 'FAILED' ? (
                  <StatusBadge tone="error">算力机离线 / 未完成</StatusBadge>
                ) : (
                  <StatusBadge tone="default">就绪检测</StatusBadge>
                )}
              </div>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <Button
                  type="default"
                  size="small"
                  disabled={!canSubmitRevision || isDirty || Boolean(task?.annotation_revision)}
                  loading={isLoadingAi}
                  onClick={() => void handleLoadAiCandidates()}
                >
                  载入候选
                </Button>
                <Button
                  type={actionJob?.status === 'FAILED' ? 'primary' : 'default'}
                  danger={actionJob?.status === 'FAILED'}
                  size="small"
                  loading={isDispatchingJob}
                  onClick={() => void handleDispatchActionJob()}
                  icon={<Sparkles size={12} />}
                >
                  {actionJob?.status === 'SUCCEEDED' ? '重新检测' : '再次检测'}
                </Button>
              </div>
            </div>

            {actionCandidates.length > 0 ? (
              <div className="capture-group-ai-candidate-list" style={{ marginTop: 8 }}>
                {actionCandidates.map((candidate) => {
                  const style = getActionStyle(candidate.action_label)
                  const labelDef = schema?.labels.find((l) => l.label_id === candidate.action_label)
                  const labelName = labelDef?.display_name ?? candidate.action_label
                  const durationSeconds = ((candidate.end_ms - candidate.start_ms) / 1000).toFixed(
                    1,
                  )
                  const isAdopted = draftSegments.some(
                    (s) =>
                      s.label === candidate.action_label &&
                      s.start_ms === candidate.start_ms &&
                      s.end_ms === candidate.end_ms,
                  )
                  return (
                    <div
                      key={candidate.action_candidate_id}
                      className="capture-group-ai-candidate-card"
                    >
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 8,
                          flexWrap: 'wrap',
                        }}
                      >
                        <span
                          style={{
                            width: 8,
                            height: 8,
                            borderRadius: '50%',
                            backgroundColor: style.dot,
                            display: 'inline-block',
                          }}
                        />
                        <span style={{ fontWeight: 600 }}>{labelName}</span>
                        <Tag style={{ margin: 0 }}>{candidate.action_label}</Tag>
                        <span
                          style={{
                            color: 'var(--app-color-foreground-secondary)',
                            fontSize: 13,
                          }}
                        >
                          {formatHumanFriendlyTime(candidate.start_ms)} ~{' '}
                          {formatHumanFriendlyTime(candidate.end_ms)}
                          <span
                            style={{
                              color: 'var(--app-color-foreground-muted)',
                              marginLeft: 4,
                            }}
                          >
                            ({durationSeconds}s)
                          </span>
                        </span>
                        {typeof candidate.confidence === 'number' && (
                          <Tag color="blue" variant="filled" style={{ fontSize: 11, margin: 0 }}>
                            置信度 {Math.round(candidate.confidence * 100)}%
                          </Tag>
                        )}
                      </div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
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
                })}
              </div>
            ) : actionJob?.status === 'FAILED' ? (
              <div className="capture-group-ai-banner capture-group-ai-banner-failed">
                <span>
                  算力机服务暂未响应或预标注未完成，可点击“再次检测”重试，或直接进行人工标注。
                </span>
              </div>
            ) : actionJob?.status === 'QUEUED' ? (
              <div className="capture-group-ai-banner capture-group-ai-banner-queued">
                <span>任务已在队列等待，计算节点接入后将自动处理。</span>
              </div>
            ) : actionJob?.status === 'RUNNING' ? (
              <div className="capture-group-ai-banner">
                <span>AI 模型推理中，正在分析主视图视频动作切片，请稍候...</span>
              </div>
            ) : (
              <div className="capture-group-ai-banner">
                <span>
                  暂未生成动作候选建议。可点击“再次检测”发起 AI
                  分析，或点击“载入候选”获取服务端已有建议。
                </span>
              </div>
            )}
          </div>

          <div className="capture-group-form-heading">
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                width: '100%',
                flexWrap: 'wrap',
                gap: '4px 8px',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
                <h3 style={{ margin: 0, whiteSpace: 'nowrap' }}>动作时间段</h3>
                {draftSegments.length > 0 && (
                  <span
                    className="capture-group-annotation-summary-text"
                    style={{
                      fontSize: 12,
                      color: 'var(--app-color-foreground-muted)',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    ({draftSegments.length}段
                    {totalDurationMs ? ` · ${formatHumanFriendlyTime(totalDurationMs)}` : ''})
                  </span>
                )}
              </div>
              <Button
                type="default"
                size="small"
                htmlType="button"
                loading={isLoadingAi}
                disabled={!canSubmitRevision || isDirty || Boolean(task?.annotation_revision)}
                onClick={() => void handleLoadAiCandidates()}
                icon={<Sparkles size={12} style={{ color: 'var(--app-color-primary)' }} />}
                title={
                  isDirty || task?.annotation_revision
                    ? '保留当前人工草稿或历史修订，不能用 AI 候选覆盖'
                    : '将 AI 识别的动作片段一键载入为草稿'
                }
              >
                AI 自动填充
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
                  const schemaLabel = sortedLabels.find((l) => l.label_id === seg.label)
                  const displayName = schemaLabel?.display_name ?? style.label
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
            return (
              <div
                className={`capture-group-segment-row ${isPlayingActive ? 'is-playing-active' : ''}`}
                key={segment.draftId}
              >
                <div className="capture-group-segment-fields">
                  <div className="capture-group-segment-card-header">
                    <div className="capture-group-segment-badge-group">
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
                                event.target.value === '' ? Number.NaN : Number(event.target.value),
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
                          <label htmlFor={`segment-${segment.draftId}-end`}>结束时间（毫秒）</label>
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
                                event.target.value === '' ? Number.NaN : Number(event.target.value),
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
                type="primary"
                size="large"
                block
                htmlType="submit"
                aria-label="提交标注"
                loading={isSubmittingRevision}
                disabled={!canSubmitRevision}
              >
                提交标注
              </Button>
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
                  <span>
                    {schema?.labels.find((label) => label.label_id === segment.label)
                      ?.display_name ?? segment.label}
                  </span>
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
