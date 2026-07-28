import { Button, Card, Descriptions, Table, Tag } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import {
  CatalogApiError,
  getCaptureSession,
  getCaptureSessionDatasetAdmissionPreview,
  type CameraStream,
  type CaptureSessionDetail,
  type DatasetAdmissionPreview,
} from '@/api/catalog'
import { paths } from '@/app/paths'
import {
  assemblyStatusLabels,
  declaredSyncStatusLabels,
  formatDateTime,
  qualityStatusLabels,
  statusLabel,
  statusTone,
  streamStatusLabels,
} from '@/catalog-presentation'

const admissionReasonLabels: Record<string, string> = {
  SESSION_ASSEMBLY_INCOMPLETE: '本次配药操作的视频流尚未组装完整',
  SESSION_STREAM_COUNT_MISMATCH: '已接收的视频流数量与相机配置不一致',
  SESSION_QUALITY_NOT_PASS: '本次配药操作的技术检测尚未通过',
  STREAM_NOT_INGESTED: '有相机视频尚未完成媒体入库',
  STREAM_QUALITY_REJECTED: '有相机视频未通过技术检测',
  CLIP_ASSET_NOT_INGESTED: '有视频资产尚未完成媒体检测',
  CLIP_ASSET_DELETED: '有视频已经删除',
  QUALITY_REVIEW_NOT_QUALIFIED: '有视频尚未通过人工质量判断',
  QUALITY_REVIEW_CHANGED_AFTER_POST_CLEANING: '人工质量结论已变更，需要重新完成清洗检查',
  ANNOTATION_REVISION_MISSING: '有视频尚未提交人工标注',
  ANNOTATION_REVIEW_NOT_APPROVED: '有标注尚未通过独立复核',
  POST_CLEANING_MISSING: '有已复核标注尚未完成清洗检查',
  POST_CLEANING_WARNING: '有标注清洗结果需要人工确认',
  POST_CLEANING_REJECT: '有标注未通过清洗检查',
}

