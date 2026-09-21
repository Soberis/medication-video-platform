import { ArrowRight } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'

import {
  AnnotationTaskApiError,
  listAnnotationTasks,
  type AnnotationTask,
  type AnnotationTaskProgress,
} from '@/api/annotation'
import { useAuth } from '@/app/auth'
import { paths } from '@/app/paths'
import { PageHeader } from '@/components/PageHeader'
import { RefreshButton } from '@/components/RefreshButton'
import { StatusIndicator, type StatusIndicatorTone } from '@/components/status/StatusIndicator'
import { EmptyState, ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { shortId } from '@/features/catalog/catalog-presentation'
import {
  annotationTaskStatusLabel,
  annotationTaskStatusTone,
  captureGroupTaskDestination,
} from '@/features/capture-group-presentation'

export const ANNOTATION_TASK_LIST_POLL_INTERVAL_MS = 20_000

function taskNoActionLabel(task: AnnotationTask, viewerUsername: string | undefined) {
  if (task.status === 'COMPLETED') return '当前无需操作'
  if (task.status === 'SUBMITTED') {
    const revision = task.annotation_revision
    if (revision?.submitted_by === viewerUsername) {
      return task.can_review
        ? '你是提交人，可直接确认本次标注'
        : '你是提交人；当前尚不能确认，请检查项目权限与标注前检查状态'
    }
    return task.can_review ? '等待你确认标注' : '等待项目管理员确认'
  }
  if (task.annotation_revision?.review?.decision === 'REJECTED') return '等待任务执行人返工'
  return '等待任务执行人处理'
}

function captureGroupCleaningPresentation(task: AnnotationTask): {
  label: string
  tone: StatusIndicatorTone
} | null {
  const revision = task.annotation_revision
  if (revision?.review?.decision !== 'APPROVED') return null
  const result = revision.capture_group_cleaning
  if (result?.outcome === 'PASS') return { label: '通过', tone: 'success' }
  if (result?.outcome === 'WARNING') return { label: '需处理', tone: 'warning' }
  if (result?.outcome === 'REJECT') return { label: '未通过', tone: 'destructive' }
  return { label: '待处理', tone: 'neutral' }
}

function taskEvidenceLabel(
  task: AnnotationTask,
  cleaning: ReturnType<typeof captureGroupCleaningPresentation>,
) {
  const review = task.annotation_revision?.review
  if (!review) return '等待人工复核'
  if (review.decision === 'REJECTED') return '复核未通过'
  return cleaning ? `复核已通过 · 标注检查${cleaning.label}` : '复核已通过'
}

function taskEvidenceTone(
  task: AnnotationTask,
  cleaning: ReturnType<typeof captureGroupCleaningPresentation>,
): StatusIndicatorTone {
  const review = task.annotation_revision?.review
  if (review?.decision === 'REJECTED') return 'destructive'
  if (!review) return 'information'
  return cleaning?.tone === 'warning' || cleaning?.tone === 'destructive'
    ? cleaning.tone
    : 'neutral'
}

export default function AnnotationTaskListView({
  filter = 'tasks',
  embedded = false,
  searchQuery = '',
}: {
  filter?: string
  embedded?: boolean
  searchQuery?: string
}) {
  const { projectId = '' } = useParams()
  const navigate = useNavigate()
  const { session } = useAuth()
  const [items, setItems] = useState<AnnotationTask[]>([])
  const [progress, setProgress] = useState<AnnotationTaskProgress | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const activeRequest = useRef<AbortController | null>(null)

  const loadTasks = useCallback(
    async (silent = false) => {
      activeRequest.current?.abort()
      const request = new AbortController()
      activeRequest.current = request
      if (!silent) {
        setLoadState('loading')
        setProgress(null)
        setErrorCode('')
        setErrorMessage('')
      }
      try {
        const taskList = await listAnnotationTasks(projectId, request.signal)
        if (activeRequest.current !== request) return
        setItems(taskList.items)
        setProgress(taskList.progress)
        setLoadState('success')
      } catch (error) {
        if (request.signal.aborted || activeRequest.current !== request) return
        if (!silent) {
          setLoadState('error')
          setErrorCode(
            error instanceof AnnotationTaskApiError
              ? error.errorCode
              : 'ANNOTATION_TASK_REQUEST_FAILED',
          )
          setErrorMessage(
            error instanceof AnnotationTaskApiError
              ? error.message
              : '暂时无法读取标注任务，请稍后重试。',
          )
        }
      } finally {
        if (activeRequest.current === request) activeRequest.current = null
      }
    },
    [projectId],
  )
  useEffect(() => {
    const timer = window.setTimeout(() => void loadTasks(), 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
    }
  }, [loadTasks])

  useEffect(() => {
    if (searchQuery.trim() !== '' || loadState !== 'success') {
      return undefined
    }

    let isDisposed = false
    let timer: number | null = null

    const scheduleNext = () => {
      if (isDisposed) return
      timer = window.setTimeout(async () => {
        timer = null
        if (isDisposed) return
        if (!document.hidden) {
          await loadTasks(true)
        }
        if (!isDisposed) {
          scheduleNext()
        }
      }, ANNOTATION_TASK_LIST_POLL_INTERVAL_MS)
    }

    scheduleNext()

    const handleVisibilityChange = () => {
      if (!document.hidden) {
        void loadTasks(true)
      }
    }
    document.addEventListener('visibilitychange', handleVisibilityChange)

    return () => {
      isDisposed = true
      if (timer !== null) {
        window.clearTimeout(timer)
        timer = null
      }
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [loadState, loadTasks, searchQuery])

  const normalizedQuery = searchQuery.trim().toLowerCase()
  const visibleItems = items.filter((task) => {
    if (filter === 'mine') {
      if (!(task.can_submit || task.can_review)) return false
    } else if (filter === 'assigned') {
      if (annotationTaskStatusLabel(task) !== '待标注') return false
    } else if (filter === 'submitted') {
      if (task.status !== 'SUBMITTED') return false
    } else if (filter === 'rework') {
      if (annotationTaskStatusLabel(task) !== '待返工') return false
    } else if (filter === 'completed') {
      if (task.status !== 'COMPLETED') return false
    }

    if (normalizedQuery) {
      const matchId = task.capture_group_id.toLowerCase().includes(normalizedQuery)
      const matchTaskId = task.annotation_task_id.toLowerCase().includes(normalizedQuery)
      const matchStatus = annotationTaskStatusLabel(task).toLowerCase().includes(normalizedQuery)
      const matchAssignee = task.assignee_actor_id?.toLowerCase().includes(normalizedQuery)
      const matchSubmittedBy = task.annotation_revision?.submitted_by
        ?.toLowerCase()
        .includes(normalizedQuery)
      if (!matchId && !matchTaskId && !matchStatus && !matchAssignee && !matchSubmittedBy)
        return false
    }

    return true
  })

  return (
    <>
      {!embedded ? (
        <PageHeader
          titleId="annotation-task-title"
          title="标注任务"
          actions={
            <RefreshButton loading={loadState === 'loading'} onClick={() => void loadTasks()} />
          }
        />
      ) : null}
      {loadState === 'loading' ? <LoadingState>正在加载标注任务…</LoadingState> : null}
      {loadState === 'error' ? (
        <ErrorState code={errorCode} onRetry={() => void loadTasks()}>
          {errorMessage}
        </ErrorState>
      ) : null}
      {loadState === 'success' && progress ? (
        <section
          className="annotation-task-overview"
          aria-label={progress.scope === 'PROJECT' ? '项目标注任务进度' : '我的标注任务进度'}
        >
          <div className="task-overview-metrics">
            <strong>{progress.total} 个任务</strong>
            <span className="task-overview-dot" aria-hidden="true">
              ·
            </span>
            <span>{progress.completed} 个已完成</span>
            <span className="task-overview-dot" aria-hidden="true">
              ·
            </span>
            <span>
              {progress.awaiting_submission + progress.rework_required + progress.awaiting_review}{' '}
              个待处理
            </span>
          </div>
        </section>
      ) : null}
      {loadState === 'success' && visibleItems.length ? (
        <section className="annotation-task-queue" aria-labelledby="annotation-task-queue-title">
          <h2 className="visually-hidden" id="annotation-task-queue-title">
            任务队列
          </h2>
          <div className="annotation-task-container">
            <div className="annotation-task-header" aria-hidden="true">
              <span className="annotation-task-th-identity">采集记录</span>
              <span className="annotation-task-th-status">任务状态</span>
              <span className="annotation-task-th-evidence">标注与复核进展</span>
              <span className="annotation-task-th-action">操作</span>
            </div>
            <ul className="annotation-task-list-surface" aria-label="标注任务列表">
              {visibleItems.map((row) => {
                const captureGroupId = row.capture_group_id
                const taskSubject = `采集记录 ${shortId(captureGroupId)}`
                const revision = row.annotation_revision
                const revisionSegmentCount = revision
                  ? revision.annotation_document.schema_version === 'annotation-document.v2'
                    ? revision.annotation_document.camera_views.reduce(
                        (count, view) => count + view.segments.length,
                        0,
                      )
                    : revision.annotation_document.segments.length
                  : 0
                const presentation = captureGroupCleaningPresentation(row)
                const taskDestination = captureGroupTaskDestination(row)
                const taskAction =
                  row.status !== 'COMPLETED' && taskDestination ? (
                    <Link
                      className="capture-group-list-open annotation-task-action-link"
                      aria-label={taskDestination.actionLabel}
                      to={paths.captureGroupFocus(projectId, captureGroupId, taskDestination.focus)}
                    >
                      <span>{taskDestination.actionLabel}</span>
                      <ArrowRight aria-hidden="true" size={13} />
                    </Link>
                  ) : null
                const completedTaskResult =
                  row.status === 'COMPLETED' && taskDestination ? (
                    <Link
                      className="capture-group-list-open annotation-task-result-link"
                      to={paths.captureGroupFocus(projectId, captureGroupId, taskDestination.focus)}
                    >
                      <span>查看标注结果</span>
                      <ArrowRight aria-hidden="true" size={13} />
                    </Link>
                  ) : null
                return (
                  <li
                    aria-label={`${taskSubject} · ${annotationTaskStatusLabel(row)}`}
                    className="annotation-task-row"
                    key={row.annotation_task_id}
                    data-status={row.status}
                    tabIndex={0}
                    onClick={(event) => {
                      const target = event.target as HTMLElement
                      if (
                        target.closest('a') ||
                        target.closest('button') ||
                        (window.getSelection()?.toString().length ?? 0) > 0
                      ) {
                        return
                      }
                      const destination = taskDestination
                        ? paths.captureGroupFocus(projectId, captureGroupId, taskDestination.focus)
                        : paths.captureGroup(projectId, captureGroupId)
                      void navigate(destination)
                    }}
                    onKeyDown={(event) => {
                      if (event.key !== 'Enter' && event.key !== ' ') return
                      const target = event.target as HTMLElement
                      if (target.closest('a') || target.closest('button')) return
                      event.preventDefault()
                      const destination = taskDestination
                        ? paths.captureGroupFocus(projectId, captureGroupId, taskDestination.focus)
                        : paths.captureGroup(projectId, captureGroupId)
                      void navigate(destination)
                    }}
                  >
                    <header className="annotation-task-row-identity">
                      <strong
                        className="mono-wrap annotation-task-title-clickable"
                        title={captureGroupId}
                      >
                        {taskSubject}
                      </strong>
                    </header>
                    <div className="annotation-task-row-status">
                      <StatusIndicator
                        className="annotation-task-status"
                        tone={annotationTaskStatusTone(row)}
                      >
                        {annotationTaskStatusLabel(row)}
                      </StatusIndicator>
                    </div>
                    <div className="annotation-task-row-evidence">
                      <div className="annotation-task-progress">
                        {revision ? (
                          <div className="task-evidence-summary">
                            <strong className="task-evidence-primary">
                              已提交 · {revisionSegmentCount} 个时间段
                            </strong>
                            <p
                              className="task-evidence-status"
                              data-tone={taskEvidenceTone(row, presentation)}
                            >
                              {taskEvidenceLabel(row, presentation)}
                              {revision.review?.decision === 'APPROVED' &&
                              revision.review.reviewed_by === revision.submitted_by ? (
                                <small className="task-evidence-self-review">
                                  <span aria-hidden="true">（</span>
                                  <span>管理员自审</span>
                                  <span aria-hidden="true">）</span>
                                </small>
                              ) : null}
                            </p>
                          </div>
                        ) : (
                          <div className="task-evidence-summary">
                            <span className="muted-text">尚未提交</span>
                          </div>
                        )}
                      </div>
                    </div>
                    <footer className="annotation-task-row-footer">
                      <div
                        className="annotation-task-row-actions"
                        aria-label={
                          row.status === 'COMPLETED'
                            ? '完成结果操作'
                            : taskAction
                              ? '下一步操作'
                              : '当前任务状态'
                        }
                      >
                        {row.status === 'COMPLETED'
                          ? completedTaskResult
                          : (taskAction ?? (
                              <span className="annotation-task-no-action">
                                {taskNoActionLabel(row, session?.username)}
                              </span>
                            ))}
                      </div>
                    </footer>
                  </li>
                )
              })}
            </ul>
          </div>
        </section>
      ) : null}
      {loadState === 'success' && !visibleItems.length ? (
        <EmptyState description="当前没有需要处理的标注任务。" />
      ) : null}
    </>
  )
}
