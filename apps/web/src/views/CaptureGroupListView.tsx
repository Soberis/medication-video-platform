import { Button } from 'antd'
import { ArrowRight, RefreshCw } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'

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
  isHumanAnnotationExportReady,
  receiptStateLabels,
  receiptTone,
} from '@/features/capture-group-presentation'
import { formatDateTime, shortId } from '@/features/catalog/catalog-presentation'
import { ExportDataMenu } from '@/features/capture-group/ExportDataMenu'
import { formatTaskStatus } from '@/features/capture-group/capture-group-detail-model'

type CaptureGroupListLoadingMode = 'initial' | 'refresh' | 'more' | null

type AnnotationTaskReadState =
  | { status: 'loading' }
  | { status: 'success'; scope: 'PROJECT' | 'ASSIGNEE'; items: AnnotationTask[] }
  | { status: 'error' }

interface RequestToken {
  controller: AbortController
  generation: number
}

export default function CaptureGroupListView() {
  const { projectId = '' } = useParams()
  const navigate = useNavigate()
  const { session } = useAuth()
  const [rows, setRows] = useState<CaptureGroupSummary[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [loadingMode, setLoadingMode] = useState<CaptureGroupListLoadingMode>('initial')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [taskReadState, setTaskReadState] = useState<AnnotationTaskReadState>({
    status: 'loading',
  })
  const groupRequest = useRef<RequestToken | null>(null)
  const taskRequest = useRef<RequestToken | null>(null)
  const groupGeneration = useRef(0)
  const taskGeneration = useRef(0)
  const rowsRef = useRef<CaptureGroupSummary[]>([])

  const loadGroups = useCallback(
    async (append = false, cursor: string | null = null) => {
      groupRequest.current?.controller.abort()
      const request: RequestToken = {
        controller: new AbortController(),
        generation: groupGeneration.current + 1,
      }
      groupGeneration.current = request.generation
      groupRequest.current = request
      const requestMode: CaptureGroupListLoadingMode = append
        ? 'more'
        : rowsRef.current.length
          ? 'refresh'
          : 'initial'
      if (requestMode === 'initial') {
        setRows([])
        setNextCursor(null)
      }
      setLoadingMode(requestMode)
      setLoadState('loading')
      setErrorCode('')
      setErrorMessage('')
      try {
        const groupList = await listCaptureGroups(projectId, cursor, request.controller.signal)
        if (
          request.controller.signal.aborted ||
          groupRequest.current !== request ||
          groupGeneration.current !== request.generation
        ) {
          return
        }
        const loadedRows = groupList.items
        const existingGroupIds = new Set(rowsRef.current.map((row) => row.capture_group_id))
        const nextRows = append
          ? [
              ...rowsRef.current,
              ...loadedRows.filter(
                ({ capture_group_id }) => !existingGroupIds.has(capture_group_id),
              ),
            ]
          : loadedRows
        rowsRef.current = nextRows
        setRows(nextRows)
        setNextCursor(groupList.next_cursor)
        setLoadState('success')
      } catch (error) {
        if (
          request.controller.signal.aborted ||
          groupRequest.current !== request ||
          groupGeneration.current !== request.generation
        ) {
          return
        }
        setLoadState('error')
        setErrorCode(
          error instanceof CaptureGroupApiError
            ? error.errorCode
            : 'CAPTURE_GROUP_LIST_REQUEST_FAILED',
        )
        setErrorMessage('暂时无法读取采集记录，请稍后重试。')
      } finally {
        if (groupRequest.current === request) {
          groupRequest.current = null
          setLoadingMode(null)
        }
      }
    },
    [projectId],
  )

  const loadTasks = useCallback(async () => {
    taskRequest.current?.controller.abort()
    const request: RequestToken = {
      controller: new AbortController(),
      generation: taskGeneration.current + 1,
    }
    taskGeneration.current = request.generation
    taskRequest.current = request
    setTaskReadState({ status: 'loading' })

    try {
      const taskList = await listAnnotationTasks(projectId, request.controller.signal)
      if (
        request.controller.signal.aborted ||
        taskRequest.current !== request ||
        taskGeneration.current !== request.generation
      ) {
        return
      }
      setTaskReadState({
        status: 'success',
        scope: taskList.progress.scope,
        items: taskList.items,
      })
    } catch {
      if (
        request.controller.signal.aborted ||
        taskRequest.current !== request ||
        taskGeneration.current !== request.generation
      ) {
        return
      }
      setTaskReadState({ status: 'error' })
    } finally {
      if (taskRequest.current === request) taskRequest.current = null
    }
  }, [projectId])

  useEffect(() => {
    rowsRef.current = []
    const timer = window.setTimeout(() => {
      void loadGroups()
      void loadTasks()
    }, 0)

    return () => {
      window.clearTimeout(timer)
      groupGeneration.current += 1
      taskGeneration.current += 1
      groupRequest.current?.controller.abort()
      taskRequest.current?.controller.abort()
      groupRequest.current = null
      taskRequest.current = null
      rowsRef.current = []
    }
  }, [loadGroups, loadTasks])

  return (
    <>
      <PageHeader
        className="capture-group-page-header"
        titleId="capture-group-list-title"
        title="视频"
        description="查看视频、标注动作，导出训练所需数据。同一次采集的多相机视频保存在一起。"
        actions={
          <>
            {session?.role === 'ADMIN' ? (
              <>
                <Button type="primary" onClick={() => void navigate(paths.localUpload)}>
                  上传视频
                </Button>
                <ExportDataMenu
                  key={projectId}
                  projectId={projectId}
                  humanReady={
                    taskReadState.status === 'success' &&
                    taskReadState.items.some(isHumanAnnotationExportReady)
                  }
                />
              </>
            ) : null}
            <Button
              type="text"
              size="small"
              icon={<RefreshCw aria-hidden="true" size={14} strokeWidth={1.8} />}
              loading={loadingMode === 'refresh'}
              onClick={() => {
                void loadGroups()
                void loadTasks()
              }}
            >
              刷新
            </Button>
          </>
        }
      />
      {loadState === 'loading' && !rows.length ? (
        <LoadingState>正在读取采集记录…</LoadingState>
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
              <strong>还没有视频</strong>
              <br />
              上传视频后，即可查看检测结果并添加标注。
            </div>
          }
        >
          {session?.role === 'ADMIN' ? <Link to={paths.localUpload}>上传视频</Link> : null}
        </EmptyState>
      ) : null}
      {rows.length ? (
        <section className="capture-group-list-surface" aria-label="采集记录结果">
          <div className="capture-group-list-meta">
            <span className="capture-group-list-count">
              {nextCursor ? `已显示 ${rows.length} 条采集记录` : `共 ${rows.length} 条采集记录`}
            </span>
          </div>
          <div className="capture-group-list" role="list" aria-label="采集记录列表">
            {rows.map((group) => {
              const identity = group.capture_group_key ?? shortId(group.capture_group_id)
              const identityLabel = `采集记录 · ${identity}`
              const eventTime = group.device_time_utc ?? group.created_at
              const eventTimeLabel = group.device_time_utc ? '拍摄时间' : '记录时间'
              const task =
                taskReadState.status === 'success'
                  ? taskReadState.items.find(
                      (candidate) => candidate.capture_group_id === group.capture_group_id,
                    )
                  : undefined
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
                      <dt className="visually-hidden">人工标签任务</dt>
                      <dd>
                        {taskReadState.status === 'loading' ? (
                          <StatusIndicator ariaLive="polite" tone="information">
                            人工任务读取中
                          </StatusIndicator>
                        ) : taskReadState.status === 'error' ? (
                          <>
                            <StatusIndicator ariaLive="polite" tone="warning">
                              人工任务状态暂不可用
                            </StatusIndicator>
                            <Button
                              type="link"
                              size="small"
                              aria-label="重试读取人工任务"
                              onClick={() => void loadTasks()}
                            >
                              重试
                            </Button>
                          </>
                        ) : task ? (
                          <StatusIndicator tone="information">
                            {formatTaskStatus(task)}
                          </StatusIndicator>
                        ) : (
                          <span className="capture-group-list-machine-path">
                            {taskReadState.scope === 'ASSIGNEE'
                              ? '没有分配给我的人工标签任务'
                              : '暂无人工标签任务'}
                          </span>
                        )}
                      </dd>
                    </div>
                  </dl>
                  <footer className="capture-group-list-row-footer">
                    <Link
                      className="capture-group-list-open"
                      aria-label={`查看视频：${identityLabel}`}
                      to={paths.captureGroup(projectId, group.capture_group_id)}
                    >
                      查看视频 <ArrowRight aria-hidden="true" size={14} strokeWidth={1.8} />
                    </Link>
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
