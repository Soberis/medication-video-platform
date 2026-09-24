import { Button, Input, Pagination, Select } from 'antd'
import { ArrowRight, Search, Video } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import AnnotationTaskListView from '@/views/AnnotationTaskListView'

import {
  CaptureGroupApiError,
  listCaptureGroups,
  type CaptureGroupSummary,
} from '@/api/capture-groups'
import { listAnnotationTasks, type AnnotationTask } from '@/api/annotation'
import { getProject, type DataProject } from '@/api/projects'
import { useAuth } from '@/app/auth'
import { paths } from '@/app/paths'
import { PageHeader } from '@/components/PageHeader'
import { ProjectBreadcrumb } from '@/components/ProjectBreadcrumb'
import { RefreshButton } from '@/components/RefreshButton'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { EmptyState, ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { receiptStateLabels, receiptTone } from '@/features/capture-group-presentation'
import { formatDateTime, shortId } from '@/features/catalog/catalog-presentation'
import { ExportDataMenu } from '@/features/capture-group/ExportDataMenu'
import { CopyableText } from '@/components/data/CopyableText'
import { formatTaskStatus } from '@/features/capture-group/capture-group-detail-model'

export const CAPTURE_GROUP_LIST_POLL_INTERVAL_MS = 20_000

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
  const isPlatformAdministrator = session?.role === 'ADMIN'
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
  const [project, setProject] = useState<DataProject | null>(null)
  const currentProject = project?.project_id === projectId ? project : null
  const [searchQuery, setSearchQuery] = useState('')
  const [currentPage, setCurrentPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [refreshEpoch, setRefreshEpoch] = useState(0)
  const groupRequest = useRef<RequestToken | null>(null)
  const taskRequest = useRef<RequestToken | null>(null)
  const groupGeneration = useRef(0)
  const taskGeneration = useRef(0)
  const rowsRef = useRef<CaptureGroupSummary[]>([])
  const tasksByGroupId = useMemo(() => {
    if (taskReadState.status !== 'success') return new Map<string, AnnotationTask>()
    return new Map(taskReadState.items.map((task) => [task.capture_group_id, task]))
  }, [taskReadState])

  const filteredRows = useMemo(() => {
    const query = searchQuery.trim().toLowerCase()
    if (!query) return rows
    return rows.filter((group) => {
      const identity = (group.capture_group_key ?? shortId(group.capture_group_id)).toLowerCase()
      const fullId = group.capture_group_id.toLowerCase()
      const timeStr = formatDateTime(group.device_time_utc ?? group.created_at).toLowerCase()
      return identity.includes(query) || fullId.includes(query) || timeStr.includes(query)
    })
  }, [rows, searchQuery])

  const paginatedRows = useMemo(() => {
    const startIndex = (currentPage - 1) * pageSize
    return filteredRows.slice(startIndex, startIndex + pageSize)
  }, [filteredRows, currentPage, pageSize])

  const loadGroups = useCallback(
    async (append = false, cursor: string | null = null, silent = false) => {
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
          ? silent
            ? null
            : 'refresh'
          : 'initial'
      if (requestMode === 'initial') {
        setRows([])
        setNextCursor(null)
        setCurrentPage(1)
      } else if (append) {
        setCurrentPage(1)
      }
      setLoadingMode(requestMode)
      if (!silent) {
        setLoadState('loading')
        setErrorCode('')
        setErrorMessage('')
      }
      try {
        const groupList = await listCaptureGroups(projectId, cursor, request.controller.signal, 100)
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
        if (!silent) {
          setLoadState('error')
          setErrorCode(
            error instanceof CaptureGroupApiError
              ? error.errorCode
              : 'CAPTURE_GROUP_LIST_REQUEST_FAILED',
          )
          setErrorMessage('暂时无法读取采集记录，请稍后重试。')
        }
      } finally {
        if (groupRequest.current === request) {
          groupRequest.current = null
          setLoadingMode(null)
        }
      }
    },
    [projectId],
  )

  const loadTasks = useCallback(
    async (silent = false) => {
      taskRequest.current?.controller.abort()
      const request: RequestToken = {
        controller: new AbortController(),
        generation: taskGeneration.current + 1,
      }
      taskGeneration.current = request.generation
      taskRequest.current = request
      if (!silent) {
        setTaskReadState({ status: 'loading' })
      }

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
        if (!silent) {
          setTaskReadState({ status: 'error' })
        }
      } finally {
        if (taskRequest.current === request) taskRequest.current = null
      }
    },
    [projectId],
  )

  useEffect(() => {
    const request = new AbortController()
    void getProject(projectId, request.signal)
      .then((project) => {
        if (!request.signal.aborted) setProject(project)
      })
      .catch(() => {
        if (!request.signal.aborted) setProject(null)
      })
    return () => request.abort()
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

  useEffect(() => {
    if (
      activeView !== 'all' ||
      currentPage !== 1 ||
      searchQuery.trim() !== '' ||
      loadState !== 'success'
    ) {
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
          await Promise.all([loadGroups(false, null, true), loadTasks(true)])
        }
        if (!isDisposed) {
          scheduleNext()
        }
      }, CAPTURE_GROUP_LIST_POLL_INTERVAL_MS)
    }

    scheduleNext()

    const handleVisibilityChange = () => {
      if (!document.hidden) {
        void Promise.all([loadGroups(false, null, true), loadTasks(true)])
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
  }, [activeView, currentPage, loadGroups, loadState, loadTasks, searchQuery])

  const openCaptureGroup = useCallback(
    (targetGroupId: string) => {
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
        await navigate(paths.captureGroup(projectId, targetGroupId), {
          state: { captureGroupList },
        })
      })()
    },
    [location.pathname, location.search, navigate, nextCursor, projectId, rows, viewer],
  )

  return (
    <>
      <ProjectBreadcrumb projectId={projectId} currentProject={currentProject}>
        <span>视频</span>
      </ProjectBreadcrumb>
      <PageHeader
        className="capture-group-page-header"
        titleId="capture-group-list-title"
        eyebrow="采集记录"
        title="视频"
        actions={
          <>
            {session?.role === 'ADMIN' ? (
              <>
                <Button
                  type="primary"
                  onClick={() =>
                    void navigate(`${paths.localUpload}?projectId=${encodeURIComponent(projectId)}`)
                  }
                >
                  上传视频
                </Button>
                {isPlatformAdministrator || currentProject?.current_role === 'ADMIN' ? (
                  <ExportDataMenu key={`${projectId}:${viewer}`} projectId={projectId} />
                ) : null}
              </>
            ) : null}
            <RefreshButton
              loading={loadingMode === 'refresh'}
              onClick={() => {
                setRefreshEpoch((v) => v + 1)
                void loadGroups()
                void loadTasks()
              }}
            />
          </>
        }
      />
      <div className="capture-group-list-toolbar">
        <div className="capture-group-list-toolbar-filters">
          <Select
            aria-label="视频视图"
            id="capture-group-view-select"
            className="capture-group-view-select"
            value={activeView}
            onChange={(value) => {
              setCurrentPage(1)
              setSearchParams(value === 'all' ? {} : { view: value })
            }}
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
          {activeView === 'all' && rows.length ? (
            <span className="capture-group-list-count">
              {nextCursor ? `已显示 ${rows.length} 条采集记录` : `共 ${rows.length} 条采集记录`}
            </span>
          ) : null}
        </div>
        {rows.length || activeView !== 'all' ? (
          <div className="capture-group-list-toolbar-actions">
            <Input
              className="capture-group-list-search"
              placeholder={activeView === 'all' ? '搜索视频标识或时间…' : '搜索任务或采集记录…'}
              prefix={<Search size={14} className="capture-group-search-icon" />}
              allowClear
              value={searchQuery}
              onChange={(e) => {
                setSearchQuery(e.target.value)
                setCurrentPage(1)
              }}
              onKeyDown={(e) => {
                if (e.key === 'Escape') {
                  setSearchQuery('')
                  setCurrentPage(1)
                }
              }}
            />
          </div>
        ) : null}
      </div>
      {activeView !== 'all' ? (
        <AnnotationTaskListView
          embedded
          filter={activeView}
          searchQuery={searchQuery}
          key={`${activeView}:${refreshEpoch}`}
        />
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
              {session?.role === 'ADMIN' ? (
                <Button
                  type="primary"
                  onClick={() =>
                    void navigate(`${paths.localUpload}?projectId=${encodeURIComponent(projectId)}`)
                  }
                >
                  上传视频
                </Button>
              ) : null}
            </EmptyState>
          ) : null}
          {rows.length ? (
            <section className="capture-group-list-surface" aria-label="采集记录结果">
              {filteredRows.length === 0 ? (
                <div className="capture-group-list-empty-search">
                  <span>未找到与 “{searchQuery}” 匹配的采集记录</span>
                  <Button size="small" onClick={() => setSearchQuery('')}>
                    清除搜索
                  </Button>
                </div>
              ) : (
                <div className="capture-group-list" role="list" aria-label="采集记录列表">
                  <div className="capture-group-list-header" role="row" aria-hidden="true">
                    <div className="capture-group-list-th capture-group-list-th-identity">
                      <span>采集标识</span>
                    </div>
                    <div className="capture-group-list-th capture-group-list-th-time">
                      <span>记录时间</span>
                    </div>
                    <div className="capture-group-list-th capture-group-list-th-facts">
                      <span>机位与状态</span>
                    </div>
                    <div className="capture-group-list-th capture-group-list-th-action">
                      <span>操作</span>
                    </div>
                  </div>
                  {paginatedRows.map((group) => {
                    const identity = group.capture_group_key ?? shortId(group.capture_group_id)
                    const identityLabel = `采集记录 · ${identity}`
                    const eventTime = group.device_time_utc ?? group.created_at
                    const eventTimeLabel = group.device_time_utc ? '拍摄时间' : '记录时间'
                    const task = tasksByGroupId.get(group.capture_group_id)
                    return (
                      <div
                        className="capture-group-list-row"
                        key={group.capture_group_id}
                        role="listitem"
                        tabIndex={0}
                        aria-label={identityLabel}
                        onClick={(event) => {
                          const target = event.target as HTMLElement
                          if (
                            target.closest('a') ||
                            target.closest('button') ||
                            (window.getSelection()?.toString().length ?? 0) > 0
                          ) {
                            return
                          }
                          openCaptureGroup(group.capture_group_id)
                        }}
                        onKeyDown={(event) => {
                          if (event.key !== 'Enter' && event.key !== ' ') return
                          const target = event.target as HTMLElement
                          if (target.closest('a') || target.closest('button')) return
                          event.preventDefault()
                          openCaptureGroup(group.capture_group_id)
                        }}
                      >
                        <header className="capture-group-list-row-header">
                          <div className="capture-group-list-identity">
                            <Video aria-hidden="true" size={16} strokeWidth={1.8} />
                            <strong
                              className="capture-group-list-title"
                              title={`完整 ID: ${group.capture_group_id}`}
                            >
                              {identity}
                            </strong>
                            <CopyableText
                              value={group.capture_group_id}
                              label="复制完整 ID"
                              compact
                              hideCode
                            />
                          </div>
                        </header>
                        <time className="capture-group-list-time" dateTime={eventTime}>
                          <span className="visually-hidden">{eventTimeLabel}：</span>
                          {formatDateTime(eventTime)}
                        </time>
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
                                    type="text"
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
                          <Link
                            className="capture-group-list-open"
                            aria-label={`查看视频：${identityLabel}`}
                            to={paths.captureGroup(projectId, group.capture_group_id)}
                            onClick={(event: React.MouseEvent) => {
                              if (
                                event.button !== 0 ||
                                event.metaKey ||
                                event.ctrlKey ||
                                event.shiftKey ||
                                event.altKey
                              )
                                return
                              event.preventDefault()
                              openCaptureGroup(group.capture_group_id)
                            }}
                          >
                            <span>查看视频</span>
                            <ArrowRight aria-hidden="true" size={14} strokeWidth={1.8} />
                          </Link>
                        </footer>
                      </div>
                    )
                  })}
                </div>
              )}
              {nextCursor || filteredRows.length > pageSize ? (
                <div className="capture-group-list-pagination">
                  <div className="capture-group-list-pagination-left">
                    {nextCursor ? (
                      <Button
                        loading={loadingMode === 'more'}
                        size="small"
                        onClick={() => void loadGroups(true, nextCursor)}
                      >
                        加载更多
                      </Button>
                    ) : (
                      <span className="capture-group-list-pagination-summary">
                        共 {filteredRows.length} 条
                      </span>
                    )}
                  </div>
                  {filteredRows.length > pageSize ? (
                    <Pagination
                      size="small"
                      current={currentPage}
                      pageSize={pageSize}
                      total={filteredRows.length}
                      showSizeChanger
                      pageSizeOptions={['10', '20', '50']}
                      showTotal={(total, range) => `第 ${range[0]}-${range[1]} 条 / 共 ${total} 条`}
                      onChange={(page, newPageSize) => {
                        setCurrentPage(page)
                        if (newPageSize && newPageSize !== pageSize) {
                          setPageSize(newPageSize)
                          setCurrentPage(1)
                        }
                      }}
                    />
                  ) : null}
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
