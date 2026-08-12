import { Button, Card, Empty, InputNumber, Select, Table, Tag } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'

import {
  CatalogApiError,
  getDataManagementSummary,
  listVideoSummaries,
  requestHandPoseCandidates,
  retryHandPoseCandidates,
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
import { formatDateTime, statusTone } from '@/catalog-presentation'
import {
  parseVideoFilters,
  replaceVideoFiltersInSearchParams,
} from '@/catalog-video-filters'

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
  const filters = useMemo(
    () => parseVideoFilters(new URLSearchParams(location.search)),
    [location.search],
  )
  const [items, setItems] = useState<VideoSummary[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [summary, setSummary] = useState<DataManagementSummary | null>(null)
  const [summaryError, setSummaryError] = useState(false)
  const [actionVideoId, setActionVideoId] = useState<string | null>(null)
  const [actionMessage, setActionMessage] = useState('')
  const activeRequest = useRef<AbortController | null>(null)

  const loadPage = useCallback(
    async (
      append = false,
      nextFilters: VideoSummaryFilters = {},
      cursor: string | null = null,
    ) => {
      activeRequest.current?.abort()
      const request = new AbortController()
      activeRequest.current = request
      if (!append) {
        setLoadState('loading')
        setSummary(null)
        setSummaryError(false)
      }
      setErrorMessage('')
      try {
        const [pageResult, summaryResult] = await Promise.allSettled([
          listVideoSummaries(projectId, nextFilters, append ? cursor : null, request.signal),
          getDataManagementSummary(projectId, nextFilters, request.signal),
        ])
        if (activeRequest.current !== request) return
        if (pageResult.status === 'rejected') throw pageResult.reason
        if (summaryResult.status === 'fulfilled') {
          setSummary(summaryResult.value)
          setSummaryError(false)
        } else {
          setSummary(null)
          setSummaryError(true)
        }
        const page = pageResult.value
        setItems((current) => (append ? [...current, ...page.items] : page.items))
        setNextCursor(page.next_cursor)
        setLoadState('success')
      } catch (error) {
        if (request.signal.aborted || activeRequest.current !== request) return
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
    [projectId],
  )

  useEffect(() => {
    setItems([])
    setNextCursor(null)
    const timer = window.setTimeout(() => void loadPage(false, filters), 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
    }
  }, [filters, loadPage, projectId])

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

  async function startHandPose(row: VideoSummary) {
    if (
      actionVideoId !== null ||
      row.media_status !== 'INGESTED' ||
      row.hand_status === 'QUEUED' ||
      row.hand_status === 'RUNNING' ||
      row.hand_status === 'SUCCEEDED' ||
      (row.hand_status === 'FAILED' && !row.hand_retryable)
    )
      return
    setActionVideoId(row.video_id)
    setActionMessage('')
    try {
      const job =
        row.hand_status === 'FAILED' && row.hand_retryable
          ? await retryHandPoseCandidates(projectId, row.video_id)
          : await requestHandPoseCandidates(projectId, row.video_id)
      setItems((current) =>
        current.map((item) =>
          item.video_id === row.video_id
            ? {
                ...item,
                hand_status: job.status,
                hand_result: job.hand_result,
                hand_retryable: false,
              }
            : item,
        ),
      )
      setActionMessage(row.hand_status === 'FAILED' ? '已重新排队手部检测。' : '已排队手部检测。')
    } catch (error) {
      setActionMessage(
        error instanceof CatalogApiError ? error.message : '手部检测请求失败，请稍后重试。',
      )
    } finally {
      setActionVideoId(null)
    }
  }

  return (
    <>
      <section className="page-heading" aria-labelledby="video-management-title">
        <div>
          <p className="app-eyebrow">V1.18 P0 · 单视频闭环</p>
          <h1 id="video-management-title">视频管理</h1>
          <p>从媒体入库到手部候选和人工配药动作标注，按单个固定机位视频查看当前状态。</p>
        </div>
        <Button loading={loadState === 'loading'} onClick={() => void loadPage(false, filters)}>
          刷新
        </Button>
      </section>
      {summary ? (
        <Card className="summary-card" variant="outlined" aria-label="数据管理汇总">
          <div className="summary-grid">
            <div>
              <span className="cell-secondary">视频总数</span>
              <strong>{summary.total_videos}</strong>
            </div>
            <div>
              <span className="cell-secondary">总时长</span>
              <strong>{formatDuration(summary.total_duration)}</strong>
            </div>
            <div>
              <span className="cell-secondary">存储量</span>
              <strong>{formatBytes(summary.storage_bytes)}</strong>
            </div>
            <div>
              <span className="cell-secondary">可用数据</span>
              <strong>{summary.usable_count}</strong>
            </div>
            <div>
              <span className="cell-secondary">来源分布</span>
              <strong>{formatCountMap(summary.counts_by_source, sourceCountLabel)}</strong>
            </div>
            <div>
              <span className="cell-secondary">相机分布</span>
              <strong>{formatCountMap(summary.counts_by_camera, cameraCountLabel)}</strong>
            </div>
            <div>
              <span className="cell-secondary">媒体状态分布</span>
              <strong>{formatCountMap(summary.counts_by_status, mediaCountLabel)}</strong>
            </div>
            <div>
              <span className="cell-secondary">待配药动作标注 / 待复核 / 待清洗</span>
              <strong>
                {summary.pending_annotation} / {summary.pending_review} / {summary.pending_cleaning}
              </strong>
            </div>
          </div>
        </Card>
      ) : null}
      {summaryError ? (
        <div className="catalog-notice" role="status">
          数据管理汇总暂不可用，视频列表仍可使用。
        </div>
      ) : null}
      <Card className="filter-card" variant="outlined">
        <div className="catalog-filters video-management-filters">
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
            手部状态
            <Select
              allowClear
              value={filters.handStatus}
              placeholder="全部"
              options={Object.entries(handStatusLabels).map(([value, label]) => ({ value, label }))}
              onChange={(value) =>
                applyFilters({
                  ...filters,
                  handStatus: value as VideoSummaryHandStatus | undefined,
                })
              }
            />
          </label>
          <label>
            手部结果
            <Select
              allowClear
              value={filters.handResult}
              placeholder="全部"
              options={Object.entries(handResultLabels).map(([value, label]) => ({ value, label }))}
              onChange={(value) =>
                applyFilters({
                  ...filters,
                  handResult: value as VideoSummaryHandResult | undefined,
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
          <Button onClick={() => applyFilters({})}>清除筛选</Button>
        </div>
      </Card>
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
      {actionMessage ? (
        <div className="catalog-notice" role="status">
          {actionMessage}
        </div>
      ) : null}
      {loadState === 'success' && items.length === 0 ? (
        <Empty description="当前没有符合条件的视频。" />
      ) : null}
      {items.length > 0 ? (
        <Card className="table-card" variant="outlined">
          <Table<VideoSummary>
            dataSource={items}
            rowKey="video_id"
            pagination={false}
            scroll={{ x: 1250 }}
            columns={[
              {
                title: '视频',
                width: 220,
                render: (_, row) => (
                  <>
                    <strong className="mono-wrap">{row.display_name}</strong>
                    <span className="cell-secondary">
                      来源 {sourceLabels[row.source_name as VideoSummarySource] ?? '其他受控来源'}
                    </span>
                  </>
                ),
              },
              {
                title: '相机',
                dataIndex: 'camera_number',
                width: 80,
                render: (value) => value ?? '—',
              },
              {
                title: '拍摄 / 接收时间',
                width: 210,
                render: (_, row) => (
                  <>
                    <span>{formatDateTime(row.captured_at)}</span>
                    <span className="cell-secondary">接收 {formatDateTime(row.received_at)}</span>
                  </>
                ),
              },
              {
                title: '媒体',
                width: 110,
                render: (_, row) => (
                  <Tag color={statusTone(row.media_status)}>
                    {mediaStatusLabels[row.media_status]}
                  </Tag>
                ),
              },
              {
                title: '手部检测',
                width: 150,
                render: (_, row) => (
                  <>
                    <Tag color={statusTone(row.hand_status)}>
                      {handStatusLabels[row.hand_status]}
                    </Tag>
                    <span className="cell-secondary">{handResultLabels[row.hand_result]}</span>
                  </>
                ),
              },
              {
                title: '配药动作标注',
                dataIndex: 'annotation_status',
                width: 150,
                render: (value: VideoSummaryAnnotationStatus) => annotationStatusLabels[value],
              },
              {
                title: '清洗',
                dataIndex: 'cleaning_status',
                width: 110,
                render: (value: VideoSummaryCleaningStatus) => cleaningStatusLabels[value],
              },
              {
                title: '留存',
                dataIndex: 'retention_status',
                width: 170,
                render: (value: string) => retentionStatusLabel(value),
              },
              {
                title: '操作',
                width: 220,
                render: (_, row) => (
                  <span className="table-actions">
                    <Link
                      className="text-link"
                      to={{
                        pathname: paths.asset(projectId, row.video_id),
                        search: location.search,
                      }}
                    >
                      {row.hand_status === 'SUCCEEDED' ? '播放 / 查看结果' : '播放'}
                    </Link>
                    {row.hand_status === 'QUEUED' || row.hand_status === 'RUNNING' ? (
                      <Button size="small" disabled>
                        检测中
                      </Button>
                    ) : row.hand_status === 'FAILED' &&
                      row.hand_retryable &&
                      row.media_status === 'INGESTED' ? (
                      <Button
                        size="small"
                        loading={actionVideoId === row.video_id}
                        onClick={() => void startHandPose(row)}
                      >
                        重试检测
                      </Button>
                    ) : row.hand_status === 'FAILED' ? (
                      <span className="cell-secondary">不可重试</span>
                    ) : row.hand_status !== 'SUCCEEDED' && row.media_status === 'INGESTED' ? (
                      <Button
                        size="small"
                        loading={actionVideoId === row.video_id}
                        onClick={() => void startHandPose(row)}
                      >
                        检测手部
                      </Button>
                    ) : row.hand_status !== 'SUCCEEDED' ? (
                      <span className="cell-secondary">媒体尚未入库，不能检测</span>
                    ) : null}
                    <Link className="text-link" to={paths.annotationTasks(projectId)}>
                      进入配药动作标注
                    </Link>
                  </span>
                ),
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
        </Card>
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

function retentionStatusLabel(value: string) {
  const labels: Record<string, string> = {
    NOT_ASSESSED: '未评估',
    RELEASE_REVIEW_REQUIRED: '待放行复核',
    QUARANTINE_REQUIRED: '需隔离',
    DEIDENTIFICATION_REQUIRED: '需去标识化',
  }
  return labels[value] ?? '需人工确认'
}

function formatCountMap(values: Record<string, number>, label: (key: string) => string) {
  const entries = Object.entries(values)
  if (entries.length === 0) return '暂无'
  return entries.map(([key, count]) => `${label(key)} ${count}`).join(' · ')
}

function sourceCountLabel(value: string) {
  return sourceLabels[value as VideoSummarySource] ?? '其他来源'
}

function cameraCountLabel(value: string) {
  return /^\d+$/.test(value) ? `相机 ${value}` : '未绑定相机'
}

function mediaCountLabel(value: string) {
  return mediaStatusLabels[value as VideoSummaryMediaStatus] ?? '其他状态'
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
