import { Button, Card, Descriptions, Empty, Table, Tag } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import {
  CatalogApiError,
  getDatasetVersion,
  getDatasetVersionCurrentAdmission,
  type DatasetAdmissionPreview,
  type DatasetVersionDetail,
  type DatasetVersionCurrentAdmission,
  type DatasetVersionCurrentAdmissionEvidenceChange,
  type DatasetVersionCurrentAdmissionMember,
  type DatasetVersionMemberSession,
  type DatasetVersionMemberStream,
} from '@/api/catalog'
import { paths } from '@/app/paths'
import { assemblyStatusLabels, formatDateTime, qualityStatusLabels, shortId, statusTone } from '@/catalog-presentation'

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

const evidenceChangeLabels: Record<string, string> = {
  SESSION_EXPECTED_CAMERA_COUNT_CHANGED: '预期相机数量已变化',
  SESSION_RECEIVED_CAMERA_COUNT_CHANGED: '已接收相机数量已变化',
  SESSION_ASSEMBLY_STATUS_CHANGED: '组装状态已变化',
  SESSION_QUALITY_STATUS_CHANGED: '技术检测状态已变化',
  STREAM_ADDED: '发现新增视频流',
  STREAM_REMOVED: '原有视频流已移除',
  STREAM_ASSET_SHA256_CHANGED: '视频资产摘要已变化',
  STREAM_QUALITY_REVIEW_DECISION_CHANGED: '人工质量结论已变化',
  STREAM_QUALITY_REVIEW_VERSION_CHANGED: '人工质量版本已变化',
  STREAM_ANNOTATION_REVISION_CHANGED: '标注修订已变化',
  STREAM_ANNOTATION_DOCUMENT_SHA256_CHANGED: '标注文档摘要已变化',
  STREAM_LABEL_SCHEMA_DIGEST_CHANGED: '标签规范摘要已变化',
  STREAM_CLEANING_RESULT_CHANGED: '清洗结果已变化',
  STREAM_CLEANING_RUN_CHANGED: '清洗运行已变化',
  STREAM_CLEANING_RULE_SET_DIGEST_CHANGED: '清洗规则摘要已变化',
  STREAM_POST_CLEANING_OUTCOME_CHANGED: '后置清洗结论已变化',
}

export default function DatasetVersionDetailView() {
  const { projectId = '', id: datasetVersionId = '' } = useParams()
  return <DatasetVersionDetailContent key={`${projectId}:${datasetVersionId}`} />
}