export default function SessionDetailView() {
  const { projectId = '', id: sessionId = '' } = useParams()
  const [detail, setDetail] = useState<CaptureSessionDetail | null>(null)
  const [admissionPreview, setAdmissionPreview] = useState<DatasetAdmissionPreview | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [admissionLoadState, setAdmissionLoadState] = useState<'loading' | 'success' | 'error'>(
    'loading',
  )
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [admissionErrorCode, setAdmissionErrorCode] = useState('')
  const [admissionErrorMessage, setAdmissionErrorMessage] = useState('')
  const detailRequest = useRef<AbortController | null>(null)
  const admissionRequest = useRef<AbortController | null>(null)

  const admissionReasonSummary = useMemo(() => {
    if (!admissionPreview) return []
    const affectedStreams = new Map<string, Set<string>>()
    for (const reason of admissionPreview.blocking_reasons) affectedStreams.set(reason, new Set())
    for (const stream of admissionPreview.streams)
      for (const reason of stream.blocking_reasons) {
        const streams = affectedStreams.get(reason) ?? new Set<string>()
        streams.add(stream.camera_stream_id)
        affectedStreams.set(reason, streams)
      }
    return [...affectedStreams.entries()].map(([code, streams]) => ({
      code,
      affectedStreamCount: streams.size,
      label: admissionReasonLabels[code] ?? `存在尚未识别的准入条件（${code}）`,
    }))
  }, [admissionPreview])
  const ingestedStreamCount =
    admissionPreview?.streams.filter(
      (stream) => stream.stream_status === 'INGESTED' && stream.asset_status === 'INGESTED',
    ).length ?? 0
  const qualifiedStreamCount =
    admissionPreview?.streams.filter((stream) => stream.quality_review_decision === 'QUALIFIED')
      .length ?? 0
  const approvedAnnotationCount =
    admissionPreview?.streams.filter(
      (stream) =>
        stream.annotation_review_decision === 'APPROVED' &&
        stream.post_cleaning_decision === 'PASS',
    ).length ?? 0

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
  const loadAdmissionPreview = useCallback(async () => {
    admissionRequest.current?.abort()
    const request = new AbortController()
    admissionRequest.current = request
    setAdmissionLoadState('loading')
    setAdmissionErrorCode('')
    setAdmissionErrorMessage('')
    try {
      const response = await getCaptureSessionDatasetAdmissionPreview(
        projectId,
        sessionId,
        request.signal,
      )
      if (admissionRequest.current !== request) return
      setAdmissionPreview(response)
      setAdmissionLoadState('success')
    } catch (error) {
      if (request.signal.aborted || admissionRequest.current !== request) return
      setAdmissionPreview(null)
      setAdmissionLoadState('error')
      setAdmissionErrorCode(
        error instanceof CatalogApiError ? error.errorCode : 'DATASET_ADMISSION_PREVIEW_FAILED',
      )
      setAdmissionErrorMessage(
        error instanceof CatalogApiError
          ? error.message
          : '暂时无法核对训练数据准入条件，请稍后重试。',
      )
    } finally {
      if (admissionRequest.current === request) admissionRequest.current = null
    }
  }, [projectId, sessionId])
  useEffect(() => {
    const timer = window.setTimeout(
      () => void Promise.all([loadDetail(), loadAdmissionPreview()]),
      0,
    )
    return () => {
      window.clearTimeout(timer)
      detailRequest.current?.abort()
      admissionRequest.current?.abort()
    }
  }, [loadAdmissionPreview, loadDetail])

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑">
        <Link to={paths.sessions(projectId)}>配药操作记录</Link>
        <span aria-hidden="true">/</span>
        <span>详情</span>
      </nav>
      {loadState === 'loading' ? (
        <div className="catalog-notice" role="status">
          正在读取 Session 详情…
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
          <Card
            className="detail-card"
            variant="outlined"
            title={
              <div className="card-header-row">
                <div>
                  <strong>训练数据准备度</strong>
                  <span className="card-header-note">当前事实预览，不会自动创建或冻结数据集</span>
                </div>
                {admissionLoadState === 'success' && admissionPreview ? (
                  <Tag color={admissionPreview.eligible ? 'success' : 'warning'}>
                    {admissionPreview.eligible ? '可以进入数据集' : '尚未准备完成'}
                  </Tag>
                ) : null}
              </div>
            }
          >
            {admissionLoadState === 'loading' ? (
              <div className="readiness-loading" role="status">
                正在核对采集、技术检测、人工质量和标注证据…
              </div>
            ) : null}
            {admissionLoadState === 'error' ? (
              <div className="catalog-error readiness-error" role="alert">
                <span>{admissionErrorMessage}</span>
                <strong>{admissionErrorCode}</strong>
                <Button onClick={() => void loadAdmissionPreview()}>重试</Button>
              </div>
            ) : null}
            {admissionLoadState === 'success' && admissionPreview ? (
              <>
                <div className="readiness-evidence" data-ready={admissionPreview.eligible}>
                  <div>
                    <span>已入库视频</span>
                    <strong>
                      {admissionPreview.received_camera_count} /{' '}
                      {admissionPreview.expected_camera_count} 路
                    </strong>
                    <small>{assemblyStatusLabels[admissionPreview.assembly_status]}</small>
                  </div>
                  <div>
                    <span>技术检测</span>
                    <strong>
                      {ingestedStreamCount} / {admissionPreview.streams.length} 路
                    </strong>
                    <small>{qualityStatusLabels[admissionPreview.quality_status]}</small>
                  </div>
                  <div>
                    <span>人工质量</span>
                    <strong>
                      {qualifiedStreamCount} / {admissionPreview.streams.length} 路
                    </strong>
                    <small>人工判断合格</small>
                  </div>
                  <div>
                    <span>标注与清洗</span>
                    <strong>
                      {approvedAnnotationCount} / {admissionPreview.streams.length} 路
                    </strong>
                    <small>独立复核及清洗通过</small>
                  </div>
                </div>
                {admissionPreview.eligible ? (
                  <div className="readiness-result readiness-result-ready">
                    <strong>当前证据满足训练数据准入条件</strong>
                    <span>后续创建数据集时仍会重新校验，避免使用已变化的数据。</span>
                  </div>
                ) : (
                  <div className="readiness-result">
                    <strong>还需完成 {admissionReasonSummary.length} 项条件</strong>
                    <ul>
                      {admissionReasonSummary.map((reason) => (
                        <li key={reason.code} title={reason.code}>
                          <span>{reason.label}</span>
                          {reason.affectedStreamCount > 0 ? (
                            <small>影响 {reason.affectedStreamCount} 路视频</small>
                          ) : null}
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </>
            ) : null}
          </Card>
          <Card className="detail-card" title={<strong>采集概况</strong>} variant="outlined">
            <Descriptions
              bordered
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
                  <dt>Session ID</dt>
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
