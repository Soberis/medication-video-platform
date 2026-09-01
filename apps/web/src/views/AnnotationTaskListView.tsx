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
  downloadTrainingDataV3,
  getTrainingDataV3ExportPreview,
  TrainingDataExportApiError,
  type TrainingDataV3ExportPreview,
} from '@/api/training-data'
import { paths } from '@/app/paths'
import { PageHeader } from '@/components/PageHeader'
import { MetadataList } from '@/components/data/MetadataList'
import { SummaryStrip } from '@/components/SummaryStrip'
import { StatusIndicator, type StatusIndicatorTone } from '@/components/status/StatusIndicator'
import { EmptyState, ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { Section } from '@/components/workspace/Section'
import {
  cleaningStatusLabels,
  formatDateTime,
  shortId,
} from '@/features/catalog/catalog-presentation'
import { captureGroupTaskDestination } from '@/features/capture-group-presentation'

const postCleaningReasonLabels: Readonly<Record<string, string>> = {
  REVIEW_NOT_APPROVED: '未绑定已通过的管理员复核',
  QUALITY_REVIEW_NOT_QUALIFIED: '当前机位质量结论不是“合格”',
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
  const result = revision.capture_group_cleaning
  const reasons = (result?.reason_codes ?? []).map((code) => {
    return (
      postCleaningReasonLabels[code] ??
      `其他训练资格检查原因（${/^[A-Z][A-Z0-9_]{0,63}$/.test(code) ? code : 'UNKNOWN_REASON'}）`
    )
  })
  const eligibilityTerm = '训练资格检查'
  if (result?.outcome === 'PASS')
    return {
      label: cleaningStatusLabels.PASS,
      tone: 'success',
      description: `当前规则检查通过；实际训练行数以服务端导出预览为准。`,
      reasons,
      evaluatedAt: result.evaluated_at,
    }
  if (result?.outcome === 'WARNING')
    return {
      label: cleaningStatusLabels.WARNING,
      tone: 'warning',
      description: `当前数据暂不可用于训练，请补充事实后重新核对${eligibilityTerm}。`,
      reasons,
      evaluatedAt: result.evaluated_at,
    }
  if (result?.outcome === 'REJECT')
    return {
      label: cleaningStatusLabels.REJECT,
      tone: 'destructive',
      description: `当前数据未通过${eligibilityTerm}，暂不可用于训练；视频资产未被删除。`,
      reasons,
      evaluatedAt: result.evaluated_at,
    }
  return {
    label: cleaningStatusLabels.NOT_STARTED,
    tone: 'neutral',
    description: `尚未形成可用于训练的${eligibilityTerm}结论。`,
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
  const [trainingDataV3Preview, setTrainingDataV3Preview] =
    useState<TrainingDataV3ExportPreview | null>(null)
  const [isTrainingDataV3ExportOpen, setIsTrainingDataV3ExportOpen] = useState(false)
  const [isTrainingDataV3PreviewLoading, setIsTrainingDataV3PreviewLoading] = useState(false)
  const [isTrainingDataV3DownloadLoading, setIsTrainingDataV3DownloadLoading] = useState(false)
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
      setItems(taskList.items.filter((task) => Boolean(task.capture_group_id)))
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

  const openTrainingDataV3Export = useCallback(async () => {
    if (
      progress?.scope !== 'PROJECT' ||
      isTrainingDataV3PreviewLoading ||
      isTrainingDataV3DownloadLoading
    )
      return
    trainingExportRequest.current?.abort()
    const request = new AbortController()
    trainingExportRequest.current = request
    setIsTrainingDataV3PreviewLoading(true)
    try {
      const preview = await getTrainingDataV3ExportPreview(projectId, request.signal)
      if (request.signal.aborted || trainingExportRequest.current !== request) return
      setTrainingDataV3Preview(preview)
      setIsTrainingDataV3ExportOpen(true)
    } catch (error) {
      if (request.signal.aborted || trainingExportRequest.current !== request) return
      void message.error(
        error instanceof TrainingDataExportApiError
          ? error.message
          : '暂时无法读取 training-data.v3 导出信息，请稍后重试。',
      )
    } finally {
      if (trainingExportRequest.current === request) {
        trainingExportRequest.current = null
        setIsTrainingDataV3PreviewLoading(false)
      }
    }
  }, [
    isTrainingDataV3DownloadLoading,
    isTrainingDataV3PreviewLoading,
    message,
    progress?.scope,
    projectId,
  ])

  const closeTrainingDataV3Export = useCallback(() => {
    if (isTrainingDataV3DownloadLoading) return
    setIsTrainingDataV3ExportOpen(false)
    setTrainingDataV3Preview(null)
  }, [isTrainingDataV3DownloadLoading])

  const downloadTrainingDataV3File = useCallback(async () => {
    if (
      !trainingDataV3Preview ||
      trainingDataV3Preview.event_count <= 0 ||
      isTrainingDataV3DownloadLoading
    )
      return
    const request = new AbortController()
    trainingExportRequest.current = request
    setIsTrainingDataV3DownloadLoading(true)
    try {
      const blob = await downloadTrainingDataV3(projectId, request.signal)
      const objectUrl = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = objectUrl
      link.download = trainingDataV3Preview.file_name
      link.rel = 'noopener'
      link.hidden = true
      document.body.append(link)
      try {
        link.click()
      } finally {
        URL.revokeObjectURL(objectUrl)
        link.remove()
      }
      setIsTrainingDataV3ExportOpen(false)
      setTrainingDataV3Preview(null)
      void message.success(`已开始下载 ${trainingDataV3Preview.file_name}。`)
    } catch (error) {
      if (request.signal.aborted) return
      void message.error(
        error instanceof TrainingDataExportApiError
          ? error.message
          : 'training-data.v3 暂时无法导出，请稍后重试。',
      )
    } finally {
      if (trainingExportRequest.current === request) trainingExportRequest.current = null
      setIsTrainingDataV3DownloadLoading(false)
    }
  }, [isTrainingDataV3DownloadLoading, message, projectId, trainingDataV3Preview])

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑导航">
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
              const captureGroupId = row.capture_group_id
              if (!captureGroupId) return null
              const taskSubject = `配药事件 ${shortId(captureGroupId)}`
              const revision = row.annotation_revision
              const presentation = postCleaningPresentation(row)
              const taskDestination = captureGroupTaskDestination(row)
              const taskDetails = (
                <details className="task-details">
                  <summary>查看详情</summary>
                  <div className="task-details-content">
                    <MetadataList
                      className="task-details-metadata"
                      ariaLabel={`${taskSubject} 的任务详情`}
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
                        <h3>训练资格检查说明</h3>
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
              const taskAction = taskDestination ? (
                <Button
                  type="primary"
                  size="small"
                  aria-label={`进入${taskSubject}的${taskDestination.actionLabel}`}
                  onClick={() =>
                    void navigate(
                      paths.captureGroupFocus(projectId, captureGroupId, taskDestination.focus),
                    )
                  }
                >
                  {taskDestination.actionLabel}
                </Button>
              ) : null
              return (
                <article
                  aria-label={`${taskSubject} · ${taskStatusLabel(row)}`}
                  className="annotation-task-card"
                  key={row.annotation_task_id}
                >
                  <header className="annotation-task-card-header">
                    <div className="annotation-task-identity">
                      <Link
                        className="text-link mono-wrap"
                        to={paths.captureGroup(projectId, captureGroupId)}
                        title={captureGroupId}
                      >
                        {taskSubject}
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
                                训练资格检查 · {presentation.label}
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
        <EmptyState description="当前没有待处理的配药动作标注任务。符合质量准入条件的配药事件会自动生成标注任务。" />
      ) : null}
      {loadState === 'success' && progress?.scope === 'PROJECT' ? (
        <details className="detail-disclosure annotation-task-project-tools">
          <summary>
            <strong>更多项目操作</strong>
            <span>训练数据导出</span>
          </summary>
          <div className="detail-disclosure-content">
            <div className="annotation-task-project-tools-content">
              <div className="annotation-task-project-tools-copy">
                <strong>项目训练数据</strong>
                <span>项目训练数据使用 training-data.v3；导出前由服务端确认事件级准入。</span>
              </div>
              <div className="annotation-task-project-tools-actions">
                <Button
                  size="small"
                  loading={isTrainingDataV3PreviewLoading}
                  disabled={isTrainingDataV3DownloadLoading}
                  onClick={() => void openTrainingDataV3Export()}
                >
                  预览 training-data.v3
                </Button>
              </div>
            </div>
          </div>
        </details>
      ) : null}
      <Modal
        open={isTrainingDataV3ExportOpen}
        title="training-data.v3 导出预览"
        okText="导出 v3"
        cancelText="关闭"
        confirmLoading={isTrainingDataV3DownloadLoading}
        okButtonProps={{
          disabled: !trainingDataV3Preview?.event_count,
          'aria-label': '导出 training-data.v3',
        }}
        cancelButtonProps={{
          disabled: isTrainingDataV3DownloadLoading,
          'aria-label': '关闭 v3 预览',
        }}
        mask={{ closable: !isTrainingDataV3DownloadLoading }}
        keyboard={!isTrainingDataV3DownloadLoading}
        onCancel={closeTrainingDataV3Export}
        onOk={() => void downloadTrainingDataV3File()}
        destroyOnHidden
      >
        {trainingDataV3Preview ? (
          <>
            <SummaryStrip
              ariaLabel="training-data.v3 产品摘要"
              items={[
                {
                  label: '合格配药事件',
                  value: `${trainingDataV3Preview.eligible_group_count} 组`,
                },
                {
                  label: '参与机位',
                  value: trainingDataV3Preview.event_count > 0 ? '随事件保留' : '暂无可导出事件',
                  detail: '机位明细见技术详情',
                },
                {
                  label: '排除机位',
                  value: trainingDataV3Preview.event_count > 0 ? '保留审计记录' : '暂无可导出事件',
                  detail: '不参与下游训练',
                },
                {
                  label: '正式标注',
                  value:
                    trainingDataV3Preview.event_count > 0 ? '已通过人工复核' : '暂无可导出事件',
                },
                {
                  label: '训练资格',
                  value:
                    trainingDataV3Preview.event_count > 0 ? '已通过当前规则' : '暂无可导出事件',
                },
              ]}
            />
            {trainingDataV3Preview.event_count === 0 ? (
              <p className="training-data-export-empty" role="status">
                当前没有符合条件的配药事件，暂时无法导出。
              </p>
            ) : null}
            <details className="detail-disclosure training-data-v3-technical-details">
              <summary>
                <strong>查看技术与追溯信息</strong>
                <span>版本、字段与时间基准</span>
              </summary>
              <div className="detail-disclosure-content">
                <MetadataList
                  className="training-data-export-summary training-data-v3-export-summary"
                  items={[
                    {
                      id: 'v3-event-count',
                      label: '事件数',
                      value: `${trainingDataV3Preview.event_count} 个`,
                    },
                    {
                      id: 'v3-action-labels',
                      label: '动作标签',
                      value: 'action_label + action_labels',
                    },
                    {
                      id: 'v3-view-provenance',
                      label: '视图与质量来源',
                      value:
                        'participating_views + excluded_views；group_quality + qualified_views + rejected_missing_views',
                    },
                    {
                      id: 'v3-confirmed-view-set',
                      label: '确认输入',
                      value: 'confirmed_view_set + input_version',
                    },
                    {
                      id: 'v3-revision-provenance',
                      label: '修订与复核来源',
                      value:
                        'annotation_revision_id + revision；annotation_review_id + review_decision + reviewed_by',
                    },
                    {
                      id: 'v3-cleaning-provenance',
                      label: '训练资格检查来源',
                      value:
                        'capture_group_cleaning_id + rule set + input fingerprint + cleaning_evaluated_by；PASS',
                    },
                    {
                      id: 'v3-candidate-provenance',
                      label: '候选与映射来源',
                      value: 'candidate_provenance + mapping_provenance',
                    },
                    {
                      id: 'v3-primary-interval',
                      label: '主视图时间基准',
                      value:
                        'primary_clip_asset_id + primary_camera_number + primary_interval；参考视图不生成动作对齐时间戳',
                    },
                    {
                      id: 'v3-format-version',
                      label: '格式 / 版本',
                      value: (
                        <>
                          <code>{trainingDataV3Preview.schema_version}</code> ·{' '}
                          <code>{trainingDataV3Preview.content_type}</code>
                        </>
                      ),
                    },
                    {
                      id: 'v3-split-unit',
                      label: 'Split 单位',
                      value: '配药事件（同一事件的机位不跨 train / validation / test）',
                    },
                    {
                      id: 'v3-file',
                      label: '文件',
                      value: <code>{trainingDataV3Preview.file_name}</code>,
                    },
                    {
                      id: 'v3-includes-mp4',
                      label: '原始视频',
                      value: trainingDataV3Preview.includes_mp4 ? '包含' : '不包含',
                    },
                  ]}
                />
                <p className="form-hint">
                  准入条件由服务端根据事件质量、正式修订、复核通过和训练资格检查通过判定；本页不重新计算。
                </p>
              </div>
            </details>
          </>
        ) : null}
      </Modal>
    </>
  )
}