function DatasetVersionDetailContent() {
  const { projectId = '', id: datasetVersionId = '' } = useParams()
  const [detail, setDetail] = useState<DatasetVersionDetail | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [requestId, setRequestId] = useState<string | null>(null)
  const [currentAdmission, setCurrentAdmission] = useState<DatasetVersionCurrentAdmission | null>(null)
  const [currentAdmissionState, setCurrentAdmissionState] = useState<'idle' | 'loading' | 'success' | 'error'>(
    'idle',
  )
  const [currentAdmissionErrorCode, setCurrentAdmissionErrorCode] = useState('')
  const [currentAdmissionErrorMessage, setCurrentAdmissionErrorMessage] = useState('')
  const [currentAdmissionRequestId, setCurrentAdmissionRequestId] = useState<string | null>(null)
  const activeRequest = useRef<AbortController | null>(null)
  const currentAdmissionRequest = useRef<AbortController | null>(null)

  const streamCount = useMemo(
    () => detail?.members.reduce((total, member) => total + member.streams.length, 0) ?? 0,
    [detail],
  )

  const loadDetail = useCallback(async () => {
    activeRequest.current?.abort()
    const request = new AbortController()
    activeRequest.current = request
    setLoadState('loading')
    setErrorCode('')
    setErrorMessage('')
    setRequestId(null)
    try {
      const response = await getDatasetVersion(projectId, datasetVersionId, request.signal)
      if (activeRequest.current !== request) return
      setDetail(response)
      setLoadState('success')
    } catch (error) {
      if (request.signal.aborted || activeRequest.current !== request) return
      setDetail(null)
      setLoadState('error')
      if (error instanceof CatalogApiError) {
        setErrorCode(error.errorCode)
        setErrorMessage(error.message)
        setRequestId(error.requestId)
      } else {
        setErrorCode('DATASET_VERSION_DETAIL_FAILED')
        setErrorMessage('暂时无法读取数据集草稿证据，请稍后重试。')
      }
    } finally {
      if (activeRequest.current === request) activeRequest.current = null
    }
  }, [datasetVersionId, projectId])

  const checkCurrentAdmission = useCallback(async () => {
    currentAdmissionRequest.current?.abort()
    const request = new AbortController()
    currentAdmissionRequest.current = request
    setCurrentAdmission(null)
    setCurrentAdmissionState('loading')
    setCurrentAdmissionErrorCode('')
    setCurrentAdmissionErrorMessage('')
    setCurrentAdmissionRequestId(null)
    try {
      const response = await getDatasetVersionCurrentAdmission(projectId, datasetVersionId, request.signal)
      if (currentAdmissionRequest.current !== request) return
      setCurrentAdmission(response)
      setCurrentAdmissionState('success')
    } catch (error) {
      if (request.signal.aborted || currentAdmissionRequest.current !== request) return
      setCurrentAdmission(null)
      setCurrentAdmissionState('error')
      if (error instanceof CatalogApiError) {
        setCurrentAdmissionErrorCode(error.errorCode)
        setCurrentAdmissionErrorMessage(error.message)
        setCurrentAdmissionRequestId(error.requestId)
      } else {
        setCurrentAdmissionErrorCode('DATASET_VERSION_CURRENT_ADMISSION_FAILED')
        setCurrentAdmissionErrorMessage('暂时无法核对当前准入事实，请稍后重试。')
      }
    } finally {
      if (currentAdmissionRequest.current === request) currentAdmissionRequest.current = null
    }
  }, [datasetVersionId, projectId])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadDetail(), 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
      activeRequest.current = null
      currentAdmissionRequest.current?.abort()
      currentAdmissionRequest.current = null
    }
  }, [loadDetail])

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑">
        <Link to={paths.datasets(projectId)}>数据集草稿</Link>
        <span aria-hidden="true">/</span>
        <span>证据详情</span>
      </nav>
      {loadState === 'loading' ? (
        <div className="catalog-notice" role="status">
          正在读取不可变候选快照…
        </div>
      ) : null}
      {loadState === 'error' ? (
        <div className="catalog-error" role="alert">
          <strong>{errorCode}</strong>
          <span>{errorMessage}</span>
          {requestId ? <small>请求标识：{requestId}</small> : null}
          <Button onClick={() => void loadDetail()}>重试</Button>
        </div>
      ) : null}
      {loadState === 'success' && detail ? (
        <>
          <section className="page-heading detail-heading" aria-labelledby="dataset-version-title">
            <div>
              <p className="app-eyebrow">不可变候选快照</p>
              <h1 id="dataset-version-title">{detail.display_name}</h1>
              <p>以下证据只反映创建草稿时的事实；后续拆分、冻结和导出仍须单独验证。</p>
            </div>
            <div className="heading-tags">
              <Tag color="blue">草稿</Tag>
            </div>
          </section>
          <Card className="detail-card" title={<strong>草稿事实</strong>} variant="outlined">
            <Descriptions
              column={{ xs: 1, sm: 2, lg: 4 }}
              items={[
                { key: 'creator', label: '创建人', children: detail.created_by },
                { key: 'created', label: '创建时间', children: formatDateTime(detail.created_at) },
                { key: 'sessions', label: '候选操作', children: `${detail.members.length} 组` },
                { key: 'streams', label: '候选视频流', children: `${streamCount} 路` },
              ]}
            />
            <details className="technical-facts">
              <summary>查看草稿标识</summary>
              <dl>
                <div>
                  <dt>DatasetVersion ID</dt>
                  <dd>
                    <code>{detail.dataset_version_id}</code>
                  </dd>
                </div>
              </dl>
            </details>
          </Card>
          <Card
            className="detail-card"
            extra={
              <Button loading={currentAdmissionState === 'loading'} onClick={() => void checkCurrentAdmission()}>
                核对当前事实
              </Button>
            }
            title={<strong>当前准入复核</strong>}
            variant="outlined"
          >
            <p className="form-hint">
              此核对只读取当前服务端事实，不会修改不可变候选快照。结果只说明当前草稿创建前提，不代表可冻结/可导出。
            </p>
            {currentAdmissionState === 'idle' ? <p>尚未核对当前事实。</p> : null}
            {currentAdmissionState === 'loading' ? (
              <div className="catalog-notice" role="status" aria-live="polite">
                正在核对当前准入事实…
              </div>
            ) : null}
            {currentAdmissionState === 'error' ? (
              <div className="catalog-error" role="alert">
                <strong>{currentAdmissionErrorCode}</strong>
                <span>{currentAdmissionErrorMessage}</span>
                {currentAdmissionRequestId ? <small>请求标识：{currentAdmissionRequestId}</small> : null}
                <Button onClick={() => void checkCurrentAdmission()}>重试</Button>
              </div>
            ) : null}
            {currentAdmissionState === 'success' && currentAdmission ? (
              <CurrentAdmissionEvidence projectId={projectId} admission={currentAdmission} />
            ) : null}
          </Card>
          <Card
            className="detail-card table-card"
            variant="outlined"
            title={
              <div className="card-header-row">
                <strong>候选配药操作</strong>
                <span>展开行可核对每路视频的固定摘要与标注清洗证据</span>
              </div>
            }
          >
            <Table<DatasetVersionMemberSession>
              dataSource={detail.members}
              rowKey="capture_session_id"
              pagination={false}
              scroll={{ x: 940 }}
              expandable={{
                rowExpandable: (member) => member.streams.length > 0,
                expandedRowRender: (member) => <StreamEvidence projectId={projectId} streams={member.streams} />,
              }}
              columns={[
                {
                  title: '候选顺序',
                  width: 100,
                  render: (_, row) => row.candidate_index + 1,
                },
                {
                  title: '配药操作',
                  width: 220,
                  render: (_, row) => (
                    <Link className="text-link table-action-link" to={paths.session(projectId, row.capture_session_id)}>
                      {shortId(row.capture_session_id)}
                    </Link>
                  ),
                },
                {
                  title: '视频完整性',
                  width: 150,
                  render: (_, row) => `${row.received_camera_count} / ${row.expected_camera_count} 路`,
                },
                {
                  title: '组装状态',
                  width: 140,
                  render: (_, row) => (
                    <Tag color={statusTone(row.assembly_status)}>
                      {assemblyStatusLabels[row.assembly_status]}
                    </Tag>
                  ),
                },
                {
                  title: '技术检测',
                  width: 140,
                  render: (_, row) => (
                    <Tag color={statusTone(row.quality_status)}>
                      {qualityStatusLabels[row.quality_status]}
                    </Tag>
                  ),
                },
                {
                  title: '已固定视频流',
                  width: 130,
                  render: (_, row) => `${row.streams.length} 路`,
                },
              ]}
            />
          </Card>
        </>
      ) : null}
    </>
  )
}

