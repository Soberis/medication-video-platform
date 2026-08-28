import { App as AntdApp, Alert, Button, Input, Select } from 'antd'
import { CircleAlert, LockKeyhole } from 'lucide-react'
import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'

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
import { paths } from '@/app/paths'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { Section } from '@/components/workspace/Section'
import {
  createDraftSegment,
  currentGroupQualityAdmitting,
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
  refreshVersion: number
  onTaskChange: (task: AnnotationTask) => void
  onRefresh: () => Promise<void>
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
  refreshVersion,
  onTaskChange,
  onRefresh,
}: CaptureGroupAnnotationPanelProps) {
  const { message } = AntdApp.useApp()
  const [isSelectingPrimary, setIsSelectingPrimary] = useState(false)
  const [draftSegments, setDraftSegments] = useState<DraftAnnotationSegment[]>([])
  const [segmentErrors, setSegmentErrors] = useState<Record<number, SegmentValidationErrors>>({})
  const [annotationNote, setAnnotationNote] = useState('')
  const [isSubmittingRevision, setIsSubmittingRevision] = useState(false)
  const appliedRefreshVersion = useRef<number | null>(null)
  const sortedLabels = useMemo(() => sortedSchemaLabels(schema), [schema])
  const groupQualityAdmitting = currentGroupQualityAdmitting(groupQuality)
  const groupQualityDowngraded =
    groupQuality?.result === 'NEEDS_REVIEW' || groupQuality?.result === 'REJECTED'
  const groupQualityReceiving = group.receipt_state === 'RECEIVING'
  const primaryLocked = Boolean(task?.annotation_revision)
  const canSubmitRevision = Boolean(
    task &&
      task.capture_group_id === captureGroupId &&
      task.status === 'ASSIGNED' &&
      task.can_submit &&
      task.primary_clip_asset_id &&
      groupQualityAdmitting &&
      !groupQualityDowngraded &&
      !groupQualityReceiving,
  )

  useEffect(() => {
    if (appliedRefreshVersion.current === refreshVersion) return
    appliedRefreshVersion.current = refreshVersion
    setDraftSegments(initialDraftSegments(task))
    setSegmentErrors({})
    setAnnotationNote('')
  }, [refreshVersion, task])

  const primaryOptions = viewRows.map((view) => {
    const decision = groupViewDecision(view, viewReviews)
    const enabled = Boolean(
      view.stream &&
        view.stream.stream_status === 'INGESTED' &&
        viewReviews[view.stream.clip_asset_id]?.decision === 'QUALIFIED',
    )
    return {
      value: view.stream?.clip_asset_id ?? `missing-${view.cameraNumber}`,
      label: `机位 ${view.cameraNumber} · ${view.member?.camera_role ?? '未命名'} · ${
        view.stream ? viewQualityLabels[decision] : '缺失'
      }`,
      disabled: !enabled,
    }
  })

  const annotationBlockingReason = groupQualityReceiving
    ? '接收仍在进行中，不能进入事件级标注准入。'
    : !groupQuality
      ? '当前事件质量结论暂不可用，不能判断事件级标注准入。'
      : !task
        ? taskVisibility === 'permission-limited'
          ? '当前账号不是该任务执行人；唯一配药事件任务状态按权限不可见。'
          : taskVisibility === 'unavailable'
            ? '唯一配药事件任务状态暂不可读，请刷新后重试。'
            : '事件质量通过后，服务端会自动创建唯一配药事件任务。'
        : !groupQualityAdmitting
          ? '当前事件质量为需复核或不合格；任务和历史仍保留，但不能提交新的修订。'
          : !task.primary_clip_asset_id
            ? task.can_select_primary
              ? '请选择一路已接收、媒体可用且机位质量合格的主视图。'
              : '当前账号或任务状态不允许选择主视图；请等待任务执行人处理。'
            : !task.can_submit
              ? '当前账号或任务状态不允许提交；请查看服务端任务状态。'
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
    setIsSelectingPrimary(true)
    try {
      const updatedTask = await selectAnnotationPrimaryView(
        projectId,
        task.annotation_task_id,
        clipAssetId,
      )
      onTaskChange(updatedTask)
      void message.success('主视图已保存；正式标注时间轴将以该机位为准。')
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
      void message.success('配药动作修订已提交；主视图现在锁定。')
      await onRefresh()
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

  return (
    <Section
      className="capture-group-annotation-section capture-group-workflow-focus-target"
      id="capture-group-annotation"
      tabIndex={-1}
      title="配药动作标注"
      titleId="capture-group-annotation-title"
      actions={<span className="capture-group-section-note">一个事件任务 · 一条修订链</span>}
    >
      {task ? (
        <>
          <div className="capture-group-task-banner">
            <div>
              <StatusIndicator tone={taskStatusTone(task)}>
                唯一配药事件任务 · {formatTaskStatus(task)}
              </StatusIndicator>
              <span>执行人：{task.assignee_actor_id}</span>
            </div>
            <Link
              className="text-link"
              to={paths.captureGroupFocus(projectId, captureGroupId, 'annotation')}
            >
              打开配药事件标注
            </Link>
          </div>
          {groupQualityDowngraded ? (
            <Alert
              type="warning"
              showIcon
              title={`当前事件质量为“${groupQualityLabels[groupQuality?.result ?? 'UNREVIEWED']}”`}
              description="任务和既有修订历史仍可审计；不能提交新的修订。恢复事件质量准入后再继续。"
            />
          ) : null}
          <div className="capture-group-primary-view-control">
            <div>
              <strong>主视图 · 正式标注时间基准</strong>
              <p>
                {primaryLocked
                  ? '首个正式修订已存在；主视图已锁定。'
                  : '在首个正式修订前选择一路符合服务端条件的机位。'}
              </p>
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
            {primaryLocked ? <LockKeyhole aria-label="主视图已锁定" size={17} /> : null}
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
                  <span className="app-eyebrow">正式标注时间基准</span>
                  <h3>配药动作时间段</h3>
                </div>
                <span>所有机位共享这一条事件级修订链，不创建按机位重复的标签。</span>
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
                          updateSegment(segment.draftId, 'start_ms', Number(event.target.value))
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
                          updateSegment(segment.draftId, 'end_ms', Number(event.target.value))
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
                        onChange={(value) => updateSegment(segment.draftId, 'label', value)}
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
                            current.filter((candidate) => candidate.draftId !== segment.draftId),
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
                  onClick={() => setDraftSegments((current) => [...current, createDraftSegment()])}
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
                <span>保留既有修订、复核和训练资格检查历史；等待服务端恢复事件级准入。</span>
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
                {task.annotation_revision.annotation_document.segments.map((segment, index) => (
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
                ))}
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
