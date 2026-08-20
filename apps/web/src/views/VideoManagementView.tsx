import { App as AntdApp, Button, Empty, InputNumber, Select, Table } from 'antd'
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
import { StatusBadge } from '@/components/StatusBadge'
import { SummaryStrip } from '@/components/SummaryStrip'
import { formatDateTime, shortId, statusTone } from '@/features/catalog/catalog-presentation'
import {
  parseVideoFilters,
  replaceVideoFiltersInSearchParams,
} from '@/features/catalog/catalog-video-filters'

const mediaStatusLabels: Record<VideoSummaryMediaStatus, string> = {
  UPLOADED: '待处理',
  INGESTING: '处理中',
  INGESTED: '可检测',
  INGEST_FAILED: '媒体失败',
}

const handStatusLabels: Record<VideoSummaryHandStatus, string> = {
  NOT_REQUESTED: '未检测',
  QUEUED: '排队中',
  RUNNING: '检测中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
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
  COMPLETED: '已完成',
}

const cleaningStatusLabels: Record<VideoSummaryCleaningStatus, string> = {
  NOT_STARTED: '待清洗',
  PASS: '通过',
  WARNING: '需关注',
  REJECT: '不通过',
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
          error instanceof CatalogApiError
            ? error.message
            : '暂时无法读取视频管理列表，请稍后重试。',
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
        eyebrow={projectId ? `当前项目 · ${shortId(projectId)}` : '当前项目'}
        titleId="video-management-title"
        title="视频管理"
        description="根据当前状态处理视频，并推进人工流程。"
        actions={
          <Button loading={loadState === 'loading'} onClick={() => void loadPage(false, filters)}>
            刷新
          </Button>
        }
      />
      {summary ? (
        <SummaryStrip
          ariaLabel="数据管理汇总"
          items={[
            { label: '视频', value: summary.total_videos },
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
      {summaryError ? (
        <div className="catalog-notice" role="status">
          数据管理汇总暂不可用，视频列表仍可使用。
        </div>
      ) : null}
      <section
        className="video-management-filter-panel working-surface"
        aria-labelledby="video-management-filters-title"
      >
        <div className="filter-card-heading">
          <div>
            <h2 id="video-management-filters-title">状态筛选</h2>
            <span>统计和列表使用相同条件</span>
          </div>
          <Button type="link" onClick={() => applyFilters({})}>
            清除筛选
          </Button>
        </div>
        <div className="catalog-filters video-management-filters">
          <label>
            媒体状态
            <Select
              allowClear
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
          <label>
            配药动作标注
            <Select
              allowClear
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
          <label>
            清洗结论
            <Select
              allowClear
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
        <details
          className="video-management-advanced-filters"
          open={isAdvancedFiltersOpen}
          onToggle={(event) => setIsAdvancedFiltersOpen(event.currentTarget.open)}
        >
          <summary>
            <strong>更多筛选</strong>
            <span>
              {advancedFilterCount > 0 ? `${advancedFilterCount} 项已应用` : '来源、相机和拍摄时间'}
            </span>
          </summary>
          <div className="catalog-filters video-management-filters video-management-secondary-filters">
            <label>
              来源
              <Select
                allowClear
                value={filters.source}
                placeholder="全部"
                options={Object.entries(sourceLabels).map(([value, label]) => ({ value, label }))}
                onChange={(value) =>
                  applyFilters({ ...filters, source: value as VideoSummarySource | undefined })
                }
              />
            </label>
            <label>
              手部候选状态
              <Select
                allowClear
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
            <label>
              相机编号
              <InputNumber
                min={1}
                value={filters.cameraNumber}
                placeholder="全部"
                onChange={(value) =>
                  applyFilters({ ...filters, cameraNumber: value === null ? undefined : value })
                }
              />
            </label>
            <label>
              拍摄开始
              <input
                type="datetime-local"
                value={toLocalDateTimeInput(filters.capturedFrom)}
                onChange={(event) =>
                  applyFilters({ ...filters, capturedFrom: toInstant(event.currentTarget.value) })
                }
              />
            </label>
            <label>
              拍摄截止
              <input
                type="datetime-local"
                value={toLocalDateTimeInput(filters.capturedBefore)}
                onChange={(event) =>
                  applyFilters({ ...filters, capturedBefore: toInstant(event.currentTarget.value) })
                }
              />
            </label>
            <label>
              手部结果
              <Select
                allowClear
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
        </details>
      </section>
      {loadState === 'loading' ? (
        <div className="catalog-notice" role="status">
          正在读取视频管理列表…
        </div>
      ) : null}
      {loadState === 'error' ? (
        <div className="catalog-error" role="alert">
          <span>{errorMessage}</span>
          <Button onClick={() => void loadPage(false, filters)}>重试</Button>
        </div>
      ) : null}
      {loadState === 'success' && items.length === 0 ? (
        <Empty description="当前没有符合条件的视频。" />
      ) : null}
      {items.length > 0 ? (
        <section
          className="table-card video-management-table-card working-surface"
          aria-label="视频列表"
        >
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
                width: 260,
                render: (_, row) => (
                  <>
                    <div className="video-name-line">
                      <strong className="mono-wrap">
                        <Link
                          className="video-name-link"
                          aria-label={`视频 ${row.display_name}，资产 ID ${row.video_id}`}
                          title={`唯一视频资产 ID：${row.video_id}`}
                          to={{
                            pathname: paths.asset(projectId, row.video_id),
                            search: location.search,
                          }}
                        >
                          {row.display_name}
                        </Link>
                      </strong>
                      <span className="video-identity" title={`唯一视频资产 ID：${row.video_id}`}>
                        ID {shortId(row.video_id)}
                      </span>
                    </div>
                    <span className="cell-secondary">
                      来源 {sourceLabels[row.source_name as VideoSummarySource] ?? '其他受控来源'}
                      {' · '}
                      {row.camera_number ? `相机 ${row.camera_number}` : '未绑定相机'}
                      {' · '}
                      {row.captured_at
                        ? `拍摄 ${formatDateTime(row.captured_at)}`
                        : `接收 ${formatDateTime(row.received_at)}`}
                    </span>
                  </>
                ),
              },
              {
                title: '媒体',
                width: 100,
                render: (_, row) => (
                  <StatusBadge tone={statusTone(row.media_status)}>
                    {mediaStatusLabels[row.media_status]}
                  </StatusBadge>
                ),
              },
              {
                title: '配药动作标注',
                dataIndex: 'annotation_status',
                width: 120,
                render: (value: VideoSummaryAnnotationStatus) => (
                  <StatusBadge tone={annotationStatusTone(value)}>
                    {annotationStatusLabels[value]}
                  </StatusBadge>
                ),
              },
              {
                title: '清洗',
                dataIndex: 'cleaning_status',
                width: 100,
                render: (value: VideoSummaryCleaningStatus) => (
                  <StatusBadge tone={statusTone(value)}>{cleaningStatusLabels[value]}</StatusBadge>
                ),
              },
              {
                title: '下一步',
                width: 180,
                render: (_, row) => {
                  const action = nextStepAction(row)
                  return (
                    <div className="video-next-step">
                      <strong>{nextStepLabel(row)}</strong>
                      {action ? (
                        <Link
                          className="text-link"
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

function annotationStatusTone(status: VideoSummaryAnnotationStatus) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'SUBMITTED') return 'processing'
  return status === 'ASSIGNED' ? 'warning' : 'default'
}

function nextStepLabel(row: VideoSummary) {
  if (row.media_status !== 'INGESTED') {
    return row.media_status === 'INGEST_FAILED' ? '视频检测失败' : '视频检测中'
  }
  if (row.quality_decision === 'UNREVIEWED') return '待人工质量判断'
  if (row.quality_decision === 'NEEDS_REVIEW') return '需重新人工质量判断'
  if (row.quality_decision === 'REJECTED') return '质量判断未通过'
  if (row.quality_decision !== 'QUALIFIED') return '待人工质量判断'
  if (row.annotation_status === 'SUBMITTED') return '等待人工复核'
  if (row.annotation_status === 'ASSIGNED') return '继续人工标注'
  if (row.annotation_status === 'NOT_STARTED') return '待进入人工标注'
  if (row.cleaning_status === 'NOT_STARTED') return '等待数据清洗'
  if (row.cleaning_status === 'PASS') return '已通过清洗，可参与训练数据导出'
  if (row.cleaning_status === 'WARNING' || row.cleaning_status === 'REJECT')
    return '需要重新核对清洗'
  return '查看处理结果'
}

function nextStepAction(row: VideoSummary) {
  if (row.media_status === 'INGEST_FAILED')
    return { label: '查看失败原因', target: 'detail' as const }
  if (row.media_status !== 'INGESTED') return null
  if (row.quality_decision === 'UNREVIEWED' || row.quality_decision === 'NEEDS_REVIEW') {
    return { label: '下一步：人工质量判断', target: 'detail' as const }
  }
  if (row.quality_decision === 'REJECTED') {
    return { label: '查看质量结论', target: 'detail' as const }
  }
  if (row.quality_decision !== 'QUALIFIED') return null
  if (row.annotation_status === 'SUBMITTED') {
    return { label: '下一步：人工复核', target: 'annotation' as const }
  }
  if (row.annotation_status === 'ASSIGNED') {
    return { label: '继续人工标注', target: 'annotation' as const }
  }
  if (row.annotation_status === 'NOT_STARTED') {
    return { label: '下一步：进入人工标注', target: 'detail' as const }
  }
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
      void message.success('视频检测完成')
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
