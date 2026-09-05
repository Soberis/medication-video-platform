import { Button } from 'antd'
import { ArrowRight, RefreshCw } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import {
  CaptureGroupApiError,
  listCaptureGroups,
  type CaptureGroupSummary,
} from '@/api/capture-groups'
import { listAnnotationTasks, type AnnotationTask } from '@/api/annotation'
import { useAuth } from '@/app/auth'
import { paths } from '@/app/paths'
import { PageHeader } from '@/components/PageHeader'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { EmptyState, ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import {
  captureGroupTaskDestination,
  receiptStateLabels,
  receiptTone,
} from '@/features/capture-group-presentation'
import { formatDateTime, shortId } from '@/features/catalog/catalog-presentation'
import { HandLandmarkExportButton } from '@/features/capture-group/HandLandmarkExportButton'

interface CaptureGroupListRow {
  group: CaptureGroupSummary
  task: AnnotationTask | null
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

        // Human tasks are an optional inbox. A failed task read must not turn a
        // healthy data-group/media read into an error or a blocked state.
        const taskResult = await listAnnotationTasks(projectId, request.signal).catch(() => null)
        if (request.signal.aborted || activeRequest.current !== request) return
        const indexedTasks = taskResult?.items ?? []
        const loadedRows = groupList.items.map(
          (group): CaptureGroupListRow => ({
            group,
            task:
              indexedTasks.find(
                (candidate) => candidate.capture_group_id === group.capture_group_id,
              ) ?? null,
          }),
        )
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
        setErrorMessage('暂时无法读取数据组，请稍后重试。')
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
      <PageHeader
        className="capture-group-page-header"
        titleId="capture-group-list-title"
        title="数据组"
        description="按数据组查看接收事实、视频资产、Media Probe 与 MediaPipe 检测证据。"
        actions={
          <>
            {session?.role === 'ADMIN' ? <HandLandmarkExportButton projectId={projectId} /> : null}
            <Button
              type="text"
              size="small"
              icon={<RefreshCw aria-hidden="true" size={14} strokeWidth={1.8} />}
              loading={loadingMode === 'refresh'}
              onClick={() => void loadGroups()}
            >
              刷新
            </Button>
          </>
        }
      />
      {loadState === 'loading' && !rows.length ? (
        <LoadingState>正在读取数据组…</LoadingState>
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
              <strong>还没有数据组</strong>
              <br />
              完成明确归组的接收记录后，数据组会显示在这里。
              <br />
              已有独立视频不会根据文件名、上传顺序或拍摄时间自动归组。
            </div>
          }
        >
          {session?.role === 'ADMIN' ? <Link to={paths.localUpload}>上传视频</Link> : null}
        </EmptyState>
      ) : null}
      {rows.length ? (
        <section className="capture-group-list-surface" aria-label="数据组结果">
          <div className="capture-group-list-meta">
            <span className="capture-group-list-count">
              {nextCursor ? `已显示 ${rows.length} 个数据组` : `共 ${rows.length} 个数据组`}
            </span>
          </div>
          <div className="capture-group-list" role="list" aria-label="数据组列表">
            {rows.map(({ group, task }) => {
              const identity = group.capture_group_key ?? shortId(group.capture_group_id)
              const identityLabel = `数据组 · ${identity}`
              const eventTime = group.device_time_utc ?? group.created_at
              const eventTimeLabel = group.device_time_utc ? '拍摄时间' : '记录时间'
              const taskDestination = task ? captureGroupTaskDestination(task) : null

              return (
                <div
                  className="capture-group-list-row"
                  key={group.capture_group_id}
                  role="listitem"
                  aria-label={identityLabel}
                >
                  <header className="capture-group-list-row-header">
                    <div className="capture-group-list-identity">
                      <strong className="capture-group-list-title">{identity}</strong>
                    </div>
                    <time className="capture-group-list-time" dateTime={eventTime}>
                      <span className="visually-hidden">{eventTimeLabel}：</span>
                      {formatDateTime(eventTime)}
                    </time>
                  </header>
                  <dl className="capture-group-list-facts">
                    <div>
                      <dt className="visually-hidden">接收 / 机位</dt>
                      <dd className="capture-group-list-receipt">
                        <StatusIndicator tone={receiptTone(group.receipt_state)}>
                          {formatListReceipt(group)}
                        </StatusIndicator>
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
                      <dt className="visually-hidden">处理路径</dt>
                      <dd>
                        {task ? (
                          <StatusIndicator tone="information">已有人工标签任务</StatusIndicator>
                        ) : (
                          <span className="capture-group-list-machine-path">
                            MediaPipe machine evidence
                          </span>
                        )}
                      </dd>
                    </div>
                    <div>
                      <dt className="visually-hidden">事实入口</dt>
                      <dd className="capture-group-list-evidence-hint">
                        Media Probe · hand-landmarks.v1
                      </dd>
                    </div>
                  </dl>
                  <footer className="capture-group-list-row-footer">
                    <Link
                      className="capture-group-list-open"
                      aria-label={`查看数据组：${identityLabel}`}
                      to={paths.captureGroup(projectId, group.capture_group_id)}
                    >
                      查看数据组 <ArrowRight aria-hidden="true" size={14} strokeWidth={1.8} />
                    </Link>
                    {taskDestination ? (
                      <Link
                        className="text-link"
                        aria-label={`${taskDestination.actionLabel}：${identityLabel}`}
                        to={paths.captureGroupFocus(
                          projectId,
                          group.capture_group_id,
                          taskDestination.focus,
                        )}
                      >
                        {taskDestination.actionLabel}
                      </Link>
                    ) : null}
                  </footer>
                </div>
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
    </>
  )
}

function formatListReceipt(group: CaptureGroupSummary) {
  const receipt = `${group.received_camera_count}/${group.expected_camera_count} 机位已收`
  return group.receipt_state === 'COMPLETE'
    ? receipt
    : `${receipt} · ${receiptStateLabels[group.receipt_state]}`
}
