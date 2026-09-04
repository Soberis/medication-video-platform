import { Button } from 'antd'
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
import { formatAnnotationTime } from '@/features/capture-group/capture-group-detail-model'
import { captureGroupTaskDestination } from '@/features/capture-group-presentation'

const captureGroupCleaningReasonLabels: Readonly<Record<string, string>> = {
  REVIEW_NOT_APPROVED: '未绑定已通过的管理员复核',
  GROUP_QUALITY_NOT_ADMITTING: '当前事件质量结论不允许进入事件清洗',
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
  return '待标注'
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
      `其他事件清洗原因（${/^[A-Z][A-Z0-9_]{0,63}$/.test(code) ? code : 'UNKNOWN_REASON'}）`
    )
  })
  if (result?.outcome === 'PASS')
    return {
      label: cleaningStatusLabels.PASS,
      tone: 'success',
      description: '当前事件清洗规则检查通过；手部检测样本请通过独立导出预览确认。',
      reasons,
      evaluatedAt: result.evaluated_at,
    }
  if (result?.outcome === 'WARNING')
    return {
      label: cleaningStatusLabels.WARNING,
      tone: 'warning',
      description: '当前事件清洗需处理，请补充事实后重新执行事件清洗。',
      reasons,
      evaluatedAt: result.evaluated_at,
    }
  if (result?.outcome === 'REJECT')
    return {
      label: cleaningStatusLabels.REJECT,
      tone: 'destructive',
      description: '当前事件清洗未通过；视频资产未被删除。',
      reasons,
      evaluatedAt: result.evaluated_at,
    }
  return {
    label: cleaningStatusLabels.NOT_STARTED,
    tone: 'neutral',
    description: '尚未形成事件清洗结论。',
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
  const [items, setItems] = useState<AnnotationTask[]>([])
  const [progress, setProgress] = useState<AnnotationTaskProgress | null>(null)
  const [labelSchema, setLabelSchema] = useState<AnnotationLabelSchema | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const activeRequest = useRef<AbortController | null>(null)

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
    }
  }, [loadTasks])

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑导航">
        <span>配药动作标注</span>
      </nav>
      <PageHeader
        titleId="annotation-task-title"
        title="配药动作标注"
        description="在这里处理配药事件的动作标注；提交后由管理员复核。"
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
          title="配药事件任务"
          titleId="annotation-task-queue-title"
        >
          <div
            className="annotation-task-list-surface"
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
                        {
                          id: 'assignee',
                          label: '执行人',
                          value: row.assignee_actor_id,
                        },
                        ...(revision
                          ? [
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
                            const formattedStart = formatAnnotationTime(segment.start_ms)
                            const formattedEnd = formatAnnotationTime(segment.end_ms)
                            return (
                              <li key={`${segment.start_ms}-${segment.end_ms}-${segment.label}`}>
                                <div className="task-annotation-time">
                                  <span className="task-annotation-range">
                                    时间段 {index + 1} ·{' '}
                                    <strong>
                                      {formattedStart && formattedEnd
                                        ? `${formattedStart} → ${formattedEnd}`
                                        : '时间暂不可用'}
                                    </strong>
                                  </span>
                                  <small>
                                    原始值：{segment.start_ms}–{segment.end_ms} 毫秒
                                  </small>
                                </div>
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
                        <h3>事件清洗说明</h3>
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
                    查看复核与事件清洗详情 <span aria-hidden="true">→</span>
                  </Link>
                ) : null
              return (
                <article
                  aria-label={`${taskSubject} · ${taskStatusLabel(row)}`}
                  className="annotation-task-card"
                  key={row.annotation_task_id}
                  role="listitem"
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
                            已提交 · {revision.annotation_document.segments.length} 个时间段
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
                                事件清洗 · {presentation.label}
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
                          <span className="muted-text">尚未提交标注</span>
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
    </>
  )
}
