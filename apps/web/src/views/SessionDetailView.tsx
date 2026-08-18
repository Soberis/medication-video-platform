import { Button, Card, Descriptions, Table, Tag } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import {
  CatalogApiError,
  getCaptureSession,
  type CameraStream,
  type CaptureSessionDetail,
} from '@/api/catalog'
import { paths } from '@/app/paths'
import { SessionCameraPlayback } from '@/components/SessionCameraPlayback'
import {
  assemblyStatusLabels,
  declaredSyncStatusLabels,
  formatDateTime,
  qualityStatusLabels,
  statusLabel,
  statusTone,
  streamStatusLabels,
} from '@/catalog-presentation'

export default function SessionDetailView() {
  const { projectId = '', id: sessionId = '' } = useParams()
  return <SessionDetailContent key={`${projectId}:${sessionId}`} />
}

function SessionDetailContent() {
  const { projectId = '', id: sessionId = '' } = useParams()
  const [detail, setDetail] = useState<CaptureSessionDetail | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const detailRequest = useRef<AbortController | null>(null)

  const loadDetail = useCallback(async () => {
    detailRequest.current?.abort()
    const request = new AbortController()
    detailRequest.current = request
    setLoadState('loading')
    try {
      const response = await getCaptureSession(projectId, sessionId, request.signal)
      if (detailRequest.current !== request) return
      setDetail(response)
      setLoadState('success')
    } catch (error) {
      if (request.signal.aborted || detailRequest.current !== request) return
      setDetail(null)
      setLoadState('error')
      setErrorCode(error instanceof CatalogApiError ? error.errorCode : 'CATALOG_REQUEST_FAILED')
      setErrorMessage(
        error instanceof CatalogApiError ? error.message : '暂时无法读取配药操作详情，请稍后重试。',
      )
    } finally {
      if (detailRequest.current === request) detailRequest.current = null
    }
  }, [projectId, sessionId])
  useEffect(() => {
    const timer = window.setTimeout(() => void loadDetail(), 0)
    return () => {
      window.clearTimeout(timer)
      detailRequest.current?.abort()
    }
  }, [loadDetail])

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑">
        <Link to={paths.sessions(projectId)}>配药操作记录</Link>
        <span aria-hidden="true">/</span>
        <span>详情</span>
      </nav>
      {loadState === 'loading' ? (
        <div className="catalog-notice" role="status">
          正在读取多相机查看详情…
        </div>
      ) : null}
      {loadState === 'error' ? (
        <div className="catalog-error" role="alert">
          <strong>{errorCode}</strong>
          <span>{errorMessage}</span>
          <Button onClick={() => void loadDetail()}>重试</Button>
        </div>
      ) : null}
      {loadState === 'success' && detail ? (
        <>
          <section className="page-heading detail-heading" aria-labelledby="session-detail-title">
            <div>
              <p className="app-eyebrow">一次护士配药操作</p>
              <h1 id="session-detail-title">配药操作详情</h1>
              <p className="session-heading-context">
                <strong>{detail.device_id}</strong>
                <span>拍摄于 {formatDateTime(detail.device_time_utc)}</span>
              </p>
            </div>
            <div className="heading-tags">
              <Tag color={statusTone(detail.assembly_status)}>
                {assemblyStatusLabels[detail.assembly_status]}
              </Tag>
              <Tag color={statusTone(detail.quality_status)}>
                {qualityStatusLabels[detail.quality_status]}
              </Tag>
            </div>
          </section>
          <SessionCameraPlayback
            expectedCameras={detail.camera_set_snapshot.members}
            projectId={projectId}
            streams={detail.streams}
          />
          <Card className="detail-card" title={<strong>采集概况</strong>} variant="outlined">
            <Descriptions
              column={{ xs: 1, sm: 2, lg: 3 }}
              items={[
                { key: 'device', label: '拍摄设备', children: detail.device_id },
                {
                  key: 'received',
                  label: '平台接收',
                  children: formatDateTime(detail.server_receive_time),
                },
                {
                  key: 'streams',
                  label: '视频完整性',
                  children: `${detail.received_camera_count} / ${detail.expected_camera_count} 路`,
                },
                {
                  key: 'camera-set',
                  label: '相机配置',
                  children: `${detail.camera_set_snapshot.camera_set_key} · v${detail.camera_set_snapshot.configuration_version}`,
                },
                {
                  key: 'layout',
                  label: '布局版本',
                  children: detail.camera_set_snapshot.layout_version,
                },
                { key: 'sync', label: '同步组', children: detail.sync_group_id ?? '未提供' },
              ]}
            />
            <details className="technical-facts">
              <summary>查看技术标识</summary>
              <dl>
                <div>
                  <dt>内部采集标识</dt>
                  <dd>
                    <code>{detail.capture_session_id}</code>
                  </dd>
                </div>
                <div>
                  <dt>批次 ID</dt>
                  <dd>
                    <code>{detail.batch_id}</code>
                  </dd>
                </div>
                <div>
                  <dt>配置快照 ID</dt>
                  <dd>
                    <code>{detail.camera_set_snapshot.camera_set_snapshot_id}</code>
                  </dd>
                </div>
              </dl>
            </details>
          </Card>
          <Card
            className="detail-card table-card"
            variant="outlined"
            title={
              <div className="card-header-row">
                <strong>相机视频流</strong>
                <span>{detail.streams.length} 路已绑定</span>
              </div>
            }
          >
            <Table<CameraStream>
              dataSource={detail.streams}
              rowKey="camera_stream_id"
              pagination={false}
              scroll={{ x: 900 }}
              columns={[
                { title: '序号', dataIndex: 'camera_index', width: 72 },
                {
                  title: '视角 / 安装位',
                  width: 170,
                  render: (_, row) => (
                    <>
                      <strong>{row.camera_role}</strong>
                      <span className="cell-secondary">{row.mount_position}</span>
                    </>
                  ),
                },
                {
                  title: '拍摄开始',
                  width: 190,
                  render: (_, row) => formatDateTime(row.record_start_utc),
                },
                {
                  title: '流状态',
                  width: 160,
                  render: (_, row) => (
                    <Tag color={statusTone(row.stream_status)}>
                      {statusLabel(streamStatusLabels, row.stream_status)}
                    </Tag>
                  ),
                },
                {
                  title: '边端同步声明',
                  width: 180,
                  render: (_, row) => (
                    <>
                      <span>{statusLabel(declaredSyncStatusLabels, row.sync_status)}</span>
                      <span className="cell-secondary">偏移 {row.time_offset_ms} ms</span>
                    </>
                  ),
                },
                {
                  title: '技术检测',
                  width: 120,
                  render: (_, row) => statusLabel(qualityStatusLabels, row.quality_status),
                },
                {
                  title: '视频资产',
                  width: 100,
                  fixed: 'right',
                  render: (_, row) => (
                    <Link
                      className="text-link table-action-link"
                      to={paths.asset(projectId, row.clip_asset_id)}
                    >
                      查看资产
                    </Link>
                  ),
                },
              ]}
            />
          </Card>
        </>
      ) : null}
    </>
  )
}
