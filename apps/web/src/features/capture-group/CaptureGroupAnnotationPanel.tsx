import { App as AntdApp, Alert, Button, Input, Select, Tooltip } from 'antd'
import { CircleAlert, Play } from 'lucide-react'
import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'

import { fetchActionCandidates, type ActionCandidate } from '@/api/action-candidate'
import {
  AnnotationTaskApiError,
  selectAnnotationPrimaryView,
  submitAnnotationRevision,
  type AnnotationLabelSchema,
  type AnnotationSegment,
  type AnnotationTask,
} from '@/api/annotation'
import type { CaptureGroup, CaptureGroupQualityReview } from '@/api/capture-groups'
import type { QualityReview } from '@/api/quality'
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
  normalizeActionLabel,
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
  taskVisibility: CaptureGroupTaskVisibility
  schema: AnnotationLabelSchema | null
  viewRows: GroupViewRow[]
  viewReviews: Record<string, QualityReview>
  currentPlaybackTimeMs: number | null
  totalDurationMs?: number | null
  hasNoHandDetected?: boolean
  onPlaySegment?: (startMs: number, endMs: number) => void
  onSeekToMs?: (timeMs: number) => void
  onTaskChange: (task: AnnotationTask) => void
  onDirtyChange?: (dirty: boolean) => void
  onRefresh: (options?: CaptureGroupDetailRefreshOptions) => Promise<boolean>
}

