import { Button, Card, DatePicker, Empty, Form, Input, Select, Table, Tag } from 'antd'
import type { Dayjs } from 'dayjs'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import {
  CatalogApiError,
  listCaptureSessions,
  type CaptureQualityStatus,
  type CaptureSessionAssemblyStatus,
  type CaptureSessionFilters,
  type CaptureSessionListItem,
} from '@/api/catalog'
import { paths } from '@/app/paths'
import {
  assemblyStatusLabels,
  formatDateTime,
  qualityStatusLabels,
  shortId,
  statusLabel,
  statusTone,
} from '@/catalog-presentation'

export default function SessionListView() {
  const { projectId = '' } = useParams()
  const [deviceId, setDeviceId] = useState('')
  const [assemblyStatus, setAssemblyStatus] = useState<CaptureSessionAssemblyStatus | undefined>()
  const [qualityStatus, setQualityStatus] = useState<CaptureQualityStatus | undefined>()
  const [recordedRange, setRecordedRange] = useState<[Dayjs, Dayjs] | null>(null)
  const [activeFilters, setActiveFilters] = useState<CaptureSessionFilters>({})
  const [items, setItems] = useState<CaptureSessionListItem[]>([])
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
        const page = await listCaptureSessions(
          projectId,
          filters,
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
        if (error instanceof CatalogApiError) {
          setErrorCode(error.errorCode)
          setErrorMessage(error.message)
          setRequestId(error.requestId)
        } else {
          setErrorCode('CATALOG_REQUEST_FAILED')
          setErrorMessage('暂时无法读取配药操作记录，请稍后重试。')
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
    const timer = window.setTimeout(() => {
      setItems([])
      setNextCursor(null)
      setActiveFilters({})
      void loadPage(false, {})
    }, 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
    }
    // loadPage changes with pagination state; project changes are the only automatic reload trigger.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId])

  function applyFilters() {
    const filters: CaptureSessionFilters = {
      deviceId: deviceId.trim() || undefined,
      assemblyStatus,
      qualityStatus,
      recordedFrom: recordedRange?.[0].toISOString(),
      recordedBefore: recordedRange?.[1].toISOString(),
    }
    setActiveFilters(filters)
    setItems([])
    setNextCursor(null)
    void loadPage(false, filters)
  }
  function resetFilters() {
    setDeviceId('')
    setAssemblyStatus(undefined)
    setQualityStatus(undefined)
    setRecordedRange(null)
    setActiveFilters({})
    setItems([])
    setNextCursor(null)
    void loadPage(false, {})
  }

  return (
    <>
      <section className="page-heading" aria-labelledby="sessions-title">
        <div>
          <p className="app-eyebrow">护士配药 · 多相机数据</p>
          <h1 id="sessions-title">配药操作记录</h1>
          <p>按一次配药操作查看多路视频是否绑定、是否完成媒体入库，以及后续质量状态。</p>
        </div>
        <Button loading={loadState === 'loading'} onClick={() => void loadPage(false)}>
          刷新
        </Button>
      </section>
      <Card className="filter-card" variant="outlined">
        <Form className="catalog-filters" layout="vertical" onFinish={applyFilters}>
          <Form.Item label="边端设备 ID">
            <Input
              value={deviceId}
              allowClear
              placeholder="例如 synthetic-edge-01"
              onChange={(event) => setDeviceId(event.target.value)}
            />
          </Form.Item>
          <Form.Item label="组装状态">
            <Select
              value={assemblyStatus}
              allowClear
              placeholder="全部状态"
              onChange={setAssemblyStatus}
              options={Object.entries(assemblyStatusLabels).map(([value, label]) => ({
                value,
                label,
              }))}
            />
          </Form.Item>
          <Form.Item label="技术检测状态">
            <Select
              value={qualityStatus}
              allowClear
              placeholder="全部状态"
              onChange={setQualityStatus}
              options={Object.entries(qualityStatusLabels).map(([value, label]) => ({
                value,
                label,
              }))}
            />
          </Form.Item>
          <Form.Item label="拍摄时间">
            <DatePicker.RangePicker
              showTime
              value={recordedRange}
              onChange={(value) => setRecordedRange(value as [Dayjs, Dayjs] | null)}
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
          正在读取配药操作记录…
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
          <Table<CaptureSessionListItem>
            dataSource={items}
            rowKey="capture_session_id"
            pagination={false}
            scroll={{ x: 1100 }}
            columns={[
              {
                title: '拍摄时间',
                width: 190,
                render: (_, row) => (
                  <>
                    <strong>{formatDateTime(row.device_time_utc)}</strong>
                    <span className="cell-secondary">
                      接收：{formatDateTime(row.server_receive_time)}
                    </span>
                  </>
                ),
              },
              { title: '设备', dataIndex: 'device_id', width: 150 },
              {
                title: '相机完整性',
                width: 170,
                render: (_, row) => (
                  <>
                    <strong>
                      已绑定 {row.bound_stream_count} / {row.expected_camera_count}
                    </strong>
                    <span className="cell-secondary">已入库 {row.received_camera_count}</span>
                  </>
                ),
              },
              {
                title: 'CameraSet / 布局',
                width: 180,
                render: (_, row) => (
                  <>
                    <span>
                      {row.camera_set_key} · v{row.camera_set_configuration_version}
                    </span>
                    <span className="cell-secondary">{row.camera_layout_version}</span>
                  </>
                ),
              },
              {
                title: '组装状态',
                width: 130,
                render: (_, row) => (
                  <Tag color={statusTone(row.assembly_status)}>
                    {statusLabel(assemblyStatusLabels, row.assembly_status)}
                  </Tag>
                ),
              },
              {
                title: '质量状态',
                width: 120,
                render: (_, row) => (
                  <Tag color={statusTone(row.quality_status)}>
                    {statusLabel(qualityStatusLabels, row.quality_status)}
                  </Tag>
                ),
              },
              {
                title: '操作',
                width: 100,
                fixed: 'right',
                render: (_, row) => (
                  <Link
                    className="text-link table-action-link"
                    aria-label={`查看配药操作：${shortId(row.capture_session_id)}`}
                    to={paths.session(projectId, row.capture_session_id)}
                  >
                    查看详情
                  </Link>
                ),
              },
            ]}
          />
          <div className="table-footer">
            <span>已显示 {items.length} 组配药操作</span>
            {nextCursor ? (
              <Button loading={isAppending} onClick={() => void loadPage(true)}>
                加载更多
              </Button>
            ) : null}
          </div>
        </Card>
      ) : null}
      {loadState === 'success' && items.length === 0 ? (
        <Empty description="当前项目没有符合条件的配药操作记录。可调整筛选，或通过边端自动上传和平台本地上传新增视频。" />
      ) : null}
    </>
  )
}
