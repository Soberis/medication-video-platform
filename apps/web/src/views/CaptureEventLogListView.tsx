import { Button, Card, DatePicker, Empty, Form, Input, Table } from 'antd'
import type { Dayjs } from 'dayjs'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router'

import {
  CatalogApiError,
  listCaptureEventLogs,
  type CaptureEventLogFilters,
  type CaptureEventLogRead,
} from '@/api/catalog'
import { formatBytes, formatDateTime, shortId } from '@/catalog-presentation'

export default function CaptureEventLogListView() {
  const { projectId = '' } = useParams()
  return <CaptureEventLogListContent key={projectId} />
}

function CaptureEventLogListContent() {
  const { projectId = '' } = useParams()
  const [batchId, setBatchId] = useState('')
  const [deviceId, setDeviceId] = useState('')
  const [indexedRange, setIndexedRange] = useState<[Dayjs, Dayjs] | null>(null)
  const [activeFilters, setActiveFilters] = useState<CaptureEventLogFilters>({})
  const [items, setItems] = useState<CaptureEventLogRead[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [isAppending, setIsAppending] = useState(false)
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [requestId, setRequestId] = useState<string | null>(null)
  const activeRequest = useRef<AbortController | null>(null)

  const loadPage = useCallback(
    async (append = false, filters = activeFilters) => {
      activeRequest.current?.abort()
      const request = new AbortController()
      activeRequest.current = request
      setIsAppending(append)
      if (!append) setLoadState('loading')
      setErrorCode('')
      setErrorMessage('')
      setRequestId(null)
      try {
        const page = await listCaptureEventLogs(
          projectId,
          filters,
          append ? nextCursor : null,
          request.signal,
        )
        if (request.signal.aborted || activeRequest.current !== request) return
        setItems((current) => (append ? [...current, ...page.items] : page.items))
        setNextCursor(page.next_cursor)
        setLoadState('success')
      } catch (error) {
        if (request.signal.aborted || activeRequest.current !== request) return
        setLoadState('error')
        if (error instanceof CatalogApiError) {
          setErrorCode(error.errorCode)
          setErrorMessage(error.message)
          setRequestId(error.requestId)
        } else {
          setErrorCode('CAPTURE_EVENT_LOG_REQUEST_FAILED')
          setErrorMessage('暂时无法读取采集事件日志索引，请稍后重试。')
        }
      } finally {
        if (activeRequest.current === request) {
          activeRequest.current = null
          setIsAppending(false)
        }
      }
    },
    [activeFilters, nextCursor, projectId],
  )

  useEffect(() => {
    const timer = window.setTimeout(() => void loadPage(false, {}), 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
      activeRequest.current = null
    }
    // loadPage changes with pagination state; project changes are the only automatic reload trigger.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId])

  function applyFilters() {
    const filters: CaptureEventLogFilters = {
      batchId: batchId.trim() || undefined,
      deviceId: deviceId.trim() || undefined,
      indexedFrom: indexedRange?.[0].toISOString(),
      indexedBefore: indexedRange?.[1].toISOString(),
    }
    setActiveFilters(filters)
    setItems([])
    setNextCursor(null)
    void loadPage(false, filters)
  }

  function resetFilters() {
    setBatchId('')
    setDeviceId('')
    setIndexedRange(null)
    setActiveFilters({})
    setItems([])
    setNextCursor(null)
    void loadPage(false, {})
  }

  return (
    <>
      <section className="page-heading" aria-labelledby="capture-event-logs-title">
        <div>
          <p className="app-eyebrow">边端上传 · 只读索引</p>
          <h1 id="capture-event-logs-title">采集事件日志</h1>
          <p>查看已验证的事件日志对象索引，不读取日志内容、不提供下载，也不推断设备动作。</p>
        </div>
        <Button loading={loadState === 'loading'} onClick={() => void loadPage(false)}>
          刷新
        </Button>
      </section>
      <Card className="filter-card" variant="outlined">
        <Form
          className="catalog-filters event-log-filters"
          layout="vertical"
          onFinish={applyFilters}
        >
          <Form.Item label="批次 ID" htmlFor="capture-event-log-batch-id">
            <Input
              id="capture-event-log-batch-id"
              value={batchId}
              allowClear
              onChange={(event) => setBatchId(event.target.value)}
            />
          </Form.Item>
          <Form.Item label="设备 ID" htmlFor="capture-event-log-device-id">
            <Input
              id="capture-event-log-device-id"
              value={deviceId}
              allowClear
              placeholder="例如 synthetic-edge-01"
              onChange={(event) => setDeviceId(event.target.value)}
            />
          </Form.Item>
          <Form.Item label="平台索引时间" htmlFor="capture-event-log-indexed-from">
            <DatePicker.RangePicker
              id={{
                start: 'capture-event-log-indexed-from',
                end: 'capture-event-log-indexed-before',
              }}
              showTime
              value={indexedRange}
              onChange={(value) => setIndexedRange(value as [Dayjs, Dayjs] | null)}
              placeholder={['开始时间', '结束时间']}
            />
          </Form.Item>
          <div className="filter-actions">
            <Button htmlType="submit" type="primary">
              应用筛选
            </Button>
            <Button onClick={resetFilters}>重置</Button>
          </div>
        </Form>
      </Card>
      {loadState === 'loading' ? (
        <div className="catalog-notice" role="status" aria-live="polite">
          正在读取采集事件日志索引…
        </div>
      ) : null}
      {loadState === 'error' ? (
        <div className="catalog-error" role="alert">
          <strong>{errorCode}</strong>
          <span>{errorMessage}</span>
          {requestId ? <small>请求标识：{requestId}</small> : null}
          <Button onClick={() => void loadPage(false)}>重试</Button>
        </div>
      ) : null}
      {items.length > 0 ? (
        <Card className="table-card" variant="outlined" aria-busy={isAppending}>
          <Table<CaptureEventLogRead>
            dataSource={items}
            rowKey="capture_event_log_id"
            pagination={false}
            scroll={{ x: 1050 }}
            columns={[
              {
                title: '事件日志索引',
                width: 220,
                render: (_, row) => (
                  <>
                    <strong className="mono-wrap" title={row.capture_event_log_id}>
                      {shortId(row.capture_event_log_id)}
                    </strong>
                    <span className="cell-secondary" title={row.client_file_id}>
                      客户端文件：{shortId(row.client_file_id)}
                    </span>
                  </>
                ),
              },
              { title: '设备', dataIndex: 'device_id', width: 180 },
              {
                title: '来源批次',
                width: 170,
                render: (_, row) => (
                  <span className="mono-wrap" title={row.batch_id}>
                    {shortId(row.batch_id)}
                  </span>
                ),
              },
              {
                title: '对象摘要',
                width: 200,
                render: (_, row) => (
                  <>
                    <strong>{row.content_type}</strong>
                    <span className="cell-secondary">{formatBytes(row.size_bytes)}</span>
                  </>
                ),
              },
              {
                title: 'SHA-256',
                width: 180,
                render: (_, row) => (
                  <span className="mono-wrap" title={row.sha256}>
                    {shortId(row.sha256)}
                  </span>
                ),
              },
              {
                title: '平台索引时间',
                width: 190,
                render: (_, row) => formatDateTime(row.indexed_at),
              },
            ]}
          />
          <div className="table-footer">
            <span>已显示 {items.length} 条采集事件日志索引</span>
            {nextCursor ? (
              <Button
                disabled={loadState === 'loading'}
                loading={isAppending}
                onClick={() => void loadPage(true)}
              >
                加载更多
              </Button>
            ) : null}
          </div>
        </Card>
      ) : null}
      {loadState === 'success' && items.length === 0 ? (
        <Empty description="当前项目没有符合条件的采集事件日志索引。请先检查上传批次是否已完成。" />
      ) : null}
    </>
  )
}
