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
  downloadHandLandmarkExport,
  getHandLandmarkExportPreview,
  HandLandmarkExportApiError,
  type HandLandmarkExportPreview,
  type HandLandmarkExportReasonCode,
} from '@/api/hand-landmarks'
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

const captureGroupCleaningReasonLabels: Readonly<Record<string, string>> = {
  REVIEW_NOT_APPROVED: '未绑定已通过的管理员复核',
  GROUP_QUALITY_NOT_ADMITTING: '当前事件质量结论不允许进入训练资格检查',
  PRIMARY_VIEW_MISSING: '缺少主视图',
  PRIMARY_VIEW_NOT_QUALIFIED: '主视图当前未通过机位质量准入',
  PRIMARY_INTERVAL_MISSING: '缺少主视图正式时间段',
  PRIMARY_INTERVAL_INVALID: '主视图存在无效的正式时间段',
  PRIMARY_INTERVAL_OVERLAP: '主视图正式时间段存在重叠',
  NO_QUALIFIED_VIEWS: '事件没有可参与的合格机位',
  VIEW_SET_DUPLICATE_CAMERA_NUMBER: '事件机位集合存在重复机位编号',
  MISSING_VIEW: '事件机位缺失',
  VIEW_NOT_REVIEWABLE: '事件机位当前不可复核',
  VIEW_QUALITY_NOT_QUALIFIED: '事件机位质量未通过',
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
function captureGroupCleaningPresentation(task: AnnotationTask): {
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
      captureGroupCleaningReasonLabels[code] ??
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

const handLandmarkReasonLabels: Readonly<Record<HandLandmarkExportReasonCode, string>> = {
  MEDIAPIPE_NOT_FINISHED: '手部检测尚未完成',
  NO_HAND_DETECTED: '未检测到手部关键点',
  MEDIAPIPE_FAILED: '手部检测失败',
  NO_VALID_21_POINT_SAMPLE: '没有完整的 21 点样本',
}

const handLandmarkReasonOrder: readonly HandLandmarkExportReasonCode[] = [
  'MEDIAPIPE_NOT_FINISHED',
  'NO_HAND_DETECTED',
  'MEDIAPIPE_FAILED',
  'NO_VALID_21_POINT_SAMPLE',
]

function formatTrainingCount(value: number) {
  return new Intl.NumberFormat('zh-CN').format(value)
}

function getHandLandmarkReasonItems(preview: HandLandmarkExportPreview) {
  return handLandmarkReasonOrder.flatMap((reasonCode) => {
    const count = preview.reason_counts?.[reasonCode]
    return typeof count === 'number' && count > 0
      ? [{ label: handLandmarkReasonLabels[reasonCode], count }]
      : []
  })
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
  const [handLandmarkPreview, setHandLandmarkPreview] = useState<HandLandmarkExportPreview | null>(
    null,
  )
  const [isHandLandmarkExportOpen, setIsHandLandmarkExportOpen] = useState(false)
  const [isHandLandmarkPreviewLoading, setIsHandLandmarkPreviewLoading] = useState(false)
  const [isHandLandmarkDownloadLoading, setIsHandLandmarkDownloadLoading] = useState(false)
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

  const openHandLandmarkExport = useCallback(async () => {
    if (
      progress?.scope !== 'PROJECT' ||
      isHandLandmarkPreviewLoading ||
      isHandLandmarkDownloadLoading
    )
      return
    trainingExportRequest.current?.abort()
    const request = new AbortController()
    trainingExportRequest.current = request
    setIsHandLandmarkPreviewLoading(true)
    try {
      const preview = await getHandLandmarkExportPreview(projectId, request.signal)
      if (request.signal.aborted || trainingExportRequest.current !== request) return
      setHandLandmarkPreview(preview)
      setIsHandLandmarkExportOpen(true)
    } catch (error) {
      if (request.signal.aborted || trainingExportRequest.current !== request) return
      void message.error(
        error instanceof HandLandmarkExportApiError
          ? error.message
          : '暂时无法读取手部训练数据，请稍后重试。',
      )
    } finally {
      if (trainingExportRequest.current === request) {
        trainingExportRequest.current = null
        setIsHandLandmarkPreviewLoading(false)
      }
    }
  }, [
    isHandLandmarkDownloadLoading,
    isHandLandmarkPreviewLoading,
    message,
    progress?.scope,
    projectId,
  ])

  const closeHandLandmarkExport = useCallback(() => {
    if (isHandLandmarkDownloadLoading) return
    setIsHandLandmarkExportOpen(false)
    setHandLandmarkPreview(null)
  }, [isHandLandmarkDownloadLoading])

  const downloadHandLandmarkFile = useCallback(async () => {
    if (
      !handLandmarkPreview ||
      handLandmarkPreview.sample_count <= 0 ||
      isHandLandmarkDownloadLoading
    )
      return
    const request = new AbortController()
    trainingExportRequest.current = request
    setIsHandLandmarkDownloadLoading(true)
    try {
      const blob = await downloadHandLandmarkExport(projectId, request.signal)
      const objectUrl = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = objectUrl
      link.download = handLandmarkPreview.file_name
      link.rel = 'noopener'
      link.hidden = true
      document.body.append(link)
      try {
        link.click()
      } finally {
        URL.revokeObjectURL(objectUrl)
        link.remove()
      }
      setIsHandLandmarkExportOpen(false)
      setHandLandmarkPreview(null)
      void message.success(`已开始下载 ${handLandmarkPreview.file_name}。`)
    } catch (error) {
      if (request.signal.aborted) return
      void message.error(
        error instanceof HandLandmarkExportApiError
          ? error.message
          : '手部训练数据暂时无法导出，请稍后重试。',
      )
    } finally {
      if (trainingExportRequest.current === request) trainingExportRequest.current = null
      setIsHandLandmarkDownloadLoading(false)
    }
  }, [handLandmarkPreview, isHandLandmarkDownloadLoading, message, projectId])

  const handLandmarkReasonItems = handLandmarkPreview
    ? getHandLandmarkReasonItems(handLandmarkPreview)
    : []

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
          <>
            {progress?.scope === 'PROJECT' ? (
              <Button
                size="small"
                loading={isHandLandmarkPreviewLoading}
                disabled={isHandLandmarkDownloadLoading}
                onClick={() => void openHandLandmarkExport()}
              >
                预览手部训练数据
              </Button>
            ) : null}
            <Button size="small" loading={loadState === 'loading'} onClick={() => void loadTasks()}>
              刷新
            </Button>
          </>
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
              const taskSubject = `配药事件 ${shortId(captureGroupId)}`
              const revision = row.annotation_revision
              const presentation = captureGroupCleaningPresentation(row)
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
              const taskAction =
                row.status !== 'COMPLETED' && taskDestination ? (
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
              const completedTaskResult =
                row.status === 'COMPLETED' ? (
                  <Link
                    className="text-link"
                    to={paths.captureGroupFocus(projectId, captureGroupId, 'cleaning')}
                  >
                    查看复核与训练资格详情 <span aria-hidden="true">→</span>
                  </Link>
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
                      aria-label={
                        row.status === 'COMPLETED'
                          ? '完成结果操作'
                          : taskAction
                            ? '下一步操作'
                            : '当前任务状态'
                      }
                    >
                      {row.status === 'COMPLETED' ? (
                        completedTaskResult
                      ) : (
                        <>
                          <span className="annotation-task-action-label">
                            {taskAction ? '下一步' : '当前状态'}
                          </span>
                          {taskAction ?? (
                            <span className="annotation-task-no-action">
                              {taskNoActionLabel(row)}
                            </span>
                          )}
                        </>
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
      <Modal
        open={isHandLandmarkExportOpen}
        title="手部训练数据"
        okText="导出 CSV"
        cancelText="关闭"
        confirmLoading={isHandLandmarkDownloadLoading}
        okButtonProps={{
          disabled: !handLandmarkPreview?.sample_count,
          'aria-label': '导出 CSV',
        }}
        cancelButtonProps={{
          disabled: isHandLandmarkDownloadLoading,
          'aria-label': '关闭手部训练数据预览',
        }}
        mask={{ closable: !isHandLandmarkDownloadLoading }}
        keyboard={!isHandLandmarkDownloadLoading}
        onCancel={closeHandLandmarkExport}
        onOk={() => void downloadHandLandmarkFile()}
        destroyOnHidden
      >
        {handLandmarkPreview ? (
          <>
            <SummaryStrip
              ariaLabel="手部训练数据摘要"
              items={[
                {
                  label: '可用于训练的配药事件',
                  value: `${formatTrainingCount(handLandmarkPreview.eligible_event_count)} 个`,
                },
                {
                  label: '有手部关键点的事件',
                  value: `${formatTrainingCount(handLandmarkPreview.events_with_landmarks)} 个`,
                },
                {
                  label: '手部样本数',
                  value: formatTrainingCount(handLandmarkPreview.sample_count),
                },
                {
                  label: '无可用关键点的事件',
                  value: `${formatTrainingCount(handLandmarkPreview.events_without_landmarks)} 个`,
                },
              ]}
            />
            <p className="hand-landmark-export-description">
              MediaPipe 手部关键点；每一行代表一个采样帧中的一只手和完整的 21 点样本。
            </p>
            {handLandmarkPreview.events_without_landmarks > 0 ? (
              <p className="hand-landmark-export-not-ready" role="status">
                {formatTrainingCount(handLandmarkPreview.events_without_landmarks)}{' '}
                个已完成事件尚未产生可用手部关键点。
              </p>
            ) : handLandmarkPreview.sample_count === 0 ? (
              <p className="hand-landmark-export-not-ready" role="status">
                当前没有可用的手部关键点样本，暂时无法导出。
              </p>
            ) : null}
            {handLandmarkReasonItems.length ? (
              <section className="hand-landmark-export-reasons" aria-label="手部检测状态说明">
                <h3>未产生样本的原因</h3>
                <ul>
                  {handLandmarkReasonItems.map((item) => (
                    <li key={item.label}>
                      <span>{item.label}</span>
                      <strong>{formatTrainingCount(item.count)} 个事件</strong>
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}
            <p className="hand-landmark-export-file">
              文件：<code>{handLandmarkPreview.file_name}</code>
            </p>
          </>
        ) : null}
      </Modal>
    </>
  )
}