export function CaptureGroupAnnotationPanel({
  projectId,
  captureGroupId,
  group,
  groupQuality,
  task,
  taskVisibility,
  schema,
  viewRows,
  viewReviews,
  currentPlaybackTimeMs,
  totalDurationMs,
  hasNoHandDetected,
  onPlaySegment,
  onSeekToMs,
  onDirtyChange,
  onTaskChange,
  onRefresh,
}: CaptureGroupAnnotationPanelProps) {
  const { message } = AntdApp.useApp()
  const [isSelectingPrimary, setIsSelectingPrimary] = useState(false)
  const [draftSegments, setDraftSegments] = useState<DraftAnnotationSegment[]>([])
  const [segmentErrors, setSegmentErrors] = useState<Record<number, SegmentValidationErrors>>({})
  const [annotationNote, setAnnotationNote] = useState('')
  const [isSubmittingRevision, setIsSubmittingRevision] = useState(false)
  const [isLoadingAi, setIsLoadingAi] = useState(false)
  const [actionCandidates, setActionCandidates] = useState<ActionCandidate[]>([])
  const [hasAutoLoadedAiDraft, setHasAutoLoadedAiDraft] = useState(false)
  const annotationFormRef = useRef<HTMLFormElement | null>(null)
  const [isDirty, setIsDirty] = useState(false)
  const draftContext = [
    task?.annotation_task_id,
    task?.group_input_version,
    task?.primary_clip_asset_id,
    task?.annotation_revision?.annotation_revision_id,
  ].join(':')
  const [appliedContext, setAppliedContext] = useState<string | null>(null)
  const contextChanged = isDirty && appliedContext !== draftContext
  const sortedLabels = useMemo(() => sortedSchemaLabels(schema), [schema])
  const groupQualityAdmitting = currentGroupQualityAdmitting(groupQuality)
  const groupQualityDowngraded =
    groupQuality?.result === 'NEEDS_REVIEW' || groupQuality?.result === 'REJECTED'
  const groupQualityReceiving = group.receipt_state === 'RECEIVING'
  const primaryLocked = Boolean(task?.annotation_revision)
  const primaryCameraNumber =
    task?.annotation_revision?.primary_camera_number ??
    task?.primary_camera_number ??
    viewRows.find((view) => view.stream?.clip_asset_id === task?.primary_clip_asset_id)
      ?.cameraNumber ??
    null
  const canSubmitRevision = Boolean(
    task &&
      !contextChanged &&
      task.capture_group_id === captureGroupId &&
      task.status === 'ASSIGNED' &&
      task.can_submit &&
      task.primary_clip_asset_id &&
      groupQualityAdmitting &&
      !groupQualityDowngraded &&
      !groupQualityReceiving &&
      sortedLabels.length > 0,
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
    return () => {
      isSubscribed = false
    }
  }, [projectId, captureGroupId])

  if (!isDirty && appliedContext !== draftContext) {
    setAppliedContext(draftContext)
    const initial = initialDraftSegments(task, actionCandidates, schema)
    setDraftSegments(initial)
    setSegmentErrors({})
    setAnnotationNote('')
    if (actionCandidates.length > 0 && !task?.annotation_revision) {
      setHasAutoLoadedAiDraft(true)
    }
  }

  useEffect(() => {
    if (
      !isDirty &&
      task &&
      !task.annotation_revision &&
      actionCandidates.length > 0 &&
      !hasAutoLoadedAiDraft
    ) {
      setDraftSegments(initialDraftSegments(task, actionCandidates, schema))
      setHasAutoLoadedAiDraft(true)
    }
  }, [isDirty, task, actionCandidates, hasAutoLoadedAiDraft, schema])

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
    : !groupQuality
      ? '当前整体质量结论暂不可用，不能判断人工标签是否可开始。'
      : !task
        ? taskVisibility === 'permission-limited'
          ? '当前账号不是该任务执行人；配药动作标注任务状态按权限不可见。'
          : taskVisibility === 'unavailable'
            ? '配药动作标注任务状态暂不可读，请刷新后重试。'
            : '整体质量确认后，可按需开始配药动作标注。'
        : !groupQualityAdmitting
          ? '当前整体质量为需复核或不合格；任务和历史仍保留，但不能提交新的修订。'
          : !task.primary_clip_asset_id
            ? task.can_select_primary
              ? '请选择一路已接收、媒体可用且机位质量合格的主视图。'
              : '当前账号或任务状态不允许选择主视图；请等待任务执行人处理。'
            : !task.can_submit
              ? '当前账号或任务状态不允许提交；请查看服务端任务状态。'
              : !sortedLabels.length
                ? '标签规范暂时无法读取，当前不能提交新的修订。'
                : task.status !== 'ASSIGNED'
                  ? `当前任务状态：${formatTaskStatus(task)}。`
                  : ''

  async function choosePrimaryView(clipAssetId: string) {
    if (
      !task ||
      !task.can_select_primary ||
      primaryLocked ||
      task.primary_clip_asset_id === clipAssetId ||
      isSelectingPrimary
    ) {
      return
    }
    if (isDirty && !window.confirm('切换标注主视图会丢弃当前未提交的时间段和备注，继续？')) return
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
    if (!canSubmitRevision) return
    setIsLoadingAi(true)
    try {
      const candidates =
        actionCandidates.length > 0
          ? actionCandidates
          : await fetchActionCandidates(projectId, captureGroupId)
      if (candidates.length > 0) {
        setActionCandidates(candidates)
        setIsDirty(true)
        setDraftSegments(
          candidates.map((candidate) =>
            createDraftSegment({
              start_ms: candidate.start_ms,
              end_ms: candidate.end_ms,
              label: normalizeActionLabel(candidate.action_label, schema),
            }),
          ),
        )
        setHasAutoLoadedAiDraft(true)
        void message.success(`成功载入 ${candidates.length} 个 AI 预标注动作`)
      } else {
        void message.info('暂无可用 AI 预标注候选；请确保 4090 Action Worker 已推理该视频。')
      }
    } catch {
      void message.error('获取 AI 预标注候选失败，请稍后重试。')
    } finally {
      setIsLoadingAi(false)
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
      {task ? (
        <>
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
              <StatusIndicator tone={taskStatusTone(task)}>
                {formatTaskStatus(task)}
              </StatusIndicator>
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
          {viewRows.length > 1 || !task.primary_clip_asset_id ? (
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
                value={task.primary_clip_asset_id ?? undefined}
                placeholder="选择主视图"
                disabled={
                  primaryLocked ||
                  isSelectingPrimary ||
                  !groupQualityAdmitting ||
                  !task.can_select_primary
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
          {task.status === 'ASSIGNED' ? (
            <form
              ref={annotationFormRef}
              className="capture-group-annotation-form"
              onSubmit={(event) => void submitRevision(event)}
            >
              <div className="capture-group-form-heading">
                <div>
                  <h3>动作时间段</h3>
                </div>
              </div>
              {hasAutoLoadedAiDraft && actionCandidates.length > 0 && !task.annotation_revision && (
                <div style={{ marginBottom: 16 }}>
                  <Alert
                    type="info"
                    showIcon
                    message={`已自动载入 Qwen3-VL 动作预标注草稿（共 ${actionCandidates.length} 个候选动作），请在时间轴上核对起止时间与标签后提交。`}
                  />
                </div>
              )}
              {hasNoHandDetected && (
                <div style={{ marginBottom: 16 }}>
                  <Alert
                    type="warning"
                    showIcon
                    message="机器检测（MediaPipe）未发现手部动作"
                    description="若本视频确为无操作空置视频，无需进行动作标注与修订提交，可直接跳过。"
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
              <div className="capture-group-annotation-toolbar">
                <div className="capture-group-annotation-summary-info">
                  <span className="app-eyebrow">草稿概览</span>
                  <span className="capture-group-annotation-summary-text">
                    已载入 <strong>{draftSegments.length}</strong> 个动作片段
                    {totalDurationMs ? ` · 全片 ${formatHumanFriendlyTime(totalDurationMs)}` : ''}
                  </span>
                </div>
                <div className="capture-group-annotation-quick-buttons">
                  <Button
                    type="default"
                    size="small"
                    htmlType="button"
                    loading={isLoadingAi}
                    disabled={!canSubmitRevision || Boolean(hasNoHandDetected)}
                    onClick={() => void handleLoadAiCandidates()}
                    title={
                      hasNoHandDetected
                        ? '机器检测未发现手部动作，无需动作标注'
                        : '将 Qwen3-VL 识别的动作片段一键载入为草稿'
                    }
                  >
                    AI 自动填充
                  </Button>
                  <Button
                    type="default"
                    size="small"
                    htmlType="button"
                    disabled={!canSubmitRevision}
                    onClick={handleAddSegment}
                  >
                    + 添加时间段
                  </Button>
                  <Button
                    type="primary"
                    size="small"
                    htmlType="button"
                    loading={isSubmittingRevision}
                    disabled={!canSubmitRevision}
                    onClick={() => {
                      if (typeof annotationFormRef.current?.requestSubmit === 'function') {
                        annotationFormRef.current.requestSubmit()
                      } else {
                        annotationFormRef.current?.dispatchEvent(
                          new Event('submit', { cancelable: true, bubbles: true }),
                        )
                      }
                    }}
                  >
                    一键提交修订
                  </Button>
                </div>
              </div>
              {totalDurationMs && totalDurationMs > 0 && draftSegments.length > 0 ? (
                <div className="capture-group-timeline-overview" aria-label="全片动作时间轨">
                  <div
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
                          <strong className="capture-group-segment-index-title">时间段 {index + 1}</strong>
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
                              type="default"
                              size="small"
                              htmlType="button"
                              icon={<Play size={12} fill="currentColor" />}
                              onClick={() => onPlaySegment(segment.start_ms, segment.end_ms)}
                              title={`播放 ${formatHumanFriendlyTime(segment.start_ms)} ~ ${formatHumanFriendlyTime(segment.end_ms)}`}
                            >
                              播放片段
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
                        <legend>时间范围</legend>
                        <div className="capture-group-segment-time-controls">
                          <div className="capture-group-segment-time-field">
                            <label htmlFor={`segment-${segment.draftId}-start`}>
                              开始时间（毫秒）
                            </label>
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
                            <div className="capture-group-segment-time-helper">
                              {formattedStart ? (
                                <span
                                  id={startReadableId}
                                  className="capture-group-segment-time-readable"
                                >
                                  {formattedStart}
                                </span>
                              ) : <span />}
                              <Button
                                type="text"
                                size="small"
                                htmlType="button"
                                disabled={!canUseCurrentPlaybackTime}
                                aria-label={`第 ${index + 1} 个时间段：使用当前时间作为开始`}
                                onClick={() => setSegmentPlaybackTime(segment.draftId, 'start_ms')}
                              >
                                设为开始
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
                            <label htmlFor={`segment-${segment.draftId}-end`}>
                              结束时间（毫秒）
                            </label>
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
                            <div className="capture-group-segment-time-helper">
                              {formattedEnd ? (
                                <span
                                  id={endReadableId}
                                  className="capture-group-segment-time-readable"
                                >
                                  {formattedEnd}
                                </span>
                              ) : <span />}
                              <Button
                                type="text"
                                size="small"
                                htmlType="button"
                                disabled={!canUseCurrentPlaybackTime}
                                aria-label={`第 ${index + 1} 个时间段：使用当前时间作为结束`}
                                onClick={() => setSegmentPlaybackTime(segment.draftId, 'end_ms')}
                              >
                                设为结束
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
                  type="text"
                  htmlType="button"
                  disabled={!canSubmitRevision}
                  onClick={handleAddSegment}
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
                    onChange={(event) => {
                      setIsDirty(true)
                      setAnnotationNote(event.target.value)
                    }}
                  />
                </label>
                <Button
                  type="primary"
                  htmlType="submit"
                  loading={isSubmittingRevision}
                  disabled={!canSubmitRevision}
                >
                  提交修订
                </Button>
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
                  提交修订
                </Button>
              </div>
            </div>
          ) : null}
          {task.annotation_revision ? (
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
        </>
      ) : (
        <p className="form-hint">{annotationBlockingReason}</p>
      )}
    </Section>
  )
}
