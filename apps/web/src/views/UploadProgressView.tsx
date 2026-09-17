import { Button, Input } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'

import {
  getUploadBatchProgress,
  listUploadFileProgress,
  UploadApiError,
  type UploadBatchProgress,
  type UploadBatchStatus,
  type UploadFileKind,
  type UploadFileProgress,
  type UploadFileStatus,
} from '@/api/uploads'
import { paths } from '@/app/paths'
import { PageHeader } from '@/components/PageHeader'
import { MetadataList } from '@/components/data/MetadataList'
import { StatusBadge, type StatusBadgeTone } from '@/components/StatusBadge'
import { EmptyState, ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { Section } from '@/components/workspace/Section'
import { SummaryStrip } from '@/components/SummaryStrip'
import { formatBytes, formatDateTime, shortId } from '@/features/catalog/catalog-presentation'

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const batchStatusLabels: Record<UploadBatchStatus, string> = {
  INITIATED: '待上传',
  UPLOADING: '上传中',
  COMPLETING: '校验中',
  COMPLETED: '已完成',
  FAILED: '处理失败',
  ABORTED: '已中止',
  EXPIRED: '已过期',
}
const fileStatusLabels: Record<UploadFileStatus, string> = {
  REGISTERED: '已登记',
  SESSION_CREATED: '准备上传',
  UPLOADING: '上传中',
  COMPLETING: '校验中',
  UPLOADED: '已确认',
  FAILED: '校验失败',
}
const fileKindLabels: Record<UploadFileKind, string> = {
  VIDEO: '视频文件',
  METADATA: '元数据',
  EVENT_LOG: '事件日志',
}
function uploadDeviceDisplayLabel(deviceId: string) {
  return deviceId === 'PLATFORM_LOCAL' ? '上传视频' : deviceId
}

function batchStatusTone(status: UploadBatchStatus): StatusBadgeTone {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED' || status === 'ABORTED' || status === 'EXPIRED') return 'error'
  return 'processing'
}

function fileStatusTone(status: UploadFileStatus): StatusBadgeTone {
  if (status === 'UPLOADED') return 'success'
  if (status === 'FAILED') return 'error'
  return 'processing'
}

function batchStatusDescription(status: UploadBatchStatus) {
  if (status === 'INITIATED') return '批次已创建，等待上传。'
  if (status === 'UPLOADING') return '视频正在上传，等待平台确认。'
  if (status === 'COMPLETING') return '上传完成，平台正在校验。'
  if (status === 'COMPLETED') return '批次已完成，视频已确认。'
  if (status === 'FAILED') return '批次处理失败，请查看文件错误。'
  if (status === 'ABORTED') return '批次已中止，不能继续上传。'
  return '批次已过期，请重新上传。'
}

function fileStatusDescription(file: UploadFileProgress) {
  if (file.status === 'REGISTERED') return '文件已登记，等待上传。'
  if (file.status === 'SESSION_CREATED') return '已准备上传，等待传输。'
  if (file.status === 'UPLOADING') return '正在上传，进度以平台确认为准。'
  if (file.status === 'COMPLETING') return '正在校验文件。'
  if (file.status === 'UPLOADED') return '文件已确认，批次状态以批次为准。'
  return '文件校验失败，请查看错误码。'
}

function fileErrorDescription(errorCode: string) {
  if (errorCode === 'UPLOAD_FILE_SHA256_MISMATCH') {
    return '文件摘要不一致，请重新选择原文件。'
  }
  return '文件完整性未确认，请根据错误码处理或重新上传。'
}

const percent = (value: number, total: number) =>
  total <= 0 ? 0 : Math.min(100, Math.max(0, Math.round((value / total) * 100)))

export default function UploadProgressView() {
  const [searchParams] = useSearchParams()
  const queryBatchId = searchParams.get('batchId')?.trim() ?? ''
  const [batchId, setBatchId] = useState(queryBatchId)
  const [batch, setBatch] = useState<UploadBatchProgress | null>(null)
  const [files, setFiles] = useState<UploadFileProgress[]>([])
  const [loadState, setLoadState] = useState<'idle' | 'loading' | 'success' | 'error'>('idle')
  const [errorMessage, setErrorMessage] = useState('')
  const [errorCode, setErrorCode] = useState('')
  const activeRequest = useRef<AbortController | null>(null)
  const lastAutoLoadedBatchId = useRef('')
  const isLoading = loadState === 'loading'
  const hasResult = batch !== null && (loadState === 'success' || loadState === 'loading')
  const isRefreshing = isLoading && batch !== null
  const batchBytePercent = percent(batch?.uploaded_size_bytes ?? 0, batch?.total_size_bytes ?? 0)

  useEffect(() => () => activeRequest.current?.abort(), [])

  const loadProgress = useCallback(
    async (requestedBatchId = batchId) => {
      const normalizedBatchId = requestedBatchId.trim()
      if (!uuidPattern.test(normalizedBatchId)) {
        setBatch(null)
        setFiles([])
        setLoadState('error')
        setErrorCode('INVALID_BATCH_ID')
        setErrorMessage('请输入有效的上传批次 ID。')
        return
      }
      activeRequest.current?.abort()
      const request = new AbortController()
      activeRequest.current = request
      setLoadState('loading')
      setErrorMessage('')
      setErrorCode('')
      try {
        const [batchResponse, fileResponse] = await Promise.all([
          getUploadBatchProgress(normalizedBatchId, request.signal),
          listUploadFileProgress(normalizedBatchId, request.signal),
        ])
        if (activeRequest.current !== request) return
        setBatch(batchResponse)
        setFiles(fileResponse.files)
        setLoadState('success')
      } catch (error) {
        if (request.signal.aborted || activeRequest.current !== request) return
        setBatch(null)
        setFiles([])
        setLoadState('error')
        if (error instanceof UploadApiError) {
          setErrorCode(error.errorCode)
          setErrorMessage(error.message)
        } else {
          setErrorCode('UPLOAD_PROGRESS_REQUEST_FAILED')
          setErrorMessage('无法读取上传进度，请稍后重试。')
        }
      } finally {
        if (activeRequest.current === request) activeRequest.current = null
      }
    },
    [batchId],
  )

  useEffect(() => {
    if (queryBatchId.length === 0) {
      lastAutoLoadedBatchId.current = ''
      return
    }
    if (lastAutoLoadedBatchId.current === queryBatchId) return
    lastAutoLoadedBatchId.current = queryBatchId
    setBatchId(queryBatchId)
    void loadProgress(queryBatchId)
  }, [loadProgress, queryBatchId])

  return (
    <>
      <PageHeader
        titleId="progress-title"
        eyebrow="上传"
        title="上传状态"
        description="查看上传批次和文件状态。"
      />
      <Section
        className="upload-progress-query working-surface"
        title="查询上传批次"
        titleId="progress-query-title"
      >
        <form
          className="batch-form"
          aria-busy={isLoading}
          onSubmit={(event) => {
            event.preventDefault()
            void loadProgress()
          }}
        >
          <label htmlFor="batch-id">上传批次 ID</label>
          <div className="batch-form-row">
            <Input
              id="batch-id"
              value={batchId}
              onChange={(event) => setBatchId(event.target.value)}
              name="batch-id"
              type="text"
              autoComplete="off"
              spellCheck={false}
              placeholder="例如：00000000-0000-4000-8000-000000000001"
              aria-invalid={loadState === 'error'}
              aria-describedby="batch-id-hint"
            />
            <Button htmlType="submit" type="primary" loading={isLoading} disabled={isLoading}>
              {isLoading ? '查询中…' : hasResult ? '刷新进度' : '查询进度'}
            </Button>
          </div>
          <p id="batch-id-hint" className="form-hint">
            输入上传批次 ID，只读取进度。
          </p>
        </form>
      </Section>
      {isLoading ? (
        <LoadingState>{isRefreshing ? '正在刷新进度…' : '正在读取进度…'}</LoadingState>
      ) : null}
      {loadState === 'error' ? (
        <ErrorState code={errorCode} onRetry={() => void loadProgress()}>
          {errorMessage}
        </ErrorState>
      ) : null}
      {batch && hasResult ? (
        <>
          <Section
            className="upload-progress-summary working-surface"
            title="批次状态"
            titleId="batch-summary-title"
            actions={
              <StatusBadge tone={batchStatusTone(batch.status)}>
                {batchStatusLabels[batch.status]}
              </StatusBadge>
            }
          >
            <p className="section-description">{batchStatusDescription(batch.status)}</p>
            <div className="progress-block" role="group" aria-label="批次传输进度">
              <div className="progress-copy">
                <span>已确认传输</span>
                <strong>{batchBytePercent}%</strong>
              </div>
              <progress
                aria-label="批次已确认传输进度"
                value={batch.uploaded_size_bytes}
                max={batch.total_size_bytes}
                aria-valuetext={`${batchBytePercent}%，${formatBytes(batch.uploaded_size_bytes)} / ${formatBytes(batch.total_size_bytes)}`}
              >
                {batchBytePercent}%
              </progress>
              <p>
                {formatBytes(batch.uploaded_size_bytes)} / {formatBytes(batch.total_size_bytes)}
              </p>
            </div>
            <SummaryStrip
              ariaLabel="上传批次汇总"
              items={[
                {
                  label: '已登记文件',
                  value: `${batch.registered_file_count} / ${batch.file_count}`,
                },
                {
                  label: '已确认文件',
                  value: batch.uploaded_file_count,
                },
                {
                  label: '需处理文件',
                  value: batch.failed_file_count,
                },
                {
                  label: '已确认分片',
                  value: `${batch.uploaded_part_count} / ${batch.tracked_part_count}`,
                },
              ]}
              footer={
                batch.status === 'COMPLETED' ? (
                  <Link className="text-link" to={paths.captureGroups(batch.project_id)}>
                    进入视频
                  </Link>
                ) : null
              }
            />
            <MetadataList
              className="upload-batch-context"
              items={[
                {
                  id: 'project-id',
                  label: '所属项目',
                  value: <code>{shortId(batch.project_id)}</code>,
                },
                {
                  id: 'device-id',
                  label: '来源设备',
                  value: uploadDeviceDisplayLabel(batch.device_id),
                },
              ]}
            />
            <details className="upload-technical-details">
              <summary>技术信息</summary>
              <MetadataList
                items={[
                  {
                    id: 'batch-id',
                    label: '上传批次 ID',
                    value: <code>{batch.batch_id}</code>,
                  },
                  ...(batch.device_id === 'PLATFORM_LOCAL'
                    ? [
                        {
                          id: 'device-id-technical',
                          label: '来源设备标识',
                          value: <code>{batch.device_id}</code>,
                        },
                      ]
                    : []),
                  { id: 'created-at', label: '创建时间', value: formatDateTime(batch.created_at) },
                  { id: 'expires-at', label: '有效期至', value: formatDateTime(batch.expires_at) },
                ]}
              />
            </details>
          </Section>
          <Section
            className="files-section"
            title={batch.failed_file_count > 0 ? '问题文件与进度' : '文件进度'}
            titleId="files-title"
            actions={<span className="upload-file-count">{files.length} 个</span>}
          >
            {files.length === 0 ? <EmptyState description="该批次尚未登记文件。" /> : null}
            {files.map((file) => (
              <article
                key={file.upload_file_id}
                className={`file-card${file.status === 'FAILED' ? ' file-card-failed' : ''}`}
                aria-labelledby={`upload-file-${file.upload_file_id}-title`}
              >
                <div className="file-heading">
                  <div>
                    <p className="file-kind">
                      {fileKindLabels[file.file_kind]} · {file.content_type}
                    </p>
                    <h3 id={`upload-file-${file.upload_file_id}-title`}>
                      {fileKindLabels[file.file_kind]}
                    </h3>
                    <p className="file-reference">
                      客户端标识：<code>{shortId(file.client_file_id)}</code>
                    </p>
                  </div>
                  <StatusBadge tone={fileStatusTone(file.status)}>
                    {fileStatusLabels[file.status]}
                  </StatusBadge>
                </div>
                <p className="section-description">{fileStatusDescription(file)}</p>
                <div
                  className="progress-block progress-compact"
                  role="group"
                  aria-label={`${fileKindLabels[file.file_kind]}的服务端确认进度`}
                >
                  <div className="progress-copy">
                    <span>服务端已确认分片</span>
                    <strong>{percent(file.uploaded_parts, file.total_parts)}%</strong>
                  </div>
                  <progress
                    aria-label={`文件 ${shortId(file.client_file_id)} 的已确认分片进度`}
                    value={file.uploaded_parts}
                    max={file.total_parts}
                    aria-valuetext={`${percent(file.uploaded_parts, file.total_parts)}%，已确认 ${file.uploaded_parts} / ${file.total_parts} 个分片`}
                  >
                    {percent(file.uploaded_parts, file.total_parts)}%
                  </progress>
                  <p>
                    已确认 {file.uploaded_parts} / {file.total_parts} 个分片，
                    {formatBytes(file.uploaded_size_bytes)} / {formatBytes(file.size_bytes)}
                  </p>
                </div>
                {file.completion_error_code ? (
                  <div className="file-error" role="alert">
                    <strong>需要处理</strong>
                    <span>{fileErrorDescription(file.completion_error_code)}</span>
                    <span>错误码</span>
                    <code>{file.completion_error_code}</code>
                  </div>
                ) : null}
                <MetadataList
                  className="file-meta-grid"
                  items={[
                    {
                      id: 'tracked-parts',
                      label: '已跟踪分片',
                      value: file.tracked_parts,
                    },
                    {
                      id: 'updated-at',
                      label: '最后更新',
                      value: formatDateTime(file.updated_at),
                    },
                    {
                      id: 'completed-at',
                      label: '完成时间',
                      value: formatDateTime(file.completed_at),
                    },
                  ]}
                />
                <details className="upload-technical-details">
                  <summary>技术信息</summary>
                  <MetadataList
                    items={[
                      {
                        id: 'upload-file-id',
                        label: '上传文件 ID',
                        value: <code>{file.upload_file_id}</code>,
                      },
                      {
                        id: 'client-file-id',
                        label: '客户端文件 ID',
                        value: <code>{file.client_file_id}</code>,
                      },
                      {
                        id: 'declared-sha256',
                        label: '声明摘要',
                        value: <code>{file.declared_sha256}</code>,
                      },
                      {
                        id: 'verified-sha256',
                        label: '已验证摘要',
                        value: <code>{file.verified_sha256 ?? '—'}</code>,
                      },
                      {
                        id: 'completion-started-at',
                        label: '校验开始',
                        value: formatDateTime(file.completion_started_at),
                      },
                    ]}
                  />
                </details>
                {batch.device_id === 'PLATFORM_LOCAL' && file.status === 'FAILED' ? (
                  <div className="upload-recovery-actions">
                    <strong>需要重新上传</strong>
                    <span>不支持续传，请重新选择原文件上传。</span>
                    <Link className="text-link" to={paths.localUpload}>
                      返回上传视频
                    </Link>
                  </div>
                ) : null}
              </article>
            ))}
          </Section>
        </>
      ) : null}
    </>
  )
}
