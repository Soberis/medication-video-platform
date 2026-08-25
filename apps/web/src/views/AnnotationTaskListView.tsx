import { App as AntdApp, Button, Modal } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'

import {
  AnnotationTaskApiError,
  getAnnotationLabelSchema,
  listAnnotationTasks,
  type AnnotationLabelSchema,
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
import {
  cleaningStatusLabels,
  formatDateTime,
  shortId,
} from '@/features/catalog/catalog-presentation'

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
  evaluatedAt: string | null
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
      label: cleaningStatusLabels.PASS,
      tone: 'success',
      description: '当前规则检查通过；实际训练行数以服务端导出预览为准。',
      reasons,
      evaluatedAt: result.evaluated_at,
    }
  if (result?.outcome === 'WARNING')
    return {
      label: cleaningStatusLabels.WARNING,
      tone: 'warning',
      description: '当前数据暂不可用于训练，请补充事实后重新核对清洗。',
      reasons,
      evaluatedAt: result.evaluated_at,
    }
  if (result?.outcome === 'REJECT')
    return {
      label: cleaningStatusLabels.REJECT,
      tone: 'destructive',
      description: '当前数据未通过清洗，暂不可用于训练；视频资产未被删除。',
      reasons,
      evaluatedAt: result.evaluated_at,
    }
  return {
    label: cleaningStatusLabels.NOT_STARTED,
    tone: 'neutral',
    description: '尚未形成可用于训练的清洗结论。',
    reasons,
    evaluatedAt: null,
  }
}

function reviewStatusTone(decision: 'APPROVED' | 'REJECTED'): StatusIndicatorTone {
  return decision === 'APPROVED' ? 'success' : 'destructive'
}

export default function AnnotationTaskListView() {
  const { projectId = '' } = useParams()
  const navigate = useNavigate()
  const { message } = AntdApp.useApp()
  const [items, setItems] = useState<AnnotationTask[]>([])
  const [progress, setProgress] = useState<AnnotationTaskProgress | null>(null)
  const [labelSchema, setLabelSchema] = useState<AnnotationLabelSchema | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
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
        description="处理已分配的配药动作标注任务；提交后由管理员复核。"
        actions={
          <Button size="small" loading={loadState === 'loading'} onClick={() => void loadTasks()}>
            刷新
          </Button>
        }
      />
      {loadState === 'loading' ? <LoadingState>正在加载配药动作标注任务…</LoadingState> : null}
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
      {loadState === 'success' && progress?.scope === 'PROJECT' ? (
        <aside className="annotation-task-project-tools" aria-label="项目训练数据">
          <div className="annotation-task-project-tools-copy">
            <strong>项目训练数据</strong>
            <span>导出前先查看当前可用训练行数，实际数量以服务端预览为准。</span>
          </div>
          <Button
            size="small"
            loading={isTrainingPreviewLoading}
            disabled={isTrainingDownloadLoading}
            onClick={() => void openTrainingExport()}
          >
            导出训练数据
          </Button>
        </aside>
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
                        ...(revision
                          ? [
                              {
                                id: 'assignee',
                                label: '执行人',
                                value: row.assignee_actor_id,
                              },
                              {
                                id: 'submitted-by',
                                label: '提交人',
                                value: revision.submitted_by,
                              },
                              {
                                id: 'submitted-at',
                                label: '提交时间',
                                value: formatDateTime(revision.submitted_at),
                              },
                              ...(revision.review
                                ? [
                                    {
                                      id: 'reviewed-by',
                                      label: '复核人',
                                      value: revision.review.reviewed_by,
                                    },
                                    {
                                      id: 'reviewed-at',
                                      label: '复核时间',
                                      value: formatDateTime(revision.review.reviewed_at),
                                    },
                                  ]
                                : []),
                            ]
                          : []),
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
                        {presentation.evaluatedAt ? (
                          <p>最近核对：{formatDateTime(presentation.evaluatedAt)}</p>
                        ) : null}
                        {presentation.reasons.length ? (
                          <p>原因：{presentation.reasons.join('；')}</p>
                        ) : null}
                      </div>
                    ) : null}
                  </div>
                </details>
              )
              const postCleaningOutcome = revision?.post_cleaning_result?.outcome
              const canOpenWorkbench =
                (row.can_submit && row.status === 'ASSIGNED') ||
                (row.can_review && Boolean(revision)) ||
                (row.can_recheck_post_cleaning &&
                  (postCleaningOutcome === 'WARNING' || postCleaningOutcome === 'REJECT'))
              const taskAction = canOpenWorkbench ? (
                <Button
                  type="primary"
                  size="small"
                  aria-label={`进入视频 ${row.clip_asset_id} 的配药动作标注工作台`}
                  onClick={() =>
                    void navigate(paths.annotationTask(projectId, row.annotation_task_id))
                  }
                >
                  进入工作台
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
                    <div
                      className="annotation-task-card-actions"
                      aria-label={taskAction ? '下一步操作' : '当前任务状态'}
                    >
                      <span className="annotation-task-action-label">
                        {taskAction ? '下一步' : '当前状态'}
                      </span>
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
        <EmptyState description="当前没有待处理的配药动作标注任务。质量判断合格的视频会自动出现在这里。" />
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
    </>
  )
}
