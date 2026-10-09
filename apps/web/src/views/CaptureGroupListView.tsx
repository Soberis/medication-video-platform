import { App as AntdApp, Button, Input, Pagination, Popconfirm, Select } from 'antd'
import { ArrowRight, RotateCw, Search, Trash2, Video } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import AnnotationTaskListView from '@/views/AnnotationTaskListView'

import {
  CaptureGroupApiError,
  cleanInvalidCaptureGroups,
  fetchCaptureGroupsPipeline,
  listAllCaptureGroupSummaries,
  retryFailedHandPoseBatch,
  type CaptureGroupPipelineItem,
  type CaptureGroupPipelineStats,
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
import { isHumanAnnotationExportReady, receiptStateLabels, receiptTone } from '@/features/capture-group-presentation'
import { formatDateTime, parseAssetIdentity, shortId } from '@/features/catalog/catalog-presentation'
import { ExportDataMenu } from '@/features/capture-group/ExportDataMenu'
import { CopyableText } from '@/components/data/CopyableText'
import { formatTaskStatus } from '@/features/capture-group/capture-group-detail-model'

export const CAPTURE_GROUP_LIST_POLL_INTERVAL_MS = 20_000

type CaptureGroupListLoadingMode = 'initial' | 'refresh' | null

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
  scrollY: number
  currentPage?: number
  pageSize?: number
}

