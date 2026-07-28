import { Button, Card, Descriptions, Empty, Table, Tag } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import {
  CatalogApiError,
  getDatasetVersion,
  type DatasetVersionDetail,
  type DatasetVersionMemberSession,
  type DatasetVersionMemberStream,
} from '@/api/catalog'
import { paths } from '@/app/paths'
import { assemblyStatusLabels, formatDateTime, qualityStatusLabels, shortId, statusTone } from '@/catalog-presentation'

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
  const activeRequest = useRef<AbortController | null>(null)

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

  useEffect(() => {
    const timer = window.setTimeout(() => void loadDetail(), 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
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
              bordered
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
