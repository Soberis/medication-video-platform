import { App as AntdApp, Button, InputNumber, Select, Table } from 'antd'
import { ArrowRight, RefreshCw, RotateCcw, SlidersHorizontal } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'

import {
  CatalogApiError,
  getDataManagementSummary,
  listVideoSummaries,
  type DataManagementSummary,
  type VideoSummary,
  type VideoSummaryAnnotationStatus,
  type VideoSummaryCleaningStatus,
  type VideoSummaryFilters,
  type VideoSummaryHandResult,
  type VideoSummaryHandStatus,
  type VideoSummaryMediaStatus,
  type VideoSummarySource,
} from '@/api/catalog'
import { paths } from '@/app/paths'
import { PageHeader } from '@/components/PageHeader'
import { PageToolbar } from '@/components/PageToolbar'
import { SummaryStrip } from '@/components/SummaryStrip'
import { StatusIndicator, type StatusIndicatorTone } from '@/components/status/StatusIndicator'
import { EmptyState, ErrorState, LoadingState, NoticeState } from '@/components/WorkspaceFeedback'
import { formatDateTime, shortId } from '@/features/catalog/catalog-presentation'
import {
  parseVideoFilters,
  replaceVideoFiltersInSearchParams,
} from '@/features/catalog/catalog-video-filters'

const mediaStatusLabels: Record<VideoSummaryMediaStatus, string> = {
  UPLOADED: '待媒体检测',
  INGESTING: '媒体检测中',
  INGESTED: '媒体检测完成',
  INGEST_FAILED: '媒体检测失败',
}

const handStatusLabels: Record<VideoSummaryHandStatus, string> = {
  NOT_REQUESTED: '未发起',
  QUEUED: '排队中',
  RUNNING: '检测中',
  SUCCEEDED: '检测完成',
  FAILED: '检测失败',
}

const handResultLabels: Record<VideoSummaryHandResult, string> = {
  UNKNOWN: '未判定',
  HAND_DETECTED: '发现手部',
  NO_HAND_DETECTED: '未发现手部',
}

const sourceLabels: Record<VideoSummarySource, string> = {
  HOSPITAL_REMOTE: '医院边端',
  PLATFORM_LOCAL: '平台本地上传',
}

const annotationStatusLabels: Record<VideoSummaryAnnotationStatus, string> = {
  NOT_STARTED: '待创建',
  ASSIGNED: '待标注',
  SUBMITTED: '待复核',
  COMPLETED: '标注完成',
}

const cleaningStatusLabels: Record<VideoSummaryCleaningStatus, string> = {
  NOT_STARTED: '待清洗',
  PASS: '已通过',
  WARNING: '需关注',
  REJECT: '未通过',
}

