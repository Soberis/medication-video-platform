import { Button, Card, Empty, Select, Table, Tag } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import {
  CatalogApiError,
  getDataManagementSummary,
  listVideoSummaries,
  requestHandPoseCandidates,
  retryHandPoseCandidates,
  type DataManagementSummary,
  type VideoSummary,
  type VideoSummaryFilters,
  type VideoSummaryHandResult,
  type VideoSummaryHandStatus,
  type VideoSummaryMediaStatus,
} from '@/api/catalog'
import { paths } from '@/app/paths'
import { formatDateTime, statusTone } from '@/catalog-presentation'

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

export default function VideoManagementView() {
  const { projectId = '' } = useParams()
  const [filters, setFilters] = useState<VideoSummaryFilters>({})
  const [items, setItems] = useState<VideoSummary[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [summary, setSummary] = useState<DataManagementSummary | null>(null)
  const [actionVideoId, setActionVideoId] = useState<string | null>(null)
  const [actionMessage, setActionMessage] = useState('')
  const activeRequest = useRef<AbortController | null>(null)

  const loadPage = useCallback(
    async (append = false, nextFilters = filters) => {
      activeRequest.current?.abort()
      const request = new AbortController()
      activeRequest.current = request
      if (!append) setLoadState('loading')
      setErrorMessage('')
      try {
        const [page, nextSummary] = await Promise.all([
          listVideoSummaries(projectId, nextFilters, append ? nextCursor : null, request.signal),
          getDataManagementSummary(projectId, request.signal),
        ])
        if (activeRequest.current !== request) return
        setItems((current) => (append ? [...current, ...page.items] : page.items))
        setNextCursor(page.next_cursor)
        setSummary(nextSummary)
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
    [filters, nextCursor, projectId],
  )

  useEffect(() => {
    const timer = window.setTimeout(() => void loadPage(false, {}), 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId])

  function applyFilters(nextFilters: VideoSummaryFilters) {
    setFilters(nextFilters)
    setItems([])
    setNextCursor(null)
    void loadPage(false, nextFilters)
  }

  async function startHandPose(row: VideoSummary) {
    if (
      actionVideoId !== null ||
      row.hand_status === 'QUEUED' ||
      row.hand_status === 'RUNNING' ||
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
          <p>从媒体入库到手部候选和人工标注，按单个视频查看当前状态。</p>
        </div>
        <Button loading={loadState === 'loading'} onClick={() => void loadPage(false)}>
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
              <span className="cell-secondary">待标注 / 待复核 / 待清洗</span>
              <strong>
                {summary.pending_annotation} / {summary.pending_review} / {summary.pending_cleaning}
              </strong>
            </div>
          </div>
        </Card>
      ) : null}
      <Card className="filter-card" variant="outlined">
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
          <Button onClick={() => void loadPage(false)}>重试</Button>
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
                    <span className="cell-secondary">来源 {row.source_name}</span>
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
              { title: '标注', dataIndex: 'annotation_status', width: 110 },
              { title: '清洗', dataIndex: 'cleaning_status', width: 110 },
              { title: '留存', dataIndex: 'retention_status', width: 170 },
              {
                title: '操作',
                width: 220,
                render: (_, row) => (
                  <span className="table-actions">
                    <Link className="text-link" to={paths.asset(projectId, row.video_id)}>
                      {row.hand_status === 'SUCCEEDED' ? '播放 / 查看结果' : '播放'}
                    </Link>
                    {row.hand_status === 'QUEUED' || row.hand_status === 'RUNNING' ? (
                      <Button size="small" disabled>
                        检测中
                      </Button>
                    ) : row.hand_status === 'FAILED' && row.hand_retryable ? (
                      <Button
                        size="small"
                        loading={actionVideoId === row.video_id}
                        onClick={() => void startHandPose(row)}
                      >
                        重试检测
                      </Button>
                    ) : row.hand_status === 'FAILED' ? (
                      <span className="cell-secondary">不可重试</span>
                    ) : row.hand_status !== 'SUCCEEDED' ? (
                      <Button
                        size="small"
                        loading={actionVideoId === row.video_id}
                        onClick={() => void startHandPose(row)}
                      >
                        检测手部
                      </Button>
                    ) : null}
                    <Link className="text-link" to={paths.annotationTasks(projectId)}>
                      进入标注
                    </Link>
                  </span>
                ),
              },
            ]}
          />
          {nextCursor ? (
            <div className="catalog-pagination">
              <Button loading={loadState === 'loading'} onClick={() => void loadPage(true)}>
                加载更多
              </Button>
            </div>
          ) : null}
        </Card>
      ) : null}
    </>
  )
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
