import { useEffect, useRef, useState } from 'react'

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
import { PageHeader } from '@/components/PageHeader'
import { MetadataList } from '@/components/data/MetadataList'
import { StatusBadge, type StatusBadgeTone } from '@/components/StatusBadge'
import { ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { formatBytes } from '@/features/catalog/catalog-presentation'

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const batchStatusLabels: Record<UploadBatchStatus, string> = {
  INITIATED: '已初始化',
  UPLOADING: '上传中',
  COMPLETING: '完成中',
  COMPLETED: '已完成',
  FAILED: '失败',
  ABORTED: '已中止',
  EXPIRED: '已过期',
}
const fileStatusLabels: Record<UploadFileStatus, string> = {
  REGISTERED: '已登记',
  SESSION_CREATED: '会话已创建',
  UPLOADING: '上传中',
  COMPLETING: '校验中',
  UPLOADED: '已上传并校验',
  FAILED: '校验失败',
}
const fileKindLabels: Record<UploadFileKind, string> = {
  VIDEO: '视频',
  METADATA: '元数据',
  EVENT_LOG: '事件日志',
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

const percent = (value: number, total: number) =>
  total <= 0 ? 0 : Math.min(100, Math.max(0, Math.round((value / total) * 100)))
const formatTime = (value: string | null) =>
  value === null
    ? '—'
    : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' }).format(
        new Date(value),
      )

export default function UploadProgressView() {
  const [batchId, setBatchId] = useState('')
  const [batch, setBatch] = useState<UploadBatchProgress | null>(null)
  const [files, setFiles] = useState<UploadFileProgress[]>([])
  const [loadState, setLoadState] = useState<'idle' | 'loading' | 'success' | 'error'>('idle')
  const [errorMessage, setErrorMessage] = useState('')
  const [errorCode, setErrorCode] = useState('')
  const activeRequest = useRef<AbortController | null>(null)
  const isLoading = loadState === 'loading'
  const hasResult = batch !== null && loadState === 'success'
  const batchBytePercent = percent(batch?.uploaded_size_bytes ?? 0, batch?.total_size_bytes ?? 0)

  useEffect(() => () => activeRequest.current?.abort(), [])

  async function loadProgress() {
    const normalizedBatchId = batchId.trim()
    if (!uuidPattern.test(normalizedBatchId)) {
      setBatch(null)
      setFiles([])
      setLoadState('error')
      setErrorCode('INVALID_BATCH_ID')
      setErrorMessage('请输入有效的上传批次 UUID。')
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
        setErrorMessage('无法连接上传进度服务，请稍后重试。')
      }
    } finally {
      if (activeRequest.current === request) activeRequest.current = null
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="上传状态查询"
        titleId="progress-title"
        title="查看批次上传进度"
        description="只读取平台已确认的状态，不会上传文件或改变批次。"
      />
      <section className="upload-progress-query working-surface" aria-labelledby="progress-title">
        <form
          className="batch-form"
          onSubmit={(event) => {
            event.preventDefault()
            void loadProgress()
          }}
        >
          <label htmlFor="batch-id">上传批次 ID</label>
          <div className="batch-form-row">
            <input
              id="batch-id"
              value={batchId}
              onChange={(event) => setBatchId(event.target.value)}
              name="batch-id"
              type="text"
              inputMode="text"
              autoComplete="off"
              spellCheck={false}
              placeholder="例如：00000000-0000-4000-8000-000000000001"
              aria-invalid={loadState === 'error'}
            />
            <button type="submit" disabled={isLoading}>
              {isLoading ? '查询中…' : hasResult ? '刷新进度' : '查询进度'}
            </button>
          </div>
        </form>
      </section>
      {isLoading ? <LoadingState>正在读取批次和文件进度…</LoadingState> : null}
      {loadState === 'error' ? <ErrorState code={errorCode}>{errorMessage}</ErrorState> : null}
      {batch && hasResult ? (
        <>
          <section className="summary-card" aria-labelledby="batch-summary-title">
            <div className="summary-heading">
              <div>
                <p className="status-label">批次汇总</p>
                <h2 id="batch-summary-title">{batch.device_id}</h2>
              </div>
              <StatusBadge tone={batchStatusTone(batch.status)}>
                {batchStatusLabels[batch.status]}
              </StatusBadge>
            </div>
            <div className="progress-block">
              <div className="progress-copy">
                <span>已确认字节</span>
                <strong>{batchBytePercent}%</strong>
              </div>
              <progress value={batch.uploaded_size_bytes} max={batch.total_size_bytes}>
                {batchBytePercent}%
              </progress>
              <p>
                {formatBytes(batch.uploaded_size_bytes)} / {formatBytes(batch.total_size_bytes)}
              </p>
            </div>
            <MetadataList
              className="metric-grid"
              items={[
                {
                  id: 'registered-files',
                  label: '文件登记',
                  value: `${batch.registered_file_count} / ${batch.file_count}`,
                },
                {
                  id: 'uploaded-files',
                  label: '已完成文件',
                  value: batch.uploaded_file_count,
                },
                {
                  id: 'failed-files',
                  label: '失败文件',
                  value: batch.failed_file_count,
                },
                {
                  id: 'uploaded-parts',
                  label: '已确认分片',
                  value: `${batch.uploaded_part_count} / ${batch.tracked_part_count}`,
                },
              ]}
            />
            <p className="batch-meta">
              批次 ID：<code>{batch.batch_id}</code>
            </p>
            <p className="batch-meta">有效期至：{formatTime(batch.expires_at)}</p>
          </section>
          <section className="files-section" aria-labelledby="files-title">
            <div className="files-heading">
              <div>
                <p className="status-label">文件进度</p>
                <h2 id="files-title">已登记文件</h2>
              </div>
              <span>{files.length} 个</span>
            </div>
            {files.length === 0 ? <p className="empty-state">该批次尚未登记文件。</p> : null}
            {files.map((file) => (
              <article key={file.upload_file_id} className="file-card">
                <div className="file-heading">
                  <div>
                    <p className="file-kind">
                      {fileKindLabels[file.file_kind]} · {file.content_type}
                    </p>
                    <h3>{file.client_file_id}</h3>
                  </div>
                  <StatusBadge tone={fileStatusTone(file.status)}>
                    {fileStatusLabels[file.status]}
                  </StatusBadge>
                </div>
                <div className="progress-block progress-compact">
                  <div className="progress-copy">
                    <span>分片进度</span>
                    <strong>{percent(file.uploaded_parts, file.total_parts)}%</strong>
                  </div>
                  <progress value={file.uploaded_parts} max={file.total_parts}>
                    {percent(file.uploaded_parts, file.total_parts)}%
                  </progress>
                  <p>
                    已确认 {file.uploaded_parts} / {file.total_parts} 个分片，
                    {formatBytes(file.uploaded_size_bytes)} / {formatBytes(file.size_bytes)}
                  </p>
                </div>
                {file.completion_error_code ? (
                  <div className="file-error" role="status">
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
                      value: formatTime(file.updated_at),
                    },
                    {
                      id: 'completed-at',
                      label: '完成时间',
                      value: formatTime(file.completed_at),
                    },
                  ]}
                />
              </article>
            ))}
          </section>
        </>
      ) : null}
    </>
  )
}
