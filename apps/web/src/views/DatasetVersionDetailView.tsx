import { App as AntdApp, Button, Card, Descriptions, Empty, InputNumber, Table, Tag } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import {
  CatalogApiError,
  createDatasetSplitPlan,
  getDatasetVersion,
  getDatasetVersionCurrentAdmission,
  type DatasetAdmissionPreview,
  type DatasetVersionDetail,
  type DatasetVersionBuildBlocker,
  type DatasetVersionCurrentAdmission,
  type DatasetVersionCurrentAdmissionEvidenceChange,
  type DatasetVersionCurrentAdmissionMember,
  type DatasetVersionMemberSession,
  type DatasetVersionMemberStream,
  type DatasetSplitAssignment,
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

const buildBlockerLabels: Record<DatasetVersionBuildBlocker, string> = {
  SELECTION_RULE_NOT_FIXED: '尚未固定候选选择规则',
  LABEL_SCHEMA_NOT_FIXED: '尚未把统一标签规范固定为数据集配置',
  LABEL_SCHEMA_NOT_UNIFORM: '候选视频使用了不同的标签规范',
  CLEANING_RULE_SET_NOT_FIXED: '尚未把统一清洗规则固定为数据集配置',
  CLEANING_RULE_SET_NOT_UNIFORM: '候选视频使用了不同的清洗规则',
  SPLIT_PLAN_NOT_FIXED: '尚未固定试用组和留出组划分',
  EXPORT_PROFILE_NOT_FIXED: '尚未固定必需导出配置',
  CURRENT_ADMISSION_NOT_ELIGIBLE: '至少一条候选操作当前不再满足准入条件',
  SNAPSHOT_EVIDENCE_CHANGED: '当前事实与草稿固定证据存在变化',
}

export default function DatasetVersionDetailView() {
  const { projectId = '', id: datasetVersionId = '' } = useParams()
  return <DatasetVersionDetailContent key={`${projectId}:${datasetVersionId}`} />
}

function DatasetVersionDetailContent() {
  const { projectId = '', id: datasetVersionId = '' } = useParams()
  const { message, modal } = AntdApp.useApp()
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
  const [holdoutCount, setHoldoutCount] = useState(1)
  const [splitPlanState, setSplitPlanState] = useState<'idle' | 'creating' | 'error'>('idle')
  const [splitPlanErrorCode, setSplitPlanErrorCode] = useState('')
  const [splitPlanErrorMessage, setSplitPlanErrorMessage] = useState('')
  const [splitPlanRequestId, setSplitPlanRequestId] = useState<string | null>(null)
  const activeRequest = useRef<AbortController | null>(null)
  const currentAdmissionRequest = useRef<AbortController | null>(null)
  const splitPlanIntent = useRef<{ holdoutCount: number; idempotencyKey: string } | null>(null)

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
        setCurrentAdmissionErrorMessage('暂时无法检查构建准备，请稍后重试。')
      }
    } finally {
      if (currentAdmissionRequest.current === request) currentAdmissionRequest.current = null
    }
  }, [datasetVersionId, projectId])

  const createSplit = useCallback(async () => {
    const intent =
      splitPlanIntent.current?.holdoutCount === holdoutCount
        ? splitPlanIntent.current
        : { holdoutCount, idempotencyKey: crypto.randomUUID() }
    splitPlanIntent.current = intent
    setSplitPlanState('creating')
    setSplitPlanErrorCode('')
    setSplitPlanErrorMessage('')
    setSplitPlanRequestId(null)
    try {
      const splitPlan = await createDatasetSplitPlan(
        projectId,
        datasetVersionId,
        intent.holdoutCount,
        intent.idempotencyKey,
      )
      setDetail((current) =>
        current ? { ...current, split_plan: splitPlan, can_create_split_plan: false } : current,
      )
      splitPlanIntent.current = null
      setSplitPlanState('idle')
      void message.success('数据划分已固定到当前草稿。')
    } catch (error) {
      setSplitPlanState('error')
      if (error instanceof CatalogApiError) {
        setSplitPlanErrorCode(error.errorCode)
        setSplitPlanErrorMessage(error.message)
        setSplitPlanRequestId(error.requestId)
      } else {
        setSplitPlanErrorCode('DATASET_SPLIT_PLAN_FAILED')
        setSplitPlanErrorMessage('暂时无法生成数据划分，请稍后重试。')
      }
    }
  }, [datasetVersionId, holdoutCount, message, projectId])

  const confirmSplit = useCallback(() => {
    modal.confirm({
      title: '固定当前数据划分？',
      content: `将 ${holdoutCount} 组设为留出组，其余设为试用组。划分在当前草稿内不可修改；如需其他划分，应新建草稿。`,
      okText: '固定划分',
      cancelText: '取消',
      onOk: createSplit,
    })
  }, [createSplit, holdoutCount, modal])

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
              <p>以下证据只反映创建草稿时的事实；数据划分可单独固定，冻结和导出仍须后续验证。</p>
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
          <Card className="detail-card" title={<strong>数据划分</strong>} variant="outlined">
            <p className="form-hint">
              每条配药操作只会进入一个组，避免同一操作同时出现在试用组和留出组；结果只属于当前草稿。
            </p>
            {detail.split_plan ? (
              <SplitPlanEvidence projectId={projectId} splitPlan={detail.split_plan} />
            ) : detail.members.length < 2 ? (
              <p>当前只有一条候选配药操作，暂时无法同时建立试用组和留出组。</p>
            ) : detail.can_create_split_plan ? (
              <div className="dataset-draft-selection-toolbar">
                <div>
                  <strong>固定当前划分</strong>
                  <span>至少保留一组作为试用组；固定后如需调整，应新建数据集草稿。</span>
                </div>
                <div className="dataset-draft-selection-actions">
                  <label htmlFor="dataset-holdout-count">留出组数</label>
                  <InputNumber
                    id="dataset-holdout-count"
                    min={1}
                    max={detail.members.length - 1}
                    precision={0}
                    value={holdoutCount}
                    onChange={(value) => setHoldoutCount(value ?? 1)}
                  />
                  <Button
                    type="primary"
                    loading={splitPlanState === 'creating'}
                    onClick={confirmSplit}
                  >
                    固定划分
                  </Button>
                </div>
              </div>
            ) : (
              <p>尚未建立数据划分，请由项目管理员完成。</p>
            )}
            {splitPlanState === 'error' ? (
              <div className="catalog-error" role="alert">
                <strong>{splitPlanErrorCode}</strong>
                <span>{splitPlanErrorMessage}</span>
                {splitPlanRequestId ? <small>请求标识：{splitPlanRequestId}</small> : null}
              </div>
            ) : null}
          </Card>
          <Card
            className="detail-card"
            extra={
              <Button loading={currentAdmissionState === 'loading'} onClick={() => void checkCurrentAdmission()}>
                检查构建准备
              </Button>
            }
            title={<strong>构建准备</strong>}
            variant="outlined"
          >
            <p className="form-hint">
              只读取当前事实并核对草稿条件，不修改候选快照。通过本检查也不代表已经冻结或可以导出。
            </p>
            {currentAdmissionState === 'idle' ? <p>尚未检查构建准备。</p> : null}
            {currentAdmissionState === 'loading' ? (
              <div className="catalog-notice" role="status" aria-live="polite">
                正在检查构建准备…
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

function SplitPlanEvidence({
  projectId,
  splitPlan,
}: {
  projectId: string
  splitPlan: NonNullable<DatasetVersionDetail['split_plan']>
}) {
  return (
    <>
      <Descriptions
        column={{ xs: 1, sm: 2, lg: 4 }}
        items={[
          { key: 'pilot', label: '试用组', children: `${splitPlan.pilot_count} 组` },
          { key: 'holdout', label: '留出组', children: `${splitPlan.holdout_count} 组` },
          { key: 'seed', label: '划分种子', children: splitPlan.split_seed },
          { key: 'creator', label: '固定人', children: splitPlan.created_by },
        ]}
      />
      <Table<DatasetSplitAssignment>
        dataSource={splitPlan.assignments}
        rowKey="capture_session_id"
        pagination={false}
        size="small"
        columns={[
          {
            title: '候选顺序',
            width: 110,
            render: (_, row) => row.candidate_index + 1,
          },
          {
            title: '配药操作',
            render: (_, row) => (
              <Link className="text-link table-action-link" to={paths.session(projectId, row.capture_session_id)}>
                {shortId(row.capture_session_id)}
              </Link>
            ),
          },
          {
            title: '分组',
            width: 130,
            render: (_, row) => (
              <Tag color={row.split === 'HOLDOUT' ? 'gold' : 'blue'}>
                {row.split === 'HOLDOUT' ? '留出组' : '试用组'}
              </Tag>
            ),
          },
        ]}
      />
      <details className="technical-facts">
        <summary>查看划分标识</summary>
        <dl>
          <div>
            <dt>策略版本</dt>
            <dd>
              <code>{splitPlan.policy_version}</code>
            </dd>
          </div>
          <div>
            <dt>SplitPlan ID</dt>
            <dd>
              <code>{splitPlan.dataset_split_plan_id}</code>
            </dd>
          </div>
        </dl>
      </details>
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
      <div className={`readiness-result${admission.build_readiness.ready_for_build ? ' readiness-result-ready' : ''}`}>
        <div>
          <strong>{admission.build_readiness.ready_for_build ? '已具备开始构建条件' : '尚未具备开始构建条件'}</strong>
          <span>
            {admission.build_readiness.ready_for_build
              ? '草稿配置与当前证据已通过构建前检查。'
              : `还有 ${admission.build_readiness.blocking_reasons.length} 项需要处理。`}
          </span>
          {admission.build_readiness.blocking_reasons.length > 0 ? (
            <ul>
              {admission.build_readiness.blocking_reasons.map((reason) => (
                <li key={reason}>{buildBlockerLabels[reason]}</li>
              ))}
            </ul>
          ) : null}
        </div>
      </div>
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
