import { Alert, Button, Card, DatePicker, Empty, Form, Input, Select, Table, Tag } from 'antd'
import type { Dayjs } from 'dayjs'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import {
  CatalogApiError,
  listClipAssets,
  type ClipAssetFilters,
  type ClipAssetRead,
  type ClipAssetStatus,
} from '@/api/catalog'
import { listAssetFolders, type AssetFolder } from '@/api/projects'
import { paths } from '@/app/paths'
import {
  assetStatusLabels,
  formatBytes,
  formatDateTime,
  qualityStatusLabels,
  statusLabel,
  statusTone,
  streamStatusLabels,
} from '@/catalog-presentation'

const UNCLASSIFIED = '__unclassified__'

export default function AssetListView() {
  const { projectId = '' } = useParams()
  const [captureSessionId, setCaptureSessionId] = useState('')
  const [batchId, setBatchId] = useState('')
  const [deviceId, setDeviceId] = useState('')
  const [cameraRole, setCameraRole] = useState('')
  const [assetStatus, setAssetStatus] = useState<ClipAssetStatus | undefined>()
  const [folderSelection, setFolderSelection] = useState('')
  const [recordedRange, setRecordedRange] = useState<[Dayjs, Dayjs] | null>(null)
  const [folders, setFolders] = useState<AssetFolder[]>([])
  const folderNames = useMemo(
    () => new Map(folders.map((folder) => [folder.folder_id, folder.display_name])),
    [folders],
  )
  const [activeFilters, setActiveFilters] = useState<ClipAssetFilters>({})
  const [items, setItems] = useState<ClipAssetRead[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [isAppending, setIsAppending] = useState(false)
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const activeRequest = useRef<AbortController | null>(null)

  const loadPage = useCallback(
    async (append = false, refreshFolders = false, filters = activeFilters) => {
      activeRequest.current?.abort()
      const request = new AbortController()
      activeRequest.current = request
      setIsAppending(append)
      if (!append) setLoadState('loading')
      setErrorCode('')
      setErrorMessage('')
      try {
        const [page, projectFolders] = await Promise.all([
          listClipAssets(projectId, filters, append ? nextCursor : null, request.signal),
          refreshFolders ? listAssetFolders(projectId, request.signal) : Promise.resolve(folders),
        ])
        if (activeRequest.current !== request) return
        setFolders(projectFolders)
        setItems((current) => (append ? [...current, ...page.items] : page.items))
        setNextCursor(page.next_cursor)
        setLoadState('success')
      } catch (error) {
        if (request.signal.aborted || activeRequest.current !== request) return
        setLoadState('error')
        if (error instanceof CatalogApiError) {
          setErrorCode(error.errorCode)
          setErrorMessage(error.message)
        } else {
          setErrorCode('CATALOG_REQUEST_FAILED')
          setErrorMessage('暂时无法读取视频资产，请稍后重试。')
        }
      } finally {
        if (activeRequest.current === request) {
          activeRequest.current = null
          setIsAppending(false)
        }
      }
    },
    [activeFilters, folders, nextCursor, projectId],
  )

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setFolders([])
      setItems([])
      setNextCursor(null)
      setActiveFilters({})
      void loadPage(false, true, {})
    }, 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId])

  function applyFilters() {
    const filters: ClipAssetFilters = {
      captureSessionId: captureSessionId.trim() || undefined,
      batchId: batchId.trim() || undefined,
      deviceId: deviceId.trim() || undefined,
      cameraRole: cameraRole.trim().toUpperCase() || undefined,
      status: assetStatus,
      folderId: folderSelection && folderSelection !== UNCLASSIFIED ? folderSelection : undefined,
      unclassified: folderSelection === UNCLASSIFIED || undefined,
      recordedFrom: recordedRange?.[0].toISOString(),
      recordedBefore: recordedRange?.[1].toISOString(),
    }
    setActiveFilters(filters)
    setItems([])
    setNextCursor(null)
    void loadPage(false, false, filters)
  }
  function resetFilters() {
    setCaptureSessionId('')
    setBatchId('')
    setDeviceId('')
    setCameraRole('')
    setAssetStatus(undefined)
    setFolderSelection('')
    setRecordedRange(null)
    setActiveFilters({})
    setItems([])
    setNextCursor(null)
    void loadPage(false, false, {})
  }

  return (
    <>
      <section className="page-heading" aria-labelledby="assets-title">
        <div>
          <p className="app-eyebrow">单路相机视频</p>
          <h1 id="assets-title">视频资产</h1>
          <p>用于跨 Session 排查单路视频状态；日常数据管理仍以一次配药操作为主。</p>
        </div>
        <Button loading={loadState === 'loading'} onClick={() => void loadPage(false, true)}>
          刷新
        </Button>
      </section>
      <Card className="filter-card" variant="outlined">
        <Form className="catalog-filters asset-filters" layout="vertical" onFinish={applyFilters}>
          <Form.Item label="Session ID">
            <Input
              value={captureSessionId}
              allowClear
              onChange={(event) => setCaptureSessionId(event.target.value)}
            />
          </Form.Item>
          <Form.Item label="批次 ID">
            <Input
              value={batchId}
              allowClear
              onChange={(event) => setBatchId(event.target.value)}
            />
          </Form.Item>
          <Form.Item label="设备 ID">
            <Input
              value={deviceId}
              allowClear
              onChange={(event) => setDeviceId(event.target.value)}
            />
          </Form.Item>
          <Form.Item label="相机角色">
            <Input
              value={cameraRole}
              allowClear
              placeholder="例如 HANDS"
              onChange={(event) => setCameraRole(event.target.value)}
            />
          </Form.Item>
          <Form.Item label="资产状态">
            <Select
              value={assetStatus}
              allowClear
              placeholder="全部状态"
              onChange={setAssetStatus}
              options={Object.entries(assetStatusLabels).map(([value, label]) => ({
                value,
                label,
              }))}
            />
          </Form.Item>
          <Form.Item label="文件夹">
            <Select
              value={folderSelection}
              onChange={setFolderSelection}
              options={[
                { label: '全部视频', value: '' },
                { label: '未分类', value: UNCLASSIFIED },
                ...folders.map((folder) => ({
                  label: folder.display_name,
                  value: folder.folder_id,
                })),
              ]}
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
      <Alert
        className="catalog-alert"
        title="文件夹只用于当前项目内的一层分类，不改变项目权限或对象存储位置。"
        type="info"
        showIcon
      />
      {loadState === 'loading' ? (
        <div className="catalog-notice" role="status">
          正在读取视频资产…
        </div>
      ) : null}
      {loadState === 'error' ? (
        <div className="catalog-error" role="alert">
          <strong>{errorCode}</strong>
          <span>{errorMessage}</span>
          <Button onClick={() => void loadPage(false)}>重试</Button>
        </div>
      ) : null}
      {items.length > 0 ? (
        <Card className="table-card" variant="outlined" aria-busy={isAppending}>
          <Table<ClipAssetRead>
            dataSource={items}
            rowKey="clip_asset_id"
            pagination={false}
            scroll={{ x: 1150 }}
            columns={[
              {
                title: '视频资产',
                width: 190,
                render: (_, row) => (
                  <>
                    <strong className="mono-wrap">{row.client_file_id}</strong>
                    <span className="cell-secondary">
                      {row.content_type} · {formatBytes(row.size_bytes)}
                    </span>
                  </>
                ),
              },
              {
                title: 'Session / 视角',
                width: 190,
                render: (_, row) =>
                  row.session_binding ? (
                    <>
                      <Link
                        className="text-link"
                        to={paths.session(projectId, row.session_binding.capture_session_id)}
                      >
                        {row.session_binding.camera_role} · #{row.session_binding.camera_index}
                      </Link>
                      <span className="cell-secondary">
                        {formatDateTime(row.session_binding.record_start_utc)}
                      </span>
                    </>
                  ) : (
                    '尚未绑定 Session'
                  ),
              },
              {
                title: '资产状态',
                width: 150,
                render: (_, row) => (
                  <Tag color={statusTone(row.status)}>
                    {statusLabel(assetStatusLabels, row.status)}
                  </Tag>
                ),
              },
              {
                title: '文件夹',
                width: 140,
                render: (_, row) =>
                  row.folder_id ? (folderNames.get(row.folder_id) ?? '未知文件夹') : '未分类',
              },
              {
                title: '流 / 质量',
                width: 160,
                render: (_, row) =>
                  row.session_binding ? (
                    <>
                      <span>
                        {statusLabel(streamStatusLabels, row.session_binding.stream_status)}
                      </span>
                      <span className="cell-secondary">
                        质量：{statusLabel(qualityStatusLabels, row.session_binding.quality_status)}
                      </span>
                    </>
                  ) : (
                    '等待 Session 组装'
                  ),
              },
              { title: '更新时间', width: 180, render: (_, row) => formatDateTime(row.updated_at) },
              {
                title: '操作',
                width: 100,
                fixed: 'right',
                render: (_, row) => (
                  <Link
                    className="text-link table-action-link"
                    aria-label={`查看视频详情：${row.client_file_id}`}
                    to={paths.asset(projectId, row.clip_asset_id)}
                  >
                    查看详情
                  </Link>
                ),
              },
            ]}
          />
          <div className="table-footer">
            <span>已显示 {items.length} 个视频资产</span>
            {nextCursor ? (
              <Button loading={isAppending} onClick={() => void loadPage(true)}>
                加载更多
              </Button>
            ) : null}
          </div>
        </Card>
      ) : null}
      {loadState === 'success' && items.length === 0 ? (
        <Empty description="当前项目没有符合条件的视频资产。请先检查边端上传和 Session 组装状态。" />
      ) : null}
    </>
  )
}
