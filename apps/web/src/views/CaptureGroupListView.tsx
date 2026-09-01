import { Alert, Button } from 'antd'
import { ArrowRight, Camera, RefreshCw } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import {
  CaptureGroupApiError,
  isCaptureGroupQualityForbidden,
  listCaptureGroups,
  listCaptureGroupQualityReviews,
  type CaptureGroupSummary,
  type CaptureGroupQualityReview,
} from '@/api/capture-groups'
import { listAnnotationTasks, type AnnotationTask } from '@/api/annotation'
import { useAuth } from '@/app/auth'
import { paths } from '@/app/paths'
import { PageHeader } from '@/components/PageHeader'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { EmptyState, ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import {
  formatViewQualitySummary,
  groupQualityLabels,
  groupQualityTone,
  receiptStateLabels,
  receiptTone,
  taskNextAction,
  type CaptureGroupNextStep,
  type CaptureGroupQualityVisibility,
  type CaptureGroupTaskVisibility,
} from '@/features/capture-group-presentation'
import { formatDateTime, shortId } from '@/features/catalog/catalog-presentation'

interface CaptureGroupListRow {
  group: CaptureGroupSummary
  quality: CaptureGroupQualityReview | null
  qualityVisibility: CaptureGroupQualityVisibility
  task: AnnotationTask | null
  taskVisibility: CaptureGroupTaskVisibility
}

type CaptureGroupListLoadingMode = 'initial' | 'refresh' | 'more' | null

export default function CaptureGroupListView() {
  const { projectId = '' } = useParams()
  const { session } = useAuth()
  const [rows, setRows] = useState<CaptureGroupListRow[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [loadingMode, setLoadingMode] = useState<CaptureGroupListLoadingMode>('initial')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const activeRequest = useRef<AbortController | null>(null)
  const rowsRef = useRef<CaptureGroupListRow[]>([])
  const hasUnavailableQuality = rows.some((row) => row.qualityVisibility === 'unavailable')
  const hasForbiddenQuality = rows.some((row) => row.qualityVisibility === 'forbidden')

  const loadGroups = useCallback(
    async (append = false, cursor: string | null = null) => {
      activeRequest.current?.abort()
      const request = new AbortController()
      activeRequest.current = request
      const requestMode: CaptureGroupListLoadingMode = append
        ? 'more'
        : rowsRef.current.length
          ? 'refresh'
          : 'initial'
      setLoadingMode(requestMode)
      setLoadState('loading')
      setErrorCode('')
      setErrorMessage('')
      try {
        const groupList = await listCaptureGroups(projectId, cursor, request.signal)
        if (request.signal.aborted || activeRequest.current !== request) return

        if (!groupList.items.length) {
          const nextRows = append ? rowsRef.current : []
          rowsRef.current = nextRows
          setRows(nextRows)
          setNextCursor(groupList.next_cursor)
          setLoadState('success')
          return
        }

        const [taskResult, qualityResult] = await Promise.all([
          listAnnotationTasks(projectId, request.signal)
            .then((value) => ({ status: 'fulfilled' as const, value }))
            .catch((error) => ({ status: 'rejected' as const, error })),
          listCaptureGroupQualityReviews(
            projectId,
            groupList.items.map((group) => group.capture_group_id),
            request.signal,
          )
            .then((value) => ({ status: 'fulfilled' as const, value }))
            .catch((error) => ({ status: 'rejected' as const, error })),
        ])
        const indexedTasks = taskResult.status === 'fulfilled' ? taskResult.value.items : []
        const qualityItems =
          qualityResult.status === 'fulfilled' && Array.isArray(qualityResult.value.items)
            ? qualityResult.value.items
            : []
        const indexedQuality = new Map(
          qualityItems.map((quality) => [quality.capture_group_id, quality]),
        )
        const qualityBatchComplete =
          qualityResult.status === 'fulfilled' &&
          Array.isArray(qualityResult.value.items) &&
          groupList.items.every((group) => indexedQuality.has(group.capture_group_id))
        const nextQualityVisibility: CaptureGroupQualityVisibility = qualityBatchComplete
          ? 'loaded'
          : qualityResult.status === 'rejected' &&
              isCaptureGroupQualityForbidden(qualityResult.error)
            ? 'forbidden'
            : 'unavailable'
        const taskVisibility: CaptureGroupTaskVisibility =
          taskResult.status === 'rejected'
            ? 'unavailable'
            : taskResult.value.progress.scope === 'PROJECT'
              ? 'visible'
              : 'permission-limited'
        const loadedRows = groupList.items.map((group): CaptureGroupListRow => {
          const quality = indexedQuality.get(group.capture_group_id) ?? null
          const task =
            indexedTasks.find(
              (candidate) => candidate.capture_group_id === group.capture_group_id,
            ) ?? null
          return {
            group,
            quality,
            qualityVisibility: nextQualityVisibility,
            task,
            taskVisibility: task ? 'visible' : taskVisibility,
          }
        })
        if (request.signal.aborted || activeRequest.current !== request) return
        const existingGroupIds = new Set(rowsRef.current.map((row) => row.group.capture_group_id))
        const nextRows = append
          ? [
              ...rowsRef.current,
              ...loadedRows.filter(({ group }) => !existingGroupIds.has(group.capture_group_id)),
            ]
          : loadedRows
        rowsRef.current = nextRows
        setRows(nextRows)
        setNextCursor(groupList.next_cursor)
        setLoadState('success')
      } catch (error) {
        if (request.signal.aborted || activeRequest.current !== request) return
        setLoadState('error')
        setErrorCode(
          error instanceof CaptureGroupApiError
            ? error.errorCode
            : 'CAPTURE_GROUP_LIST_REQUEST_FAILED',
        )
        setErrorMessage('暂时无法读取配药事件，请稍后重试。')
      } finally {
        if (activeRequest.current === request) {
          activeRequest.current = null
          setLoadingMode(null)
        }
      }
    },
    [projectId],
  )

  useEffect(() => {
    const timer = window.setTimeout(() => void loadGroups(), 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
      rowsRef.current = []
    }
  }, [loadGroups])

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑导航">
        <span>视频管理</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">配药事件</span>
      </nav>
      <PageHeader
        className="capture-group-page-header"
        titleId="capture-group-list-title"
        title="配药事件"
        description="按服务端已归组的接收记录展示拍摄时间、机位质量和事件质量；列表不推断未返回的事件。"
        actions={
          <Button
            size="small"
            icon={<RefreshCw aria-hidden="true" size={14} strokeWidth={1.8} />}
            loading={loadingMode === 'refresh'}
            onClick={() => void loadGroups()}
          >
            刷新
          </Button>
        }
      />
      {loadState === 'loading' && !rows.length ? (
        <LoadingState>正在读取配药事件…</LoadingState>
      ) : null}
      {loadState === 'error' ? (
        <ErrorState code={errorCode} onRetry={() => void loadGroups()}>
          {errorMessage}
        </ErrorState>
      ) : null}
      {loadState === 'success' && !rows.length ? (
        <EmptyState
          description={
            <div>
              <strong>还没有配药事件</strong>
              <br />
              完成明确归组的接收记录后，配药事件会显示在这里。
              <br />
              已有独立视频不会根据文件名、上传顺序或拍摄时间自动归组。
            </div>
          }
        >
          {session?.role === 'ADMIN' ? <Link to={paths.localUpload}>上传视频</Link> : null}
        </EmptyState>
      ) : null}
      {rows.length ? (
        <section className="capture-group-list-surface" aria-labelledby="capture-group-queue-title">
          <header className="capture-group-list-heading">
            <div>
              <p className="app-eyebrow">事件列表</p>
              <h2 id="capture-group-queue-title">配药事件</h2>
            </div>
            <span className="capture-group-list-count">{rows.length} 个配药事件</span>
          </header>
          <div className="capture-group-list" role="list" aria-label="配药事件列表">
            {rows.map(({ group, quality, qualityVisibility, task, taskVisibility }) => {
              const identity =
                group.capture_group_key ?? `配药事件 · ${shortId(group.capture_group_id)}`
              const nextStep = listTaskNextAction(
                qualityVisibility,
                group,
                quality,
                task,
                taskVisibility,
              )

              return (
                <article
                  className="capture-group-list-row"
                  key={group.capture_group_id}
                  role="listitem"
                  aria-label={identity}
                >
                  <header className="capture-group-list-row-header">
                    <div className="capture-group-list-identity">
                      <Camera aria-hidden="true" size={18} strokeWidth={1.7} />
                      <div>
                        <Link
                          className="text-link capture-group-list-title"
                          to={paths.captureGroup(projectId, group.capture_group_id)}
                        >
                          {identity}
                        </Link>
                      </div>
                    </div>
                    <StatusIndicator tone={receiptTone(group.receipt_state)}>
                      接收状态 · {receiptStateLabels[group.receipt_state]}
                    </StatusIndicator>
                  </header>
                  <dl className="capture-group-list-facts">
                    <div>
                      <dt>拍摄时间</dt>
                      <dd>{formatDateTime(group.device_time_utc ?? group.created_at)}</dd>
                    </div>
                    <div>
                      <dt>机位</dt>
                      <dd>
                        <span>
                          应 {group.expected_camera_count} · 已收 {group.received_camera_count} ·
                          缺失 {group.missing_camera_numbers.length}
                        </span>
                        {group.missing_camera_numbers.length ? (
                          <span className="capture-group-list-missing-views">
                            缺失：
                            {group.missing_camera_numbers
                              .map((cameraNumber) => `机位 ${cameraNumber}`)
                              .join('、')}
                          </span>
                        ) : null}
                      </dd>
                    </div>
                    <div>
                      <dt>机位质量</dt>
                      <dd>{formatListViewQuality(qualityVisibility, quality)}</dd>
                    </div>
                    <div>
                      <dt>事件质量</dt>
                      <dd>
                        <StatusIndicator tone={listGroupQualityTone(qualityVisibility, quality)}>
                          {formatListGroupQuality(qualityVisibility, quality)}
                        </StatusIndicator>
                      </dd>
                    </div>
                  </dl>
                  <footer className="capture-group-list-row-footer">
                    <span>
                      {nextStep.kind === 'action' ? '下一步：' : '当前状态：'}
                      {nextStep.label}
                      {task ? ` · 任务 ${shortId(task.annotation_task_id)}` : ''}
                    </span>
                    <Link
                      className="capture-group-list-open"
                      to={paths.captureGroup(projectId, group.capture_group_id)}
                    >
                      打开详情 <ArrowRight aria-hidden="true" size={14} strokeWidth={1.8} />
                    </Link>
                  </footer>
                </article>
              )
            })}
          </div>
          {nextCursor ? (
            <div className="capture-group-list-pagination">
              <Button
                loading={loadingMode === 'more'}
                size="small"
                onClick={() => void loadGroups(true, nextCursor)}
              >
                加载更多
              </Button>
            </div>
          ) : null}
        </section>
      ) : null}
      {loadState === 'success' && (hasUnavailableQuality || hasForbiddenQuality) ? (
        <Alert
          className="capture-group-list-note"
          type="info"
          showIcon
          title={hasForbiddenQuality ? '事件质量 · 无权查看' : '事件质量 · 暂不可用'}
          description={
            hasForbiddenQuality && !hasUnavailableQuality
              ? '当前账号无权读取事件质量；列表保留配药事件与接收状态。'
              : '当前批次的事件质量或机位质量暂时无法读取；列表保留配药事件与接收状态，请刷新后重试。'
          }
        />
      ) : null}
      {loadState === 'success' && rows.some((row) => row.taskVisibility !== 'visible') ? (
        <Alert
          className="capture-group-list-note"
          type="info"
          showIcon
          title="部分任务状态不可直接确认"
          description="普通项目成员只能看到分配给自己的任务；任务查询失败时，列表不会把未知状态误报为没有任务。"
        />
      ) : null}
    </>
  )
}

function formatListViewQuality(
  visibility: CaptureGroupQualityVisibility,
  quality: CaptureGroupQualityReview | null,
) {
  if (visibility === 'forbidden') return '无权查看'
  if (visibility === 'unavailable' || !quality) return '暂不可用'
  if (quality.result === 'UNREVIEWED') return groupQualityLabels.UNREVIEWED
  return formatViewQualitySummary(quality)
}

function formatListGroupQuality(
  visibility: CaptureGroupQualityVisibility,
  quality: CaptureGroupQualityReview | null,
) {
  if (visibility === 'forbidden') return '无权查看'
  if (visibility === 'unavailable' || !quality) return '暂不可用'
  return groupQualityLabels[quality.result]
}

function listGroupQualityTone(
  visibility: CaptureGroupQualityVisibility,
  quality: CaptureGroupQualityReview | null,
) {
  return visibility === 'loaded' && quality ? groupQualityTone(quality.result) : 'neutral'
}

function listTaskNextAction(
  qualityVisibility: CaptureGroupQualityVisibility,
  group: CaptureGroupSummary,
  quality: CaptureGroupQualityReview | null,
  task: AnnotationTask | null,
  taskVisibility: CaptureGroupTaskVisibility,
): CaptureGroupNextStep {
  if (qualityVisibility === 'forbidden')
    return { kind: 'status', label: '事件质量按当前权限不可见' }
  if (qualityVisibility === 'unavailable') {
    return { kind: 'status', label: '事件质量暂不可用，请刷新后重试' }
  }
  return taskNextAction(group, quality, task, taskVisibility)
}