export default function VideoManagementView() {
  const { projectId = '' } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const { message } = AntdApp.useApp()
  const filters = useMemo(
    () => parseVideoFilters(new URLSearchParams(location.search)),
    [location.search],
  )
  const advancedFilterCount = [
    filters.source,
    filters.cameraNumber,
    filters.capturedFrom,
    filters.capturedBefore,
    filters.handStatus,
    filters.handResult,
  ].filter((value) => value !== undefined && value !== '').length
  const activeFilterCount =
    advancedFilterCount +
    [filters.mediaStatus, filters.annotationStatus, filters.cleaningStatus].filter(
      (value) => value !== undefined,
    ).length
  const [items, setItems] = useState<VideoSummary[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [summary, setSummary] = useState<DataManagementSummary | null>(null)
  const [summaryError, setSummaryError] = useState(false)
  const [isAdvancedFiltersOpen, setIsAdvancedFiltersOpen] = useState(advancedFilterCount > 0)
  const activeRequest = useRef<AbortController | null>(null)
  const itemsRef = useRef<VideoSummary[]>([])
  const pendingWorkCount = summary
    ? summary.pending_annotation + summary.pending_review + summary.pending_cleaning
    : 0

  const loadPage = useCallback(
    async (
      append = false,
      nextFilters: VideoSummaryFilters = {},
      cursor: string | null = null,
      background = false,
    ) => {
      activeRequest.current?.abort()
      const request = new AbortController()
      activeRequest.current = request
      if (!append && !background) {
        setLoadState('loading')
        setSummary(null)
        setSummaryError(false)
      }
      if (!background) setErrorMessage('')
      try {
        const [pageResult, summaryResult] = await Promise.allSettled([
          listVideoSummaries(projectId, nextFilters, append ? cursor : null, request.signal),
          getDataManagementSummary(projectId, nextFilters, request.signal),
        ])
        if (activeRequest.current !== request) return
        if (pageResult.status === 'rejected') {
          if (background) return
          throw pageResult.reason
        }
        if (summaryResult.status === 'fulfilled') {
          setSummary(summaryResult.value)
          setSummaryError(false)
        } else if (!background) {
          setSummary(null)
          setSummaryError(true)
        }
        const page = pageResult.value
        const nextItems = append ? [...itemsRef.current, ...page.items] : page.items
        if (background) notifyVideoTransitions(itemsRef.current, nextItems, message)
        itemsRef.current = nextItems
        setItems(nextItems)
        setNextCursor(page.next_cursor)
        setLoadState('success')
      } catch (error) {
        if (background || request.signal.aborted || activeRequest.current !== request) return
        setLoadState('error')
        setErrorMessage(
          error instanceof CatalogApiError ? error.message : '暂时无法读取视频列表，请稍后重试。',
        )
      } finally {
        if (activeRequest.current === request) activeRequest.current = null
      }
    },
    [message, projectId],
  )

  useEffect(() => {
    const timer = window.setTimeout(() => {
      itemsRef.current = []
      setItems([])
      setNextCursor(null)
      void loadPage(false, filters)
    }, 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
    }
  }, [filters, loadPage, projectId])

  const shouldPoll =
    nextCursor === null &&
    items.some(
      (row) =>
        row.media_status === 'UPLOADED' ||
        row.media_status === 'INGESTING' ||
        row.hand_status === 'QUEUED' ||
        row.hand_status === 'RUNNING',
    )

  useEffect(() => {
    if (!shouldPoll) return undefined
    let disposed = false
    let inFlight = false
    let timer: number | null = null

    const clearTimer = () => {
      if (timer !== null) {
        window.clearTimeout(timer)
        timer = null
      }
    }
    const schedule = () => {
      if (disposed || document.visibilityState !== 'visible' || timer !== null) return
      timer = window.setTimeout(() => {
        timer = null
        if (disposed || document.visibilityState !== 'visible' || inFlight) {
          schedule()
          return
        }
        inFlight = true
        void loadPage(false, filters, null, true).finally(() => {
          inFlight = false
          schedule()
        })
      }, 2_500)
    }
    const handleVisibilityChange = () => {
      clearTimer()
      if (document.visibilityState === 'visible') schedule()
    }

    document.addEventListener('visibilitychange', handleVisibilityChange)
    schedule()
    return () => {
      disposed = true
      clearTimer()
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [filters, loadPage, shouldPoll])

  function applyFilters(nextFilters: VideoSummaryFilters) {
    const nextSearchParams = replaceVideoFiltersInSearchParams(
      new URLSearchParams(location.search),
      nextFilters,
    )
    const nextSearch = nextSearchParams.toString()
    const currentSearch = location.search.startsWith('?')
      ? location.search.slice(1)
      : location.search
    if (nextSearch === currentSearch) return
    void navigate({
      pathname: location.pathname,
      search: nextSearch ? `?${nextSearch}` : '',
      hash: location.hash,
    })
  }

  return (
    <>
      <PageHeader
        className="video-management-heading"
        eyebrow={projectId ? `当前项目 · ${shortId(projectId)}` : '当前项目'}
        titleId="video-management-title"
        title="视频管理"
        description="按状态、来源和拍摄时间筛选项目视频，查看处理进度并进入下一步工作。"
        actions={
          <Button
            size="small"
            icon={<RefreshCw aria-hidden="true" size={14} strokeWidth={1.8} />}
            loading={loadState === 'loading'}
            onClick={() => void loadPage(false, filters)}
          >
            刷新
          </Button>
        }
      />
      {summary ? (
        <SummaryStrip
          ariaLabel="项目视频摘要"
          items={[
            { label: '视频总数', value: summary.total_videos },
            {
              label: '待处理',
              value: pendingWorkCount,
              detail: `标注 ${summary.pending_annotation} · 复核 ${summary.pending_review} · 清洗 ${summary.pending_cleaning}`,
            },
            { label: '可用数据', value: summary.usable_count },
          ]}
          footer={
            <>
              <span>总时长 {formatDuration(summary.total_duration)}</span>
              <span>存储 {formatBytes(summary.storage_bytes)}</span>
            </>
          }
        />
      ) : null}
      {summaryError ? <NoticeState>视频摘要暂不可用，视频列表仍可使用。</NoticeState> : null}
      <section className="video-filter-toolbar" aria-labelledby="video-management-filters-title">
        <h2 id="video-management-filters-title" className="visually-hidden">
          筛选视频
        </h2>
        <PageToolbar ariaLabel="视频筛选和操作" className="video-filter-toolbar-main">
          <div className="video-filter-primary">
            <label className="video-filter-control">
              <span>媒体状态</span>
              <Select
                aria-label="媒体状态"
                allowClear
                size="small"
                value={filters.mediaStatus}
                placeholder="全部"
                options={Object.entries(mediaStatusLabels).map(([value, label]) => ({
                  value,
                  label,
                }))}
                onChange={(value) =>
                  applyFilters({
                    ...filters,
                    mediaStatus: value as VideoSummaryMediaStatus | undefined,
                  })
                }
              />
            </label>
            <label className="video-filter-control">
              <span>标注</span>
              <Select
                aria-label="配药动作标注状态"
                allowClear
                size="small"
                value={filters.annotationStatus}
                placeholder="全部"
                options={Object.entries(annotationStatusLabels).map(([value, label]) => ({
                  value,
                  label,
                }))}
                onChange={(value) =>
                  applyFilters({
                    ...filters,
                    annotationStatus: value as VideoSummaryAnnotationStatus | undefined,
                  })
                }
              />
            </label>
            <label className="video-filter-control">
              <span>清洗</span>
              <Select
                aria-label="清洗状态"
                allowClear
                size="small"
                value={filters.cleaningStatus}
                placeholder="全部"
                options={Object.entries(cleaningStatusLabels).map(([value, label]) => ({
                  value,
                  label,
                }))}
                onChange={(value) =>
                  applyFilters({
                    ...filters,
                    cleaningStatus: value as VideoSummaryCleaningStatus | undefined,
                  })
                }
              />
            </label>
          </div>
          <div className="video-filter-actions">
            <Button
              type="text"
              size="small"
              aria-expanded={isAdvancedFiltersOpen}
              aria-controls="video-secondary-filters"
              icon={<SlidersHorizontal aria-hidden="true" size={14} strokeWidth={1.8} />}
              onClick={() => setIsAdvancedFiltersOpen((open) => !open)}
            >
              更多筛选
              {advancedFilterCount > 0 ? (
                <span className="video-filter-count" aria-label={`${advancedFilterCount} 项已应用`}>
                  {advancedFilterCount}
                </span>
              ) : null}
            </Button>
            {activeFilterCount > 0 ? (
              <Button
                type="text"
                size="small"
                icon={<RotateCcw aria-hidden="true" size={14} strokeWidth={1.8} />}
                onClick={() => applyFilters({})}
              >
                清除筛选
              </Button>
            ) : null}
          </div>
        </PageToolbar>
        {isAdvancedFiltersOpen ? (
          <div id="video-secondary-filters" className="video-filter-secondary">
            <label className="video-filter-control">
              <span>来源</span>
              <Select
                aria-label="来源"
                allowClear
                size="small"
                value={filters.source}
                placeholder="全部"
                options={Object.entries(sourceLabels).map(([value, label]) => ({ value, label }))}
                onChange={(value) =>
                  applyFilters({ ...filters, source: value as VideoSummarySource | undefined })
                }
              />
            </label>
            <label className="video-filter-control">
              <span>手部候选</span>
              <Select
                aria-label="手部候选状态"
                allowClear
                size="small"
                value={filters.handStatus}
                placeholder="全部"
                options={Object.entries(handStatusLabels).map(([value, label]) => ({
                  value,
                  label,
                }))}
                onChange={(value) =>
                  applyFilters({
                    ...filters,
                    handStatus: value as VideoSummaryHandStatus | undefined,
                  })
                }
              />
            </label>
            <label className="video-filter-control">
              <span>相机编号</span>
              <InputNumber
                aria-label="相机编号"
                size="small"
                min={1}
                value={filters.cameraNumber}
                placeholder="全部"
                onChange={(value) =>
                  applyFilters({ ...filters, cameraNumber: value === null ? undefined : value })
                }
              />
            </label>
            <label className="video-filter-control">
              <span>拍摄开始</span>
              <input
                aria-label="拍摄开始"
                type="datetime-local"
                value={toLocalDateTimeInput(filters.capturedFrom)}
                onChange={(event) =>
                  applyFilters({ ...filters, capturedFrom: toInstant(event.currentTarget.value) })
                }
              />
            </label>
            <label className="video-filter-control">
              <span>拍摄截止</span>
              <input
                aria-label="拍摄截止"
                type="datetime-local"
                value={toLocalDateTimeInput(filters.capturedBefore)}
                onChange={(event) =>
                  applyFilters({ ...filters, capturedBefore: toInstant(event.currentTarget.value) })
                }
              />
            </label>
            <label className="video-filter-control">
              <span>手部结果</span>
              <Select
                aria-label="手部结果"
                allowClear
                size="small"
                value={filters.handResult}
                placeholder="全部"
                options={Object.entries(handResultLabels).map(([value, label]) => ({
                  value,
                  label,
                }))}
                onChange={(value) =>
                  applyFilters({
                    ...filters,
                    handResult: value as VideoSummaryHandResult | undefined,
                  })
                }
              />
            </label>
          </div>
        ) : null}
      </section>
      {loadState === 'loading' ? (
        <LoadingState className="video-list-state">正在加载视频列表…</LoadingState>
      ) : null}
      {loadState === 'error' ? (
        <ErrorState onRetry={() => void loadPage(false, filters)}>{errorMessage}</ErrorState>
      ) : null}
      {loadState === 'success' && items.length === 0 ? (
        <section className="video-list-state video-empty-state" aria-label="视频列表为空">
          <EmptyState
            description={
              activeFilterCount > 0 ? '没有符合当前筛选条件的视频' : '当前项目还没有视频'
            }
          >
            {activeFilterCount > 0 ? (
              <Button type="link" size="small" onClick={() => applyFilters({})}>
                清除筛选
              </Button>
            ) : null}
          </EmptyState>
        </section>
      ) : null}
      {items.length > 0 ? (
        <section className="video-list-surface" aria-label="视频列表">
          <Table<VideoSummary>
            className="video-management-table"
            dataSource={items}
            rowKey="video_id"
            pagination={false}
            size="small"
            tableLayout="fixed"
            columns={[
              {
                title: '视频',
                width: 320,
                render: (_, row) => (
                  <div className="video-identity-cell">
                    <Link
                      className="video-name-link"
                      aria-label={`视频 ${row.display_name}，视频资产 ID ${row.video_id}`}
                      title={row.display_name}
                      to={{
                        pathname: paths.asset(projectId, row.video_id),
                        search: location.search,
                      }}
                    >
                      {row.display_name}
                    </Link>
                    <span className="video-metadata-line">
                      <span>
                        {sourceLabels[row.source_name as VideoSummarySource] ?? '其他受控来源'}
                      </span>
                      <span>{row.camera_number ? `相机 ${row.camera_number}` : '未绑定相机'}</span>
                      <span className="video-metadata-time">
                        {row.captured_at
                          ? `拍摄 ${formatDateTime(row.captured_at)}`
                          : `接收 ${formatDateTime(row.received_at)}`}
                      </span>
                    </span>
                    <span className="video-identity" title={`唯一视频资产 ID：${row.video_id}`}>
                      视频 ID {shortId(row.video_id)}
                    </span>
                  </div>
                ),
              },
              {
                title: '媒体状态',
                className: 'video-table-status-column',
                align: 'center',
                width: 128,
                render: (_, row) => (
                  <StatusIndicator tone={mediaStatusTone(row.media_status)}>
                    {mediaStatusLabels[row.media_status]}
                  </StatusIndicator>
                ),
              },
              {
                title: '标注',
                dataIndex: 'annotation_status',
                className: 'video-table-status-column',
                align: 'center',
                width: 104,
                render: (value: VideoSummaryAnnotationStatus) => (
                  <StatusIndicator tone={annotationStatusTone(value)}>
                    {annotationStatusLabels[value]}
                  </StatusIndicator>
                ),
              },
              {
                title: '清洗',
                dataIndex: 'cleaning_status',
                className: 'video-table-status-column',
                align: 'center',
                width: 96,
                render: (value: VideoSummaryCleaningStatus) => (
                  <StatusIndicator tone={cleaningStatusTone(value)}>
                    {cleaningStatusLabels[value]}
                  </StatusIndicator>
                ),
              },
              {
                title: '下一步',
                width: 220,
                render: (_, row) => {
                  const action = nextStepAction(row)
                  return (
                    <div className="video-next-step">
                      <strong>{nextStepLabel(row)}</strong>
                      {action ? (
                        <Link
                          className="video-next-step-action"
                          to={
                            action.target === 'detail'
                              ? {
                                  pathname: paths.asset(projectId, row.video_id),
                                  search: location.search,
                                }
                              : paths.annotationTasks(projectId)
                          }
                        >
                          {action.label}
                          <ArrowRight aria-hidden="true" size={13} strokeWidth={1.8} />
                        </Link>
                      ) : null}
                    </div>
                  )
                },
              },
            ]}
          />
          {nextCursor ? (
            <div className="catalog-pagination">
              <Button
                loading={loadState === 'loading'}
                size="small"
                onClick={() => void loadPage(true, filters, nextCursor)}
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

function toInstant(value: string) {
  return value ? new Date(value).toISOString() : undefined
}

function toLocalDateTimeInput(value?: string) {
  if (!value) return ''
  const date = new Date(value)
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

function mediaStatusTone(status: VideoSummaryMediaStatus): StatusIndicatorTone {
  if (status === 'INGEST_FAILED') return 'destructive'
  if (status === 'INGESTED') return 'success'
  return 'information'
}

function annotationStatusTone(status: VideoSummaryAnnotationStatus): StatusIndicatorTone {
  if (status === 'COMPLETED') return 'success'
  if (status === 'SUBMITTED' || status === 'ASSIGNED') return 'information'
  return 'neutral'
}

function cleaningStatusTone(status: VideoSummaryCleaningStatus): StatusIndicatorTone {
  if (status === 'PASS') return 'success'
  if (status === 'WARNING') return 'warning'
  if (status === 'REJECT') return 'destructive'
  return 'neutral'
}

function nextStepLabel(row: VideoSummary) {
  if (row.media_status !== 'INGESTED') {
    return row.media_status === 'INGEST_FAILED' ? '媒体检测失败' : '媒体检测中'
  }
  if (row.quality_decision === 'UNREVIEWED') return '待人工质量判断'
  if (row.quality_decision === 'NEEDS_REVIEW') return '需要重新判断质量'
  if (row.quality_decision === 'REJECTED') return '质量判断未通过'
  if (row.quality_decision !== 'QUALIFIED') return '待人工质量判断'
  if (row.annotation_status === 'SUBMITTED') return '待人工复核'
  if (row.annotation_status === 'ASSIGNED') return '继续人工标注'
  if (row.annotation_status === 'NOT_STARTED') return '待系统分配标注任务'
  if (row.cleaning_status === 'NOT_STARTED') return '待清洗'
  if (row.cleaning_status === 'PASS') return '当前数据已通过清洗，可用于训练'
  if (row.cleaning_status === 'WARNING' || row.cleaning_status === 'REJECT')
    return '需要重新核对清洗'
  return '查看处理结果'
}

function nextStepAction(row: VideoSummary) {
  if (row.media_status === 'INGEST_FAILED')
    return { label: '查看失败原因', target: 'detail' as const }
  if (row.media_status !== 'INGESTED') return null
  if (row.quality_decision === 'UNREVIEWED' || row.quality_decision === 'NEEDS_REVIEW') {
    return { label: '人工质量判断', target: 'detail' as const }
  }
  if (row.quality_decision === 'REJECTED') {
    return { label: '查看质量结论', target: 'detail' as const }
  }
  if (row.quality_decision !== 'QUALIFIED') return null
  if (row.annotation_status === 'SUBMITTED') {
    return { label: '人工复核', target: 'annotation' as const }
  }
  if (row.annotation_status === 'ASSIGNED') {
    return { label: '继续人工标注', target: 'annotation' as const }
  }
  if (row.annotation_status === 'NOT_STARTED') return null
  if (row.cleaning_status === 'WARNING' || row.cleaning_status === 'REJECT') {
    return { label: '重新核对清洗', target: 'annotation' as const }
  }
  return null
}

function notifyVideoTransitions(
  previousItems: VideoSummary[],
  nextItems: VideoSummary[],
  message: ReturnType<typeof AntdApp.useApp>['message'],
) {
  const previousById = new Map(previousItems.map((item) => [item.video_id, item]))
  for (const nextItem of nextItems) {
    const previousItem = previousById.get(nextItem.video_id)
    if (!previousItem) continue
    if (
      (previousItem.media_status === 'UPLOADED' || previousItem.media_status === 'INGESTING') &&
      nextItem.media_status === 'INGESTED'
    ) {
      void message.success('媒体检测完成')
    }
    if (
      (previousItem.hand_status === 'QUEUED' || previousItem.hand_status === 'RUNNING') &&
      nextItem.hand_status === 'SUCCEEDED'
    ) {
      void message.success('手部检测完成')
    }
  }
}

function formatBytes(bytes: number) {
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`
}

function formatDuration(microseconds: number) {
  const totalSeconds = Math.max(0, Math.round(microseconds / 1_000_000))
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  return hours > 0 ? `${hours}时${minutes}分` : `${minutes}分${seconds}秒`
}
