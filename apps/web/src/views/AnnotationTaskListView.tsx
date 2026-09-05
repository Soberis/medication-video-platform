import { Button } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

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
import { formatDateTime, shortId } from '@/features/catalog/catalog-presentation'
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
  if (task.status === 'COMPLETED') return '已完成'
  if (task.status === 'SUBMITTED') return '待复核'
  if (task.annotation_revision?.review?.decision === 'REJECTED') return '待返工'
  return '待标注'
}
function taskStatusTone(task: AnnotationTask): StatusIndicatorTone {
  if (task.status === 'COMPLETED') return 'neutral'
  if (task.annotation_revision?.review?.decision === 'REJECTED') return 'warning'
  return 'information'
}
function taskNoActionLabel(task: AnnotationTask) {
  if (task.status === 'COMPLETED') return '当前无需操作'
  if (task.status === 'SUBMITTED') return '等待管理员复核'
  if (task.annotation_revision?.review?.decision === 'REJECTED') return '等待任务执行人返工'
  return '等待任务执行人处理'
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
      label: '通过',
      tone: 'success',
      description: '当前事件清洗规则检查通过。',
      reasons,
      evaluatedAt: result.evaluated_at,
    }
  if (result?.outcome === 'WARNING')
    return {
      label: '需处理',
      tone: 'warning',
      description: '当前事件清洗需处理，请补充事实后重新执行事件清洗。',
      reasons,
      evaluatedAt: result.evaluated_at,
    }
  if (result?.outcome === 'REJECT')
    return {
      label: '未通过',
      tone: 'destructive',
      description: '当前事件清洗未通过；视频资产未被删除。',
      reasons,
      evaluatedAt: result.evaluated_at,
    }
  return {
    label: '待处理',
    tone: 'neutral',
    description: '尚未形成事件清洗结论。',
    reasons,
    evaluatedAt: null,
  }
}

function taskEvidenceLabel(
  task: AnnotationTask,
  cleaning: ReturnType<typeof captureGroupCleaningPresentation>,
) {
  const review = task.annotation_revision?.review
  if (!review) return '等待人工复核'
  if (review.decision === 'REJECTED') return '复核未通过'
  return cleaning ? `复核已通过 · 事件清洗${cleaning.label}` : '复核已通过'
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

export default function AnnotationTaskListView() {
  const { projectId = '' } = useParams()
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
          : '暂时无法读取标注任务，请稍后重试。',
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
      <PageHeader
        titleId="annotation-task-title"
        title="人工标签收件箱"
        description="仅显示当前项目已有的人工标签任务；提交后由有权限的人员复核。"
        actions={
          <Button size="small" loading={loadState === 'loading'} onClick={() => void loadTasks()}>
            刷新
          </Button>
        }
      />
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
          <strong>{progress.total} 个任务</strong>
          <span aria-hidden="true">·</span>
          <span>{progress.completed} 个已完成</span>
          <span aria-hidden="true">·</span>
          <span>
            {progress.awaiting_submission + progress.rework_required + progress.awaiting_review}{' '}
            个待处理
          </span>
        </section>
      ) : null}
      {loadState === 'success' && items.length ? (
        <Section
          className="annotation-task-queue"
          title="任务队列"
          titleId="annotation-task-queue-title"
        >
          <ul className="annotation-task-list-surface" aria-label="标注任务列表">
            {items.map((row) => {
              const captureGroupId = row.capture_group_id
              const taskSubject = `数据组 ${shortId(captureGroupId)}`
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
                    {revision?.review?.review_note ? (
                      <div className="task-details-block">
                        <h3>复核意见</h3>
                        <p className="task-review-note">{revision.review.review_note}</p>
                      </div>
                    ) : null}
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
                  <Link
                    className="annotation-task-action-link"
                    aria-label={`进入${taskSubject}的${taskDestination.actionLabel}`}
                    to={paths.captureGroupFocus(projectId, captureGroupId, taskDestination.focus)}
                  >
                    {taskDestination.actionLabel} <span aria-hidden="true">→</span>
                  </Link>
                ) : null
              const completedTaskResult =
                row.status === 'COMPLETED' && taskDestination ? (
                  <Link
                    className="text-link annotation-task-result-link"
                    to={paths.captureGroupFocus(projectId, captureGroupId, taskDestination.focus)}
                  >
                    查看结果 <span aria-hidden="true">→</span>
                  </Link>
                ) : null
              return (
                <li
                  aria-label={`${taskSubject} · ${taskStatusLabel(row)}`}
                  className="annotation-task-row"
                  key={row.annotation_task_id}
                  data-status={row.status}
                >
                  <header className="annotation-task-row-identity">
                    <Link
                      className="text-link mono-wrap"
                      to={paths.captureGroup(projectId, captureGroupId)}
                      title={captureGroupId}
                    >
                      {taskSubject}
                    </Link>
                  </header>
                  <div className="annotation-task-row-status">
                    <StatusIndicator className="annotation-task-status" tone={taskStatusTone(row)}>
                      {taskStatusLabel(row)}
                    </StatusIndicator>
                  </div>
                  <div className="annotation-task-row-evidence">
                    <div className="annotation-task-progress">
                      {revision ? (
                        <div className="task-evidence-summary">
                          <strong className="task-evidence-primary">
                            已提交 · {revision.annotation_document.segments.length} 个时间段
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
                          {taskDetails}
                        </div>
                      ) : (
                        <div className="task-evidence-summary">
                          <span className="muted-text">尚未提交</span>
                          {taskDetails}
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
                              {taskNoActionLabel(row)}
                            </span>
                          ))}
                    </div>
                  </footer>
                </li>
              )
            })}
          </ul>
        </Section>
      ) : null}
      {loadState === 'success' && !items.length ? (
        <EmptyState description="当前没有人工标签任务。人工标签只在项目需要 human ground truth 时启用。" />
      ) : null}
    </>
  )
}