function CurrentAdmissionEvidence({
  projectId,
  admission,
}: {
  projectId: string
  admission: DatasetVersionCurrentAdmission
}) {
  return (
    <>
      <p>
        <Tag color={admission.all_members_currently_admission_eligible ? 'success' : 'warning'}>
          {admission.all_members_currently_admission_eligible ? '当前仍满足草稿创建前提' : '当前存在准入阻断'}
        </Tag>
        {admission.all_members_currently_admission_eligible
          ? '所有候选操作当前仍满足草稿创建前提。'
          : '至少一条候选操作当前不满足草稿创建前提。'}
      </p>
      <Table<DatasetVersionCurrentAdmissionMember>
        dataSource={admission.members}
        rowKey="capture_session_id"
        pagination={false}
        scroll={{ x: 1220 }}
        columns={[
          {
            title: '候选顺序',
            width: 100,
            render: (_, row) => row.candidate_index + 1,
          },
          {
            title: '配药操作',
            width: 190,
            render: (_, row) => (
              <Link className="text-link table-action-link" to={paths.session(projectId, row.capture_session_id)}>
                {shortId(row.capture_session_id)}
              </Link>
            ),
          },
          {
            title: '当前结果',
            width: 150,
            render: (_, row) => (
              <Tag color={row.current_admission.eligible ? 'success' : 'warning'}>
                {row.current_admission.eligible ? '仍满足前提' : '当前有阻断'}
              </Tag>
            ),
          },
          {
            title: '当前阻断原因',
            width: 350,
            render: (_, row) => <AdmissionBlockingReasons admission={row.current_admission} />,
          },
          {
            title: '固定证据变化',
            width: 430,
            render: (_, row) => <AdmissionEvidenceChanges changes={row.evidence_changes} />,
          },
        ]}
      />
    </>
  )
}

