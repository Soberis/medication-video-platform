import { Alert, App as AntdApp, Button, Form, Input, InputNumber, Modal, Select } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import {
  AnnotationTaskApiError,
  getAnnotationLabelSchema,
  listAnnotationTasks,
  recheckAnnotationPostCleaning,
  reviewAnnotationRevision,
  submitAnnotationRevision,
  type AnnotationLabelSchema,
  type AnnotationSegment,
  type AnnotationTask,
  type AnnotationTaskProgress,
} from '@/api/annotation'
import {
  downloadTrainingData,
  getTrainingDataExportPreview,
  TrainingDataExportApiError,
  type TrainingDataExportPreview,
} from '@/api/training-data'
import { paths } from '@/app/paths'
import { PageHeader } from '@/components/PageHeader'
import { MetadataList } from '@/components/data/MetadataList'
import { StatusIndicator, type StatusIndicatorTone } from '@/components/status/StatusIndicator'
import { EmptyState, ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { Section } from '@/components/workspace/Section'
import { formatDateTime, shortId } from '@/features/catalog/catalog-presentation'

interface DraftAnnotationSegment extends AnnotationSegment {
  readonly draftId: number
}
const postCleaningReasonLabels: Readonly<Record<string, string>> = {
  REVIEW_NOT_APPROVED: '未绑定已通过的管理员复核',
  QUALITY_REVIEW_NOT_QUALIFIED: '当前人工质量结论不是“合格”',
  ASSET_INPUT_DIGEST_INVALID: '视频输入摘要无效',
  ANNOTATION_DOCUMENT_DIGEST_INVALID: '标注文档摘要无效',
  ANNOTATION_DOCUMENT_DIGEST_MISMATCH: '标注文档内容与摘要不一致',
  LABEL_SCHEMA_MISMATCH: '任务与修订绑定的标签规范不一致',
  ANNOTATION_SEGMENTS_MISSING: '缺少主流程时间段',
  SEGMENT_TIME_RANGE_INVALID: '存在无效的时间范围',
  PRIMARY_STEP_LABEL_NOT_ALLOWED: '存在标签规范未允许的主流程标签',
  PRIMARY_STEP_OVERLAP: '主流程时间段存在重叠',
  VERIFIED_MEDIA_DURATION_UNAVAILABLE: '缺少已确认的视频时长',
  VERIFIED_MEDIA_DURATION_INVALID: '已确认的视频时长无效',
  VERIFIED_MEDIA_FACT_INCOMPLETE: '媒体检测事实不完整',
  VERIFIED_MEDIA_INPUT_DIGEST_MISMATCH: '媒体检测输入摘要与视频资产不一致',
  SEGMENT_EXCEEDS_VERIFIED_MEDIA_DURATION: '时间段超出已确认的视频时长',
  LEGACY_APPROVED_REVISION_REQUIRES_REVALIDATION: '历史通过记录需要按当前规则重新验证',
}
let nextDraftSegmentId = 0
function createDraftSegment(): DraftAnnotationSegment {
  nextDraftSegmentId += 1
  return { draftId: nextDraftSegmentId, start_ms: 0, end_ms: 1000, label: '' }
}
function taskStatusLabel(task: AnnotationTask) {
  if (task.status === 'COMPLETED') return '标注完成'
  if (task.status === 'SUBMITTED') return '待复核'
  return task.annotation_revision?.review?.decision === 'REJECTED' ? '待修改' : '待标注'
}
function taskStatusTone(task: AnnotationTask): StatusIndicatorTone {
  if (task.status === 'COMPLETED') return 'success'
  if (task.annotation_revision?.review?.decision === 'REJECTED') return 'warning'
  return 'information'
}
function taskNoActionLabel(task: AnnotationTask) {
  if (task.status === 'COMPLETED') return '当前无需操作'
  if (task.status === 'SUBMITTED') return '等待管理员复核'
  return '等待处理'
}
function postCleaningPresentation(task: AnnotationTask): {
  label: string
  tone: StatusIndicatorTone
  description: string
  reasons: string[]
} | null {
  const revision = task.annotation_revision
  if (revision?.review?.decision !== 'APPROVED') return null
  const result = revision.post_cleaning_result
  const reasons = (result?.reason_codes ?? []).map(
    (code) =>
      postCleaningReasonLabels[code] ??
      `其他清洗原因（${/^[A-Z][A-Z0-9_]{0,63}$/.test(code) ? code : 'UNKNOWN_REASON'}）`,
  )
  if (result?.outcome === 'PASS')
    return {
      label: '已通过清洗',
      tone: 'success',
      description: '当前规则检查通过；实际训练行数以服务端导出预览为准。',
      reasons,
    }
  if (result?.outcome === 'WARNING')
    return {
      label: '需处理',
      tone: 'warning',
      description: '当前数据暂不可用于训练，请补充事实后重新核对清洗。',
      reasons,
    }
  if (result?.outcome === 'REJECT')
    return {
      label: '未通过',
      tone: 'destructive',
      description: '当前数据未通过清洗，暂不可用于训练；视频资产未被删除。',
      reasons,
    }
  return {
    label: '待清洗',
    tone: 'neutral',
    description: '尚未形成可用于训练的清洗结论。',
    reasons,
  }
}

function reviewStatusTone(decision: 'APPROVED' | 'REJECTED'): StatusIndicatorTone {
  return decision === 'APPROVED' ? 'success' : 'destructive'
}

export default function AnnotationTaskListView() {
  const { projectId = '' } = useParams()
  const { message, modal } = AntdApp.useApp()
  const [items, setItems] = useState<AnnotationTask[]>([])
  const [progress, setProgress] = useState<AnnotationTaskProgress | null>(null)
  const [labelSchema, setLabelSchema] = useState<AnnotationLabelSchema | null>(null)
  const sortedLabels = useMemo(
    () =>
      [...(labelSchema?.labels ?? [])].sort(
        (a, b) => a.sort_order - b.sort_order || a.label_id.localeCompare(b.label_id),
      ),
    [labelSchema],
  )
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [selectedTask, setSelectedTask] = useState<AnnotationTask | null>(null)
  const [isSubmissionOpen, setIsSubmissionOpen] = useState(false)
  const [segments, setSegments] = useState<DraftAnnotationSegment[]>([])
  const [note, setNote] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [reviewingRevisionId, setReviewingRevisionId] = useState<string | null>(null)
  const [recheckingRevisionId, setRecheckingRevisionId] = useState<string | null>(null)
  const [trainingExportPreview, setTrainingExportPreview] =
    useState<TrainingDataExportPreview | null>(null)
  const [isTrainingExportOpen, setIsTrainingExportOpen] = useState(false)
  const [isTrainingPreviewLoading, setIsTrainingPreviewLoading] = useState(false)
  const [isTrainingDownloadLoading, setIsTrainingDownloadLoading] = useState(false)
  const activeRequest = useRef<AbortController | null>(null)
  const trainingExportRequest = useRef<AbortController | null>(null)

  const loadTasks = useCallback(async () => {
    activeRequest.current?.abort()
    const request = new AbortController()
    activeRequest.current = request
    setLoadState('loading')
    setProgress(null)
    setLabelSchema(null)
    setErrorCode('')
    setErrorMessage('')
    try {
      const [taskList, schema] = await Promise.all([
        listAnnotationTasks(projectId, request.signal),
        getAnnotationLabelSchema(projectId, request.signal),
      ])
      if (activeRequest.current !== request) return
      setItems(taskList.items)
      setProgress(taskList.progress)
      setLabelSchema(schema)
      setLoadState('success')
    } catch (error) {
      if (request.signal.aborted || activeRequest.current !== request) return
      setLoadState('error')
      setErrorCode(
        error instanceof AnnotationTaskApiError
          ? error.errorCode
          : 'ANNOTATION_TASK_REQUEST_FAILED',
      )
      setErrorMessage(
        error instanceof AnnotationTaskApiError
          ? error.message
          : '暂时无法读取配药动作标注，请稍后重试。',
      )
    } finally {
      if (activeRequest.current === request) activeRequest.current = null
    }
  }, [projectId])
  useEffect(() => {
    const timer = window.setTimeout(() => void loadTasks(), 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
      trainingExportRequest.current?.abort()
    }
  }, [loadTasks])

  const openTrainingExport = useCallback(async () => {
    if (progress?.scope !== 'PROJECT' || isTrainingPreviewLoading || isTrainingDownloadLoading)
      return
    trainingExportRequest.current?.abort()
    const request = new AbortController()
    trainingExportRequest.current = request
    setIsTrainingPreviewLoading(true)
    try {
      const preview = await getTrainingDataExportPreview(projectId, request.signal)
      if (request.signal.aborted || trainingExportRequest.current !== request) return
      setTrainingExportPreview(preview)
      setIsTrainingExportOpen(true)
    } catch (error) {
      if (request.signal.aborted || trainingExportRequest.current !== request) return
      void message.error(
        error instanceof TrainingDataExportApiError
          ? error.message
          : '暂时无法读取训练数据导出信息，请稍后重试。',
      )
    } finally {
      if (trainingExportRequest.current === request) {
        trainingExportRequest.current = null
        setIsTrainingPreviewLoading(false)
      }
    }
  }, [isTrainingDownloadLoading, isTrainingPreviewLoading, message, progress?.scope, projectId])

  const closeTrainingExport = useCallback(() => {
    if (isTrainingDownloadLoading) return
    setIsTrainingExportOpen(false)
    setTrainingExportPreview(null)
  }, [isTrainingDownloadLoading])

  const downloadTrainingDataFile = useCallback(async () => {
    if (
      !trainingExportPreview ||
      trainingExportPreview.training_row_count <= 0 ||
      isTrainingDownloadLoading
    )
      return
    const request = new AbortController()
    trainingExportRequest.current = request
    setIsTrainingDownloadLoading(true)
    try {
      const blob = await downloadTrainingData(projectId, request.signal)
      const objectUrl = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = objectUrl
      link.download = 'training_data.parquet'
      link.rel = 'noopener'
      link.hidden = true
      document.body.append(link)
      try {
        link.click()
      } finally {
        URL.revokeObjectURL(objectUrl)
        link.remove()
      }
      setIsTrainingExportOpen(false)
      setTrainingExportPreview(null)
      void message.success('已开始下载 training_data.parquet。')
    } catch (error) {
      if (request.signal.aborted) return
      void message.error(
        error instanceof TrainingDataExportApiError
          ? error.message
          : '训练数据暂未导出，请稍后重试。',
      )
    } finally {
      if (trainingExportRequest.current === request) trainingExportRequest.current = null
      setIsTrainingDownloadLoading(false)
    }
  }, [isTrainingDownloadLoading, message, projectId, trainingExportPreview])

  function openSubmission(task: AnnotationTask) {
    if (!task.can_submit || task.status !== 'ASSIGNED') return
    if (
      !labelSchema ||
      labelSchema.label_schema_id !== task.label_schema_id ||
      labelSchema.schema_digest !== task.label_schema_digest
    ) {
      void message.error('任务绑定的标签规范与当前项目不一致，请刷新后重试。')
      return
    }
    setSelectedTask(task)
    setIsSubmissionOpen(true)
    setSegments([createDraftSegment()])
    setNote('')
  }
  function updateSegment(index: number, patch: Partial<AnnotationSegment>) {
    setSegments((current) =>
      current.map((segment, currentIndex) =>
        currentIndex === index ? { ...segment, ...patch } : segment,
      ),
    )
  }
  async function submit() {
    const task = selectedTask
    const schema = labelSchema
    if (!task || !schema || isSubmitting) return
    if (
      schema.label_schema_id !== task.label_schema_id ||
      schema.schema_digest !== task.label_schema_digest
    ) {
      void message.error('任务绑定的标签规范已发生变化，请关闭窗口并刷新后重试。')
      return
    }
    const validSegments = segments.map(({ start_ms, end_ms, label }) => ({
      start_ms,
      end_ms,
      label: label.trim(),
    }))
    if (
      validSegments.some(
        (segment) => !segment.label || segment.start_ms < 0 || segment.end_ms <= segment.start_ms,
      )
    ) {
      void message.error('请填写标签，并确保每个时间段的结束时间晚于开始时间。')
      return
    }
    setIsSubmitting(true)
    try {
      await submitAnnotationRevision(projectId, task.annotation_task_id, validSegments, note)
      void message.success('配药动作标注已提交，等待管理员复核。')
      setIsSubmissionOpen(false)
      await loadTasks()
    } catch (error) {
      void message.error(
        error instanceof AnnotationTaskApiError
          ? error.message
          : '配药动作标注暂未提交，请稍后重试。',
      )
    } finally {
      setIsSubmitting(false)
    }
  }
  function review(task: AnnotationTask, decision: 'APPROVED' | 'REJECTED') {
    const revision = task.annotation_revision
    if (!revision || !task.can_review || reviewingRevisionId) return
    const approved = decision === 'APPROVED'
    let reviewNote = ''
    modal.confirm({
      title: approved ? '确认配药动作标注复核通过' : '确认配药动作标注复核不通过',
      content: approved ? (
        '确认时间段标注与视频画面一致，并将其作为正式人工配药动作标注？'
      ) : (
        <div className="annotation-review-confirmation">
          <p>确认该配药动作标注不符合要求？本次将记录为不通过，并退回给标注人修改。</p>
          <Input.TextArea
            aria-label="退回修改说明（可选）"
            maxLength={1000}
            rows={3}
            placeholder="补充需要修改的内容（可选）"
            onChange={(event) => {
              reviewNote = event.target.value
            }}
          />
        </div>
      ),
      okText: approved ? '确认通过' : '确认不通过',
      cancelText: '取消',
      okButtonProps: { danger: !approved },
      async onOk() {
        setReviewingRevisionId(revision.annotation_revision_id)
        try {
          await reviewAnnotationRevision(
            projectId,
            revision.annotation_revision_id,
            decision,
            reviewNote,
          )
          void message.success(
            approved ? '配药动作标注已通过管理员复核。' : '已记录配药动作标注未通过。',
          )
          await loadTasks()
        } catch (error) {
          void message.error(
            error instanceof AnnotationTaskApiError ? error.message : '复核暂未提交，请稍后重试。',
          )
          throw error
        } finally {
          setReviewingRevisionId(null)
        }
      },
    })
  }
  function recheckPostCleaning(task: AnnotationTask) {
    const revision = task.annotation_revision
    if (!revision || !task.can_recheck_post_cleaning || recheckingRevisionId) return
    modal.confirm({
      title: '重新核对清洗',
      content:
        '将按当前人工质量、视频和标注事实重新生成清洗结果；历史结果会保留，不会修改已通过的标注。',
      okText: '确认重新核对',
      cancelText: '取消',
      async onOk() {
        setRecheckingRevisionId(revision.annotation_revision_id)
        try {
          await recheckAnnotationPostCleaning(projectId, revision.annotation_revision_id)
          void message.success('已按当前事实重新核对清洗结果。')
          await loadTasks()
        } catch (error) {
          void message.error(
            error instanceof AnnotationTaskApiError
              ? error.message
              : '暂时无法重新核对清洗，请稍后重试。',
          )
          throw error
        } finally {
          setRecheckingRevisionId(null)
        }
      },
    })
  }

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑导航">
        <Link to={paths.videos(projectId)}>视频管理</Link>
        <span aria-hidden="true">/</span>
        <span>配药动作标注</span>
      </nav>
      <PageHeader
        titleId="annotation-task-title"
        title="配药动作标注"
        actions={
          <>
            {loadState === 'success' && progress?.scope === 'PROJECT' ? (
              <Button
                size="small"
                loading={isTrainingPreviewLoading}
                disabled={isTrainingDownloadLoading}
                onClick={() => void openTrainingExport()}
              >
                导出训练数据
              </Button>
            ) : null}
            <Button size="small" loading={loadState === 'loading'} onClick={() => void loadTasks()}>
              刷新
            </Button>
          </>
        }
      />
      {loadState === 'loading' ? <LoadingState>正在读取配药动作标注任务…</LoadingState> : null}
      {loadState === 'error' ? (
        <ErrorState code={errorCode} onRetry={() => void loadTasks()}>
          {errorMessage}
        </ErrorState>
      ) : null}
      {loadState === 'success' && progress ? (
        <section
          className="annotation-task-overview"
          aria-label={
            progress.scope === 'PROJECT' ? '项目配药动作标注进度' : '我的配药动作标注进度'
          }
        >
          <strong>{progress.total} 个任务</strong>
          <span aria-hidden="true">·</span>
          <span>
            {progress.total > 0 && progress.completed === progress.total
              ? '全部完成'
              : `${progress.completed} 个已完成 · ${progress.awaiting_submission + progress.rework_required + progress.awaiting_review} 个待处理`}
          </span>
        </section>
      ) : null}
      {loadState === 'success' && items.length ? (
        <Section
          className="annotation-task-queue"
          title="任务队列"
          titleId="annotation-task-queue-title"
        >
          <div
            className="annotation-task-list-surface working-surface"
            role="list"
            aria-label="配药动作标注任务列表"
          >
            {items.map((row) => {
              const revision = row.annotation_revision
              const presentation = postCleaningPresentation(row)
              const submissionActorText = revision
                ? row.assignee_actor_id === revision.submitted_by
                  ? `提交人 ${revision.submitted_by}`
                  : `执行人 ${row.assignee_actor_id} · 提交人 ${revision.submitted_by}`
                : null
              const taskDetails = (
                <details className="task-details">
                  <summary>查看详情</summary>
                  <div className="task-details-content">
                    <MetadataList
                      className="task-details-metadata"
                      ariaLabel={`视频 ${shortId(row.clip_asset_id)} 的任务详情`}
                      items={[
                        {
                          id: 'task-id',
                          label: '任务 ID',
                          value: (
                            <code title={row.annotation_task_id}>
                              {shortId(row.annotation_task_id)}
                            </code>
                          ),
                        },
                        { id: 'created-by', label: '创建者', value: row.created_by },
                        {
                          id: 'created-at',
                          label: '创建时间',
                          value: formatDateTime(row.created_at),
                        },
                      ]}
                    />
                    {revision ? (
                      <div className="task-details-block">
                        <h3>标注时间段</h3>
                        <ol className="task-annotation-list">
                          {revision.annotation_document.segments.map((segment, index) => {
                            const label =
                              labelSchema?.labels.find((item) => item.label_id === segment.label)
                                ?.display_name ?? segment.label
                            return (
                              <li key={`${segment.start_ms}-${segment.end_ms}-${segment.label}`}>
                                <span className="task-annotation-range">
                                  时间段 {index + 1} · {segment.start_ms}–{segment.end_ms} 毫秒
                                </span>
                                <span>{label}</span>
                              </li>
                            )
                          })}
                        </ol>
                        {revision.annotation_document.note ? (
                          <p className="task-annotation-note">
                            标注备注：{revision.annotation_document.note}
                          </p>
                        ) : null}
                      </div>
                    ) : null}
                    {presentation ? (
                      <div className="task-details-block">
                        <h3>清洗说明</h3>
                        <p>{presentation.description}</p>
                        {presentation.reasons.length ? (
                          <p>原因：{presentation.reasons.join('；')}</p>
                        ) : null}
                      </div>
                    ) : null}
                  </div>
                </details>
              )
              const taskAction =
                row.can_submit && row.status === 'ASSIGNED' ? (
                  <Button
                    type="link"
                    aria-label={`提交视频 ${row.clip_asset_id} 的配药动作标注`}
                    onClick={() => openSubmission(row)}
                  >
                    提交标注
                  </Button>
                ) : row.can_review && row.annotation_revision ? (
                  <>
                    <Button
                      type="link"
                      aria-label={`通过视频 ${row.clip_asset_id} 的配药动作标注复核`}
                      loading={
                        reviewingRevisionId === row.annotation_revision.annotation_revision_id
                      }
                      onClick={() => review(row, 'APPROVED')}
                    >
                      通过复核
                    </Button>
                    <Button
                      type="link"
                      danger
                      aria-label={`退回修改视频 ${row.clip_asset_id} 的配药动作标注`}
                      disabled={reviewingRevisionId !== null}
                      onClick={() => review(row, 'REJECTED')}
                    >
                      退回修改
                    </Button>
                  </>
                ) : row.can_recheck_post_cleaning && row.annotation_revision ? (
                  <Button
                    type="link"
                    aria-label={`重新核对视频 ${row.clip_asset_id} 的清洗`}
                    loading={
                      recheckingRevisionId === row.annotation_revision.annotation_revision_id
                    }
                    onClick={() => recheckPostCleaning(row)}
                  >
                    重新核对清洗
                  </Button>
                ) : null
              return (
                <article
                  aria-label={`视频 ${shortId(row.clip_asset_id)} · ${taskStatusLabel(row)}`}
                  className="annotation-task-card"
                  key={row.annotation_task_id}
                >
                  <header className="annotation-task-card-header">
                    <div className="annotation-task-identity">
                      <Link
                        className="text-link mono-wrap"
                        to={paths.asset(projectId, row.clip_asset_id)}
                        title={row.clip_asset_id}
                      >
                        视频 {shortId(row.clip_asset_id)}
                      </Link>
                    </div>
                    <StatusIndicator
                      className="annotation-task-card-status"
                      tone={taskStatusTone(row)}
                    >
                      {taskStatusLabel(row)}
                    </StatusIndicator>
                  </header>
                  <div className="annotation-task-card-content">
                    <div className="annotation-task-progress">
                      {revision ? (
                        <div className="task-evidence-summary">
                          <strong>
                            第 {revision.revision_number} 次提交 ·{' '}
                            {revision.annotation_document.segments.length} 个时间段
                          </strong>
                          <div className="task-evidence-statuses">
                            {revision.review ? (
                              <StatusIndicator
                                className="task-evidence-review"
                                tone={reviewStatusTone(revision.review.decision)}
                              >
                                复核 ·{' '}
                                {revision.review.decision === 'APPROVED' ? '已通过' : '未通过'}
                                {revision.review.reviewed_by === revision.submitted_by ? (
                                  <small className="task-evidence-self-review">
                                    <span aria-hidden="true">（</span>
                                    <span>管理员自审</span>
                                    <span aria-hidden="true">）</span>
                                  </small>
                                ) : null}
                              </StatusIndicator>
                            ) : (
                              <StatusIndicator tone="information">复核 · 待复核</StatusIndicator>
                            )}
                            {presentation ? (
                              <StatusIndicator tone={presentation.tone}>
                                清洗 · {presentation.label}
                              </StatusIndicator>
                            ) : null}
                          </div>
                          {revision.review?.review_note ? (
                            <span className="task-review-note">
                              复核意见：{revision.review.review_note}
                            </span>
                          ) : null}
                          <span className="cell-secondary">
                            提交于 {formatDateTime(revision.submitted_at)} · {submissionActorText}
                          </span>
                          {taskDetails}
                        </div>
                      ) : (
                        <div className="task-evidence-summary">
                          <span className="muted-text">尚未提交</span>
                          <span className="cell-secondary">
                            执行人{' '}
                            <span className="task-assignee-name">{row.assignee_actor_id}</span>
                          </span>
                          {taskDetails}
                        </div>
                      )}
                    </div>
                  </div>
                  <footer className="annotation-task-card-footer">
                    <div className="annotation-task-card-actions" aria-label="任务操作">
                      {taskAction ?? (
                        <span className="annotation-task-no-action">{taskNoActionLabel(row)}</span>
                      )}
                    </div>
                  </footer>
                </article>
              )
            })}
          </div>
        </Section>
      ) : null}
      {loadState === 'success' && !items.length ? (
        <EmptyState description="当前没有配药动作标注任务。质量合格的视频将由系统自动进入标注流程。" />
      ) : null}
      <Modal
        open={isTrainingExportOpen}
        title="训练数据导出"
        okText="导出"
        cancelText="取消"
        confirmLoading={isTrainingDownloadLoading}
        okButtonProps={{
          disabled: !trainingExportPreview?.training_row_count,
          'aria-label': '导出',
        }}
        cancelButtonProps={{ disabled: isTrainingDownloadLoading, 'aria-label': '取消' }}
        mask={{ closable: !isTrainingDownloadLoading }}
        keyboard={!isTrainingDownloadLoading}
        onCancel={closeTrainingExport}
        onOk={() => void downloadTrainingDataFile()}
        destroyOnHidden
      >
        {trainingExportPreview ? (
          <>
            <MetadataList
              className="training-data-export-summary"
              items={[
                {
                  id: 'training-row-count',
                  label: '训练行数',
                  value: `${trainingExportPreview.training_row_count} 条`,
                },
                { id: 'format', label: '格式', value: 'Parquet' },
                { id: 'includes', label: '包含', value: '手部关键点 + 人工动作标签' },
                {
                  id: 'includes-mp4',
                  label: '原始视频',
                  value: trainingExportPreview.includes_mp4 ? '包含' : '不包含',
                },
              ]}
            />
            {trainingExportPreview.training_row_count === 0 ? (
              <p className="training-data-export-empty" role="status">
                当前没有符合条件的训练行，暂时无法导出。
              </p>
            ) : null}
            <p className="form-hint">
              MediaPipe 候选仅作为手部观测数据，不是人工真值；动作标签来自人工正式标注。
            </p>
          </>
        ) : null}
      </Modal>
      <Modal
        open={isSubmissionOpen}
        title="提交配药动作时间段"
        width={720}
        footer={null}
        onCancel={() => setIsSubmissionOpen(false)}
        destroyOnHidden
      >
        {selectedTask && labelSchema ? (
          <div className="annotation-submission-context">
            <MetadataList
              ariaLabel="提交任务上下文"
              items={[
                {
                  id: 'submission-video',
                  label: '视频',
                  value: (
                    <Link
                      className="text-link"
                      to={paths.asset(projectId, selectedTask.clip_asset_id)}
                      title={selectedTask.clip_asset_id}
                    >
                      {shortId(selectedTask.clip_asset_id)}
                    </Link>
                  ),
                },
                {
                  id: 'submission-task',
                  label: '任务',
                  value: (
                    <code title={selectedTask.annotation_task_id}>
                      {shortId(selectedTask.annotation_task_id)}
                    </code>
                  ),
                },
                {
                  id: 'submission-schema',
                  label: '标签规范',
                  value: `${labelSchema.schema_key} · ${labelSchema.schema_version}`,
                },
              ]}
            />
            <p className="annotation-submission-state" role="status">
              当前状态：
              {selectedTask.annotation_revision?.review?.decision === 'REJECTED'
                ? '待修改'
                : '待标注'}
              。
              当前编辑仅保存在此窗口内，关闭窗口不会保存；正式提交后才会创建不可变修订并进入管理员复核。
            </p>
            {selectedTask.annotation_revision?.review?.review_note ? (
              <Alert
                className="annotation-rework-notice"
                type="warning"
                showIcon
                title="需要根据上次复核意见修改"
                description={selectedTask.annotation_revision.review.review_note}
              />
            ) : null}
            <details className="annotation-label-guide">
              <summary>查看标签说明</summary>
              <ul>
                {sortedLabels.map((label) => (
                  <li key={label.label_id}>
                    <strong>{label.display_name}</strong>
                    <span>{label.description}</span>
                  </li>
                ))}
              </ul>
            </details>
          </div>
        ) : null}
        <p className="form-hint">
          按项目标签规范记录一个或多个配药动作时间段。每个时间段必须有明确标签，且结束时间晚于开始时间。
        </p>
        <Form layout="vertical" aria-label="提交配药动作时间段" onFinish={() => void submit()}>
          {segments.map((segment, index) => (
            <div
              key={segment.draftId}
              className="annotation-segment-row"
              role="group"
              aria-label={`标注时间段 ${index + 1}`}
            >
              <Form.Item label="开始（毫秒）">
                <InputNumber
                  min={0}
                  step={100}
                  value={segment.start_ms}
                  onChange={(value) => updateSegment(index, { start_ms: value ?? 0 })}
                />
              </Form.Item>
              <Form.Item label="结束（毫秒）">
                <InputNumber
                  min={1}
                  step={100}
                  value={segment.end_ms}
                  onChange={(value) => updateSegment(index, { end_ms: value ?? 1 })}
                />
              </Form.Item>
              <Form.Item label="标签">
                <Select
                  value={segment.label || undefined}
                  placeholder="选择步骤标签"
                  aria-label={`标注时间段 ${index + 1} 的标签`}
                  onChange={(value) => updateSegment(index, { label: value })}
                  options={sortedLabels.map((label) => ({
                    value: label.label_id,
                    label: label.display_name,
                  }))}
                />
              </Form.Item>
              <Button
                aria-label={`移除标注时间段 ${index + 1}`}
                disabled={segments.length === 1}
                onClick={() =>
                  setSegments((current) =>
                    current.filter((_, currentIndex) => currentIndex !== index),
                  )
                }
              >
                移除
              </Button>
            </div>
          ))}
          <Button onClick={() => setSegments((current) => [...current, createDraftSegment()])}>
            新增片段
          </Button>
          <Form.Item label="备注（可选）">
            <Input.TextArea
              value={note}
              maxLength={1000}
              rows={3}
              onChange={(event) => setNote(event.target.value)}
            />
          </Form.Item>
          <div className="detail-actions annotation-submit-actions">
            <Button onClick={() => setIsSubmissionOpen(false)}>取消</Button>
            <Button htmlType="submit" type="primary" loading={isSubmitting}>
              提交配药动作标注
            </Button>
          </div>
        </Form>
      </Modal>
    </>
  )
}
