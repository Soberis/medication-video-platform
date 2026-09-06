import { App as AntdApp, Alert, Button, Input, Select } from 'antd'
import { CircleAlert } from 'lucide-react'
import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'

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
  formatTaskStatus,
  groupViewDecision,
  initialDraftSegments,
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

  if (!isDirty && appliedContext !== draftContext) {
    setAppliedContext(draftContext)
    setDraftSegments(initialDraftSegments(task))
    setSegmentErrors({})
    setAnnotationNote('')
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
                return (
                  <div className="capture-group-segment-row" key={segment.draftId}>
                    <div className="capture-group-segment-fields">
                      <span className="capture-group-segment-heading">时间段 {index + 1}</span>
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
                            {formattedStart ? (
                              <span
                                id={startReadableId}
                                className="capture-group-segment-time-readable"
                              >
                                {formattedStart}
                              </span>
                            ) : null}
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
                            {formattedEnd ? (
                              <span
                                id={endReadableId}
                                className="capture-group-segment-time-readable"
                              >
                                {formattedEnd}
                              </span>
                            ) : null}
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
                            {errors.end ? (
                              <small id={endErrorId} role="alert">
                                {errors.end}
                              </small>
                            ) : null}
                          </div>
                        </div>
                      </fieldset>
                    </div>
                    {draftSegments.length > 1 ? (
                      <div className="capture-group-segment-actions">
                        <Button
                          type="text"
                          danger
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
                      </div>
                    ) : null}
                  </div>
                )
              })}
              <div className="capture-group-annotation-form-actions">
                <Button
                  type="text"
                  htmlType="button"
                  disabled={!canSubmitRevision}
                  onClick={() => {
                    setIsDirty(true)
                    setDraftSegments((current) => [...current, createDraftSegment()])
                  }}
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
