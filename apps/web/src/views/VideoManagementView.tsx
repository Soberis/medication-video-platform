import { Button, Card, Empty, Select, Table, Tag } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import {
  CatalogApiError,
  listVideoSummaries,
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
  const activeRequest = useRef<AbortController | null>(null)

  const loadPage = useCallback(
    async (append = false, nextFilters = filters) => {
      activeRequest.current?.abort()
      const request = new AbortController()
      activeRequest.current = request
      if (!append) setLoadState('loading')
      setErrorMessage('')
      try {
        const page = await listVideoSummaries(
          projectId,
          nextFilters,
          append ? nextCursor : null,
          request.signal,
        )
        if (activeRequest.current !== request) return
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
                    <span className="cell-secondary">{row.video_id}</span>
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
                width: 170,
                render: (_, row) => (
                  <span className="table-actions">
                    <Link className="text-link" to={paths.asset(projectId, row.video_id)}>
                      查看 / 检测
                    </Link>
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