function AdmissionBlockingReasons({ admission }: { admission: DatasetAdmissionPreview }) {
  const reasonCodes = new Set(admission.blocking_reasons)
  for (const stream of admission.streams) {
    for (const reason of stream.blocking_reasons) reasonCodes.add(reason)
  }
  if (reasonCodes.size === 0) return <span>无当前阻断</span>
  return (
    <>
      {[...reasonCodes].map((code) => (
        <Tag key={code} color="warning" title={code}>
          {admissionReasonLabels[code] ?? code}
        </Tag>
      ))}
    </>
  )
}

function AdmissionEvidenceChanges({ changes }: { changes: DatasetVersionCurrentAdmissionEvidenceChange[] }) {
  const changeItems = changes.flatMap((change) =>
    change.change_codes.map((code) => ({
      code,
      scope: change.scope,
      cameraStreamId: change.camera_stream_id,
    })),
  )
  if (changeItems.length === 0) return <span>固定证据未发生变化</span>
  return (
    <>
      {changeItems.map((change, index) => {
        const source =
          change.scope === 'SESSION'
            ? '操作事实'
            : change.cameraStreamId
              ? `视频流 ${shortId(change.cameraStreamId)}`
              : '视频流'
        return (
          <Tag key={`${change.scope}:${change.cameraStreamId ?? 'none'}:${change.code}:${index}`} title={change.code}>
            {source} · {evidenceChangeLabels[change.code] ?? change.code}
          </Tag>
        )
      })}
    </>
  )
}

function StreamEvidence({ projectId, streams }: { projectId: string; streams: DatasetVersionMemberStream[] }) {
  if (streams.length === 0) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该候选操作没有固定视频流。" />
  return (
    <div className="dataset-stream-evidence">
      <Table<DatasetVersionMemberStream>
        dataSource={streams}
        rowKey="camera_stream_id"
        pagination={false}
        size="small"
        scroll={{ x: 1180 }}
        columns={[
          { title: '相机序号', dataIndex: 'camera_index', width: 100 },
          {
            title: '视频资产',
            width: 180,
            render: (_, row) => (
              <Link className="text-link table-action-link" to={paths.asset(projectId, row.clip_asset_id)}>
                {shortId(row.clip_asset_id)}
              </Link>
            ),
          },
          {
            title: '人工质量',
            width: 150,
            render: (_, row) => `合格 · v${row.quality_review_version}`,
          },
          { title: '独立复核', width: 130, render: () => <Tag color="success">已通过</Tag> },
          { title: '后置清洗', width: 130, render: () => <Tag color="success">通过</Tag> },
          {
            title: '证据摘要',
            width: 180,
            render: (_, row) => (
              <details className="technical-facts compact-facts">
                <summary>查看摘要</summary>
                <dl>
                  <div>
                    <dt>资产 SHA-256</dt>
                    <dd>
                      <code>{row.asset_sha256}</code>
                    </dd>
                  </div>
                  <div>
                    <dt>标注文档 SHA-256</dt>
                    <dd>
                      <code>{row.annotation_document_sha256}</code>
                    </dd>
                  </div>
                  <div>
                    <dt>标签规范摘要</dt>
                    <dd>
                      <code>{row.label_schema_digest}</code>
                    </dd>
                  </div>
                  <div>
                    <dt>清洗规则摘要</dt>
                    <dd>
                      <code>{row.cleaning_rule_set_digest}</code>
                    </dd>
                  </div>
                </dl>
              </details>
            ),
          },
        ]}
      />
    </div>
  )
}