export default function CaptureGroupListView() {
  const { projectId = '' } = useParams()
  const { message } = AntdApp.useApp()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const activeView = searchParams.get('view') ?? 'all'
  const isTaskView = ['assigned', 'submitted', 'rework', 'completed', 'mine', 'tasks'].includes(activeView)
  const { session } = useAuth()
  const isPlatformAdministrator = session?.role === 'ADMIN'
  const viewer = `${session?.username}:${session?.role}`
  const savedList = (location.state as { captureGroupList?: ListReturnState } | null)
    ?.captureGroupList
  const [restoredList] = useState(
    savedList?.projectId === projectId && savedList.viewer === viewer ? savedList : null,
  )
  const [rows, setRows] = useState<CaptureGroupSummary[]>(restoredList?.rows ?? [])
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
  const [pipelineData, setPipelineData] = useState<{
    stats: CaptureGroupPipelineStats | null
    items: Record<string, CaptureGroupPipelineItem>
  }>({ stats: null, items: {} })
  const [isCleaningInvalid, setIsCleaningInvalid] = useState(false)
  const [isRetryingHands, setIsRetryingHands] = useState(false)
  const [project, setProject] = useState<DataProject | null>(null)
  const currentProject = project?.project_id === projectId ? project : null
  const [searchQuery, setSearchQuery] = useState('')
  const [currentPage, setCurrentPage] = useState(restoredList?.currentPage ?? 1)
  const [pageSize, setPageSize] = useState(restoredList?.pageSize ?? 10)
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

  const exportableRows = useMemo(() => {
    return rows.filter((g) => {
      const task = tasksByGroupId.get(g.capture_group_id)
      return isHumanAnnotationExportReady(task)
    })
  }, [rows, tasksByGroupId])

  const viewFilteredRows = useMemo(() => {
    if (activeView === 'ready') {
      return rows.filter((g) => pipelineData.items[g.capture_group_id]?.pipeline_status === 'READY')
    }
    if (activeView === 'processing') {
      return rows.filter((g) => pipelineData.items[g.capture_group_id]?.pipeline_status === 'PROCESSING')
    }
    if (activeView === 'invalid') {
      return rows.filter((g) => {
        const s = pipelineData.items[g.capture_group_id]?.pipeline_status
        return s === 'NO_HAND' || s === 'FAILED'
      })
    }
    if (activeView === 'exportable') {
      return exportableRows
    }
    return rows
  }, [activeView, exportableRows, pipelineData.items, rows])

  const filteredRows = useMemo(() => {
    const query = searchQuery.trim().toLowerCase()
    if (!query) return viewFilteredRows
    return viewFilteredRows.filter((group) => {
      const identity = (group.capture_group_key ?? shortId(group.capture_group_id)).toLowerCase()
      const fullId = group.capture_group_id.toLowerCase()
      const timeStr = formatDateTime(group.device_time_utc ?? group.created_at).toLowerCase()
      return identity.includes(query) || fullId.includes(query) || timeStr.includes(query)
    })
  }, [viewFilteredRows, searchQuery])

  const maxPage = Math.max(1, Math.ceil(filteredRows.length / pageSize))
  const safeCurrentPage = Math.min(currentPage, maxPage)

  const paginatedRows = useMemo(() => {
    const startIndex = (safeCurrentPage - 1) * pageSize
    return filteredRows.slice(startIndex, startIndex + pageSize)
  }, [filteredRows, safeCurrentPage, pageSize])

  const loadGroups = useCallback(
    async (silent = false) => {
      groupRequest.current?.controller.abort()
      const request: RequestToken = {
        controller: new AbortController(),
        generation: groupGeneration.current + 1,
      }
      groupGeneration.current = request.generation
      groupRequest.current = request
      const requestMode: CaptureGroupListLoadingMode = rowsRef.current.length
        ? silent
          ? null
          : 'refresh'
        : 'initial'
      if (requestMode === 'initial') {
        setRows([])
        setCurrentPage(1)
      }
      setLoadingMode(requestMode)
      if (!silent) {
        setLoadState('loading')
        setErrorCode('')
        setErrorMessage('')
      }
      try {
        const loadedRows = await listAllCaptureGroupSummaries(
          projectId,
          request.controller.signal,
        )
        if (
          request.controller.signal.aborted ||
          groupRequest.current !== request ||
          groupGeneration.current !== request.generation
        ) {
          return
        }
        rowsRef.current = loadedRows
        setRows(loadedRows)
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

  const loadPipeline = useCallback(async () => {
    const res = await fetchCaptureGroupsPipeline(projectId)
    if (res?.ok) {
      setPipelineData({ stats: res.stats, items: res.items })
    }
  }, [projectId])

  const handleBatchCleanInvalid = useCallback(async () => {
    if (isCleaningInvalid) return
    setIsCleaningInvalid(true)
    try {
      const res = await cleanInvalidCaptureGroups({ projectId })
      void message.success(`已清理 ${res.deletedCount} 条未检出视频`)
      await Promise.all([loadGroups(), loadTasks(), loadPipeline()])
    } catch (err) {
      void message.error(`清理失败: ${err instanceof Error ? err.message : String(err)}`)
    } finally {
      setIsCleaningInvalid(false)
    }
  }, [isCleaningInvalid, loadGroups, loadPipeline, loadTasks, message, projectId])

  const handleBatchRetryHands = useCallback(async () => {
    if (isRetryingHands) return
    setIsRetryingHands(true)
    try {
      const res = await retryFailedHandPoseBatch({ limit: 100, projectId })
      void message.success(`已触发 ${res.retriedCount} 条视频手部重新检测，后台正在并发分析中`)
      await loadPipeline()
    } catch (err) {
      void message.error(`批量重试失败: ${err instanceof Error ? err.message : String(err)}`)
    } finally {
      setIsRetryingHands(false)
    }
  }, [isRetryingHands, loadPipeline, message, projectId])

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
      void loadPipeline()
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
  }, [loadGroups, loadPipeline, loadTasks, projectId, restoredList])

  useEffect(() => {
    if (
      isTaskView ||
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
          await Promise.all([loadGroups(true), loadTasks(true), loadPipeline()])
        }
        if (!isDisposed) {
          scheduleNext()
        }
      }, CAPTURE_GROUP_LIST_POLL_INTERVAL_MS)
    }

    scheduleNext()

    const handleVisibilityChange = () => {
      if (!document.hidden) {
        void Promise.all([loadGroups(true), loadTasks(true), loadPipeline()])
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
  }, [currentPage, isTaskView, loadGroups, loadPipeline, loadState, loadTasks, searchQuery])

  const openCaptureGroup = useCallback(
    (targetGroupId: string) => {
      const captureGroupList: ListReturnState = {
        projectId,
        viewer,
        rows,
        scrollY: window.scrollY,
        currentPage: safeCurrentPage,
        pageSize,
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
    [location.pathname, location.search, navigate, pageSize, projectId, rows, safeCurrentPage, viewer],
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
                void loadPipeline()
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
              {
                label: rows.length ? `全部视频 (${rows.length})` : '全部视频',
                value: 'all',
              },
              {
                label:
                  pipelineData.stats?.ready !== undefined
                    ? `AI 已就绪 (${pipelineData.stats.ready})`
                    : 'AI 已就绪',
                value: 'ready',
              },
              {
                label:
                  pipelineData.stats?.processing !== undefined
                    ? `分析中 (${pipelineData.stats.processing})`
                    : '分析中',
                value: 'processing',
              },
              {
                label:
                  pipelineData.stats?.invalid !== undefined
                    ? `未检出手部 / 需关注 (${pipelineData.stats.invalid})`
                    : '未检出手部 / 需关注',
                value: 'invalid',
              },
              {
                label:
                  taskReadState.status === 'success'
                    ? `可导出资产 (${exportableRows.length})`
                    : '可导出资产',
                value: 'exportable',
              },
              ...(isTaskView
                ? [
                    {
                      label: `${
                        activeView === 'assigned'
                          ? '待标注'
                          : activeView === 'submitted'
                            ? '待复核'
                            : activeView === 'rework'
                              ? '待返工'
                              : activeView === 'completed'
                                ? '已完成'
                                : activeView === 'mine'
                                  ? '我的待办'
                                  : '全部任务'
                      } (历史视图)`,
                      value: activeView,
                    },
                  ]
                : []),
            ]}
          />
          {!isTaskView && rows.length ? (
            <span className="capture-group-list-count">
              {activeView === 'all'
                ? `共 ${rows.length} 条采集记录`
                : `共 ${filteredRows.length} 条 (全部 ${rows.length} 条)`}
            </span>
          ) : null}
        </div>
        {rows.length || activeView !== 'all' ? (
          <div className="capture-group-list-toolbar-actions">
            {activeView === 'invalid' && (pipelineData.stats?.invalid ?? 0) > 0 ? (
              <>
                <Button
                  type="primary"
                  icon={<RotateCw aria-hidden="true" size={14} strokeWidth={1.8} />}
                  loading={isRetryingHands}
                  onClick={() => void handleBatchRetryHands()}
                >
                  重新检测手部 ({pipelineData.stats?.invalid}条)
                </Button>
                <Popconfirm
                  title={`确定清理全部 ${pipelineData.stats?.invalid} 条未检出视频？`}
                  description="该操作将彻底级联删除未检出手部或需重新分析的视频数据，并释放关联的 AI、媒体与任务记录。无法撤销。"
                  okText="确认清理"
                  cancelText="取消"
                  okButtonProps={{ danger: true, loading: isCleaningInvalid }}
                  onConfirm={() => void handleBatchCleanInvalid()}
                >
                  <Button
                    danger
                    icon={<Trash2 aria-hidden="true" size={14} strokeWidth={1.8} />}
                    loading={isCleaningInvalid}
                  >
                    清理未检出视频
                  </Button>
                </Popconfirm>
              </>
            ) : null}
            <Input
              className="capture-group-list-search"
              placeholder={!isTaskView ? '搜索视频标识或时间…' : '搜索任务或采集记录…'}
              prefix={<Search aria-hidden="true" size={14} strokeWidth={1.8} className="capture-group-search-icon" />}
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
      {isTaskView ? (
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
                  <span>
                    {searchQuery
                      ? `未找到与 “${searchQuery}” 匹配的采集记录`
                      : activeView === 'invalid'
                        ? '暂无未检出手部或需关注的视频'
                        : activeView === 'exportable'
                          ? '暂无可导出资产（需完成人工质检与动作核准）'
                          : activeView === 'ready'
                            ? '暂无 AI 已就绪的视频'
                            : activeView === 'processing'
                              ? '当前没有正在分析中的视频'
                              : '暂无匹配的采集记录'}
                  </span>
                  {searchQuery ? (
                    <Button size="small" onClick={() => setSearchQuery('')}>
                      清除搜索
                    </Button>
                  ) : null}
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
                    const asset = parseAssetIdentity(group.capture_group_key, group.capture_group_id)
                    const identity = group.capture_group_key ?? shortId(group.capture_group_id)
                    const identityLabel = `采集记录 · ${asset.platform ? `${asset.platform} · ` : ''}${asset.shortCode}`
                    const eventTime = group.device_time_utc ?? group.created_at
                    const eventTimeLabel = group.device_time_utc ? '拍摄时间' : '记录时间'
                    const task = tasksByGroupId.get(group.capture_group_id)
                    const pipelineItem = pipelineData.items[group.capture_group_id]
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
                            {asset.platform ? (
                              <span className="export-key-tag">{asset.platform}</span>
                            ) : null}
                            <strong
                              className="capture-group-list-title"
                              title={`完整标识: ${asset.fullKey}\n完整 ID: ${group.capture_group_id}`}
                            >
                              {asset.shortCode}
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
                              <span
                                className={`capture-group-receipt-pill ${
                                  group.receipt_state !== 'COMPLETE' ? 'is-pending' : ''
                                }`}
                              >
                                {formatListReceipt(group)}
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
                          <div className="capture-group-list-main-status">
                            <dt className="visually-hidden">处理与质检状态</dt>
                            <dd>
                              {pipelineItem ? (
                                pipelineItem.pipeline_status === 'READY' ? (
                                  isHumanAnnotationExportReady(task) ? (
                                    <StatusIndicator tone="success">
                                      已完成 · 可导出
                                    </StatusIndicator>
                                  ) : (
                                    <>
                                      <StatusIndicator tone="success">
                                        AI 就绪
                                        {pipelineItem.action_candidate_count
                                          ? ` · ${pipelineItem.action_candidate_count}段动作`
                                          : ' · 双手已检出'}
                                      </StatusIndicator>
                                      {task ? (
                                        <span className="capture-group-task-stage-badge">
                                          {formatTaskStatus(task)}
                                        </span>
                                      ) : null}
                                    </>
                                  )
                                ) : pipelineItem.pipeline_status === 'PROCESSING' ? (
                                  <StatusIndicator tone="information" ariaLive="polite">
                                    AI 分析中…
                                  </StatusIndicator>
                                ) : pipelineItem.pipeline_status === 'NO_HAND' ? (
                                  <StatusIndicator tone="neutral">
                                    未检出手部
                                  </StatusIndicator>
                                ) : pipelineItem.pipeline_status === 'FAILED' ? (
                                  <StatusIndicator tone="warning">
                                    需重新分析
                                  </StatusIndicator>
                                ) : (
                                  <StatusIndicator tone="neutral">
                                    待 AI 分析
                                  </StatusIndicator>
                                )
                              ) : taskReadState.status === 'loading' ? (
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
                                <StatusIndicator
                                  tone={isHumanAnnotationExportReady(task) ? 'success' : 'information'}
                                >
                                  {isHumanAnnotationExportReady(task)
                                    ? '已完成 · 可导出'
                                    : formatTaskStatus(task)}
                                </StatusIndicator>

                              ) : (
                                <StatusIndicator tone="neutral">
                                  待处理
                                </StatusIndicator>
                              )}
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
                            <span>
                              {pipelineItem?.pipeline_status === 'READY' &&
                              !isHumanAnnotationExportReady(task)
                                ? '进入微调'
                                : '查看视频'}
                            </span>
                            <ArrowRight aria-hidden="true" size={14} strokeWidth={1.8} />
                          </Link>
                        </footer>
                      </div>
                    )
                  })}
                </div>
              )}
              {filteredRows.length > pageSize ? (
                <div className="capture-group-list-pagination">
                  <div className="capture-group-list-pagination-left" />
                  <Pagination
                    size="small"
                    current={safeCurrentPage}
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
