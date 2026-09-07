import { Button, Checkbox, Segmented } from 'antd'
import { ArrowRight, RefreshCw, Video } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import AnnotationTaskListView from '@/views/AnnotationTaskListView'

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

interface ListReturnState {
  projectId: string
  viewer: string
  rows: CaptureGroupSummary[]
  nextCursor: string | null
  scrollY: number
}

export default function CaptureGroupListView() {
  const { projectId = '' } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const activeView = searchParams.get('view') ?? 'all'
  const { session } = useAuth()
  const viewer = `${session?.username}:${session?.role}`
  const savedList = (location.state as { captureGroupList?: ListReturnState } | null)
    ?.captureGroupList
  const [restoredList] = useState(
    savedList?.projectId === projectId && savedList.viewer === viewer ? savedList : null,
  )
  const [rows, setRows] = useState<CaptureGroupSummary[]>(restoredList?.rows ?? [])
  const [nextCursor, setNextCursor] = useState<string | null>(restoredList?.nextCursor ?? null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>(
    restoredList ? 'success' : 'loading',
  )
  const [loadingMode, setLoadingMode] = useState<CaptureGroupListLoadingMode>(
    restoredList ? null : 'initial',
  )
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [taskReadState, setTaskReadState] = useState<AnnotationTaskReadState>({
    status: 'loading',
  })
  const [selectedExportIds, setSelectedExportIds] = useState<Set<string>>(() => new Set())
  const groupRequest = useRef<RequestToken | null>(null)
  const taskRequest = useRef<RequestToken | null>(null)
  const groupGeneration = useRef(0)
  const taskGeneration = useRef(0)
  const rowsRef = useRef<CaptureGroupSummary[]>([])
  const exportableGroupIds =
    taskReadState.status === 'success'
      ? rows
          .filter((group) =>
            taskReadState.items.some(
              (task) =>
                task.capture_group_id === group.capture_group_id &&
                isHumanAnnotationExportReady(task),
            ),
          )
          .map((group) => group.capture_group_id)
      : []
  const selectedExportGroupIds = exportableGroupIds.filter((id) => selectedExportIds.has(id))

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
    const restored = restoredList?.projectId === projectId ? restoredList : null
    rowsRef.current = restored?.rows ?? []
    const timer = window.setTimeout(() => {
      if (restored) window.scrollTo(0, restored.scrollY)
      else void loadGroups()
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
  }, [loadGroups, loadTasks, projectId, restoredList])

  return (
    <>
      <PageHeader
        className="capture-group-page-header"
        titleId="capture-group-list-title"
        title="视频"
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
                  selectedCaptureGroupIds={selectedExportGroupIds}
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
      <Segmented
        aria-label="视频视图"
        value={activeView}
        onChange={(value) => setSearchParams(value === 'all' ? {} : { view: value })}
        options={[
          { label: '全部视频', value: 'all' },
          { label: '待标注', value: 'assigned' },
          { label: '待复核', value: 'submitted' },
          { label: '待返工', value: 'rework' },
          { label: '已完成', value: 'completed' },
          { label: '我的待办', value: 'mine' },
          ...(activeView === 'tasks' ? [{ label: '全部任务', value: 'tasks' }] : []),
        ]}
      />
      {activeView !== 'all' ? (
        <AnnotationTaskListView embedded filter={activeView} />
      ) : (
        <>
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
                  const exportable = Boolean(task && isHumanAnnotationExportReady(task))
                  return (
                    <div
                      className="capture-group-list-row"
                      key={group.capture_group_id}
                      role="listitem"
                      aria-label={identityLabel}
                    >
                      <header className="capture-group-list-row-header">
                        <div className="capture-group-list-identity">
                          <Video aria-hidden="true" size={16} strokeWidth={1.8} />
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
                            ) : null}
                          </dd>
                        </div>
                      </dl>
                      <footer className="capture-group-list-row-footer">
                        {session?.role === 'ADMIN' ? (
                          <Checkbox
                            disabled={!exportable}
                            checked={selectedExportIds.has(group.capture_group_id)}
                            onChange={(event) => {
                              setSelectedExportIds((current) => {
                                const next = new Set(current)
                                if (event.target.checked) next.add(group.capture_group_id)
                                else next.delete(group.capture_group_id)
                                return next
                              })
                            }}
                          >
                            {exportable
                              ? '选择导出'
                              : taskReadState.status !== 'success'
                                ? '任务状态未确认'
                                : !task
                                  ? '尚未标注'
                                  : task.status === 'SUBMITTED'
                                    ? '待复核，暂不可导出'
                                    : '标注未通过复核'}
                          </Checkbox>
                        ) : null}
                        <Link
                          className="capture-group-list-open"
                          aria-label={`查看视频：${identityLabel}`}
                          to={paths.captureGroup(projectId, group.capture_group_id)}
                          onClick={(event) => {
                            if (
                              event.button !== 0 ||
                              event.metaKey ||
                              event.ctrlKey ||
                              event.shiftKey ||
                              event.altKey
                            )
                              return
                            event.preventDefault()
                            const captureGroupList: ListReturnState = {
                              projectId,
                              viewer,
                              rows,
                              nextCursor,
                              scrollY: window.scrollY,
                            }
                            void (async () => {
                              await navigate(location.pathname + location.search, {
                                replace: true,
                                state: { captureGroupList },
                              })
                              await navigate(
                                paths.captureGroup(projectId, group.capture_group_id),
                                {
                                  state: { captureGroupList },
                                },
                              )
                            })()
                          }}
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
      )}
    </>
  )
}

function formatListReceipt(group: CaptureGroupSummary) {
  const receipt = `${group.received_camera_count}/${group.expected_camera_count} 机位已收`
  return group.receipt_state === 'COMPLETE'
    ? receipt
    : `${receipt} · ${receiptStateLabels[group.receipt_state]}`
}
