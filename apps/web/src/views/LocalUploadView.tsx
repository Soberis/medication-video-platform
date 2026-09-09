import { Alert, Button, Progress, Select } from 'antd'
import { RefreshCw, UploadCloud, Video } from 'lucide-react'
import { useCallback, useEffect, useRef, useState, type ChangeEvent } from 'react'
import { Link, useSearchParams } from 'react-router'

import { CatalogApiError } from '@/api/catalog'
import type { LocalUploadPhase } from '@/api/local-upload'
import { listProjects, type DataProject } from '@/api/projects'
import {
  useLocalUpload,
  type LocalUploadState,
  type LocalUploadTask,
  type LocalUploadTaskStatus,
} from '@/app/local-upload'
import { paths } from '@/app/paths'
import { PageHeader } from '@/components/PageHeader'
import { MetadataList } from '@/components/data/MetadataList'
import { StatusBadge, type StatusBadgeTone } from '@/components/StatusBadge'
import { EmptyState } from '@/components/WorkspaceFeedback'
import { Section } from '@/components/workspace/Section'
import { formatBytes } from '@/features/catalog/catalog-presentation'

const phaseLabels: Record<LocalUploadPhase, string> = {
  HASHING: '正在计算摘要',
  INITIALIZING: '正在创建上传批次',
  REGISTERING: '正在登记视频',
  UPLOADING: '正在上传视频分片',
  VERIFYING: '正在校验视频',
  ASSEMBLING: '正在建立采集记录',
  COMPLETED: '上传已完成',
}
function taskPercent(task: LocalUploadTask) {
  const progress = task.progress
  if (!progress || progress.totalBytes < 1) return 0
  return Math.round(
    ((progress.phase === 'HASHING' ? progress.processedBytes : progress.uploadedBytes) /
      progress.totalBytes) *
      100,
  )
}
function taskDescription(task: LocalUploadTask) {
  if (task.status === 'QUEUED') return '等待前一个视频处理完成。'
  if (task.status === 'FAILED') {
    const message = task.errorMessage ?? '上传未完成，请检查网络后重试。'
    return message.includes('重新选择') ? message : `${message} 请重新选择该文件后开始新一轮上传。`
  }
  if (task.status === 'COMPLETED') {
    return task.captureSessionId ? '上传已完成，已建立采集记录；可查看视频。' : '上传已完成。'
  }
  return task.progress ? phaseLabels[task.progress.phase] : '正在准备上传。'
}

function taskStatusLabel(task: LocalUploadTask) {
  if (task.status === 'QUEUED') return '等待处理'
  if (task.status === 'FAILED') return '处理失败'
  if (task.status === 'COMPLETED') return '已确认完成'
  return task.progress ? phaseLabels[task.progress.phase] : '准备中'
}

function taskProgressLabel(task: LocalUploadTask) {
  if (task.status === 'QUEUED') return '等待开始'
  if (task.status === 'FAILED') return '本轮未完成'
  if (task.status === 'COMPLETED') return '平台已确认完成'
  const progress = task.progress
  if (!progress) return '准备中'
  if (progress.phase === 'HASHING') return `摘要已处理 ${taskPercent(task)}%`
  if (progress.phase === 'UPLOADING') return `已传输 ${taskPercent(task)}%`
  if (progress.phase === 'VERIFYING') return '上传完成，平台校验中'
  if (progress.phase === 'ASSEMBLING') return '校验通过，正在建立采集记录'
  return phaseLabels[progress.phase]
}

function uploadTaskTone(status: LocalUploadTaskStatus): 'error' | 'processing' | 'success' {
  if (status === 'FAILED') return 'error'
  return status === 'COMPLETED' ? 'success' : 'processing'
}

function uploadResultDescription(
  uploadState: LocalUploadState,
  completedTaskCount: number,
  failedTaskCount: number,
  totalTaskCount: number,
) {
  if (uploadState === 'uploading') {
    return `平台已确认 ${completedTaskCount} / ${totalTaskCount} 个视频；其余仍在处理。`
  }
  if (failedTaskCount === 0) {
    return '所有视频已确认，采集记录已建立；可查看视频。'
  }
  if (completedTaskCount === 0) {
    return '本轮没有确认成功的视频；请重新选择后上传。'
  }
  return `平台已确认 ${completedTaskCount} 个视频，${failedTaskCount} 个需重新选择后上传。`
}

export default function LocalUploadView() {
  const [searchParams] = useSearchParams()
  const initialProjectId = searchParams.get('projectId') ?? ''
  const [projects, setProjects] = useState<DataProject[]>([])
  const [selectedProjectId, setSelectedProjectId] = useState(initialProjectId)
  const [selectedFiles, setSelectedFiles] = useState<File[]>([])
  const {
    tasks,
    uploadState,
    resetUpload,
    startUpload: startPlatformLocalUpload,
  } = useLocalUpload()
  const [projectLoadState, setProjectLoadState] = useState<'loading' | 'success' | 'error'>(
    'loading',
  )
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [isDragging, setIsDragging] = useState(false)
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const projectRequest = useRef<AbortController | null>(null)
  const isUploading = uploadState === 'uploading'
  const canStart =
    !isUploading &&
    tasks.length === 0 &&
    projectLoadState === 'success' &&
    selectedProjectId.length > 0 &&
    selectedFiles.length > 0
  const completedTaskCount = tasks.filter((task) => task.status === 'COMPLETED').length
  const failedTaskCount = tasks.filter((task) => task.status === 'FAILED').length
  const uploadSummary: { color: StatusBadgeTone; label: string } | null =
    uploadState === 'idle'
      ? null
      : uploadState === 'uploading'
        ? { label: '处理中', color: 'processing' }
        : failedTaskCount === 0
          ? { label: '全部上传完成', color: 'success' }
          : completedTaskCount === 0
            ? { label: '本轮上传失败', color: 'error' }
            : { label: '部分上传完成', color: 'warning' }

  const loadProjects = useCallback(async () => {
    projectRequest.current?.abort()
    const request = new AbortController()
    projectRequest.current = request
    setProjectLoadState('loading')
    setErrorCode('')
    setErrorMessage('')
    try {
      const response = await listProjects(request.signal)
      if (projectRequest.current !== request) return
      setProjects(response)
      setSelectedProjectId((current) => {
        const candidate = current || initialProjectId
        return response.some((project) => project.project_id === candidate) ? candidate : ''
      })
      setProjectLoadState('success')
    } catch (error) {
      if (request.signal.aborted || projectRequest.current !== request) return
      setProjects([])
      setSelectedProjectId('')
      setProjectLoadState('error')
      setErrorCode(error instanceof CatalogApiError ? error.errorCode : 'PROJECT_REQUEST_FAILED')
      setErrorMessage(
        error instanceof CatalogApiError ? error.message : '暂时无法读取项目，请稍后重试。',
      )
    } finally {
      if (projectRequest.current === request) projectRequest.current = null
    }
  }, [initialProjectId])
  useEffect(() => {
    const timer = window.setTimeout(() => void loadProjects(), 0)
    return () => {
      window.clearTimeout(timer)
      projectRequest.current?.abort()
    }
  }, [loadProjects])

  function selectProject(projectId: string) {
    setSelectedProjectId(projectId)
    setSelectedFiles([])
    resetUpload()
    setErrorCode('')
    setErrorMessage('')
  }

  function selectFiles(event: ChangeEvent<HTMLInputElement>) {
    setSelectedFiles(Array.from(event.target.files ?? []))
    event.target.value = ''
    resetUpload()
    setErrorCode('')
    setErrorMessage('')
  }

  function handleDrop(event: React.DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setIsDragging(false)
    if (!selectedProjectId || isUploading) return
    const files = Array.from(event.dataTransfer.files ?? [])
    if (files.length === 0) return
    const videoFiles = files.filter(
      (file) => file.type.startsWith('video/') || /\.(mp4|mov|avi|mkv|webm)$/i.test(file.name),
    )
    setSelectedFiles(videoFiles.length > 0 ? videoFiles : files)
    resetUpload()
    setErrorCode('')
    setErrorMessage('')
  }
  function startUpload() {
    if (!canStart) return
    const files = [...selectedFiles]
    const projectId = selectedProjectId
    startPlatformLocalUpload(projectId, files)
    setSelectedFiles([])
  }

  return (
    <>
      <PageHeader
        titleId="local-upload-title"
        title="上传视频"
        description="选择项目和视频，开始上传。"
        actions={
          <Button
            icon={<RefreshCw aria-hidden="true" size={14} strokeWidth={1.8} />}
            loading={projectLoadState === 'loading'}
            disabled={isUploading}
            onClick={() => void loadProjects()}
          >
            刷新项目
          </Button>
        }
      />
      {errorMessage ? (
        <Alert
          className="catalog-alert"
          type="error"
          title={errorMessage}
          description={errorCode ? `错误码：${errorCode}` : undefined}
          showIcon
        />
      ) : null}
      {projectLoadState !== 'success' || projects.length > 0 ? (
        <Section
          className="local-upload-card form-surface working-surface"
          title="选择项目和视频"
          titleId="local-upload-preparation-title"
        >
          <form
            className="local-upload-form"
            onSubmit={(event) => {
              event.preventDefault()
              void startUpload()
            }}
          >
            <div className="local-upload-field">
              <label htmlFor="local-upload-project">所属项目</label>
              <Select
                id="local-upload-project"
                value={selectedProjectId || undefined}
                loading={projectLoadState === 'loading'}
                disabled={isUploading || projectLoadState !== 'success'}
                placeholder="选择已加入的项目"
                onChange={selectProject}
                options={projects.map((project) => ({
                  value: project.project_id,
                  label: `${project.display_name}（${project.current_role === 'ADMIN' ? '管理员' : '成员'}）`,
                }))}
              />
            </div>
            <div className="local-upload-field local-upload-files-field">
              <label htmlFor="local-upload-files">视频文件</label>
              <div
                className={`local-upload-dropzone ${isDragging ? 'is-dragging' : ''} ${!selectedProjectId || isUploading ? 'is-disabled' : ''}`}
                onDragOver={(event) => {
                  event.preventDefault()
                  if (selectedProjectId && !isUploading) setIsDragging(true)
                }}
                onDragLeave={(event) => {
                  event.preventDefault()
                  setIsDragging(false)
                }}
                onDrop={handleDrop}
                onClick={() => {
                  if (selectedProjectId && !isUploading) {
                    fileInputRef.current?.click()
                  }
                }}
              >
                <input
                  ref={fileInputRef}
                  id="local-upload-files"
                  className="local-file-input visually-hidden-input"
                  type="file"
                  accept="video/*"
                  multiple
                  aria-describedby={`local-upload-files-hint${selectedFiles.length > 0 ? ' local-upload-selected-files' : ''}`}
                  disabled={isUploading || !selectedProjectId}
                  onChange={selectFiles}
                />
                <div className="dropzone-inner">
                  <div className="dropzone-icon-box">
                    <UploadCloud aria-hidden="true" size={22} strokeWidth={1.8} />
                  </div>
                  <div className="dropzone-text">
                    <span className="dropzone-primary-action">
                      {selectedFiles.length > 0
                        ? `已选择 ${selectedFiles.length} 个视频文件（点击或拖拽更换）`
                        : '点击选择视频，或将视频文件拖拽至此处'}
                    </span>
                    <span className="dropzone-hint-text">
                      {selectedProjectId
                        ? '支持 MP4、MOV、WebM 等常见视频格式，支持多文件批量选择'
                        : '请先在上方选择所属项目'}
                    </span>
                  </div>
                </div>
              </div>
              <p id="local-upload-files-hint" className="form-hint">
                {selectedProjectId ? '可选择多个视频，平台会分别上传。' : '请先选择项目。'}
              </p>
            </div>
            {selectedFiles.length > 0 && tasks.length === 0 ? (
              <div className="selected-file-selection">
                <p id="local-upload-selected-files" className="selected-file-count" role="status">
                  已选择 {selectedFiles.length} 个视频
                </p>
                <ul className="selected-file-list" aria-label="已选视频">
                  {selectedFiles.map((file) => (
                    <li
                      key={`${file.name}:${file.size}:${file.lastModified}`}
                      className="selected-file-item"
                    >
                      <div className="selected-file-main">
                        <Video
                          aria-hidden="true"
                          size={15}
                          strokeWidth={1.8}
                          className="selected-file-icon"
                        />
                        <strong>{file.name}</strong>
                      </div>
                      <span className="selected-file-meta">
                        {file.type || '视频'} · {formatBytes(file.size)}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
            <div className="local-upload-actions">
              <Button type="primary" htmlType="submit" disabled={!canStart} loading={isUploading}>
                {isUploading
                  ? '正在上传…'
                  : uploadState === 'completed'
                    ? '重新选择视频后上传'
                    : `开始上传${selectedFiles.length > 1 ? `（${selectedFiles.length} 个视频）` : ''}`}
              </Button>
            </div>
          </form>
        </Section>
      ) : null}
      {tasks.length > 0 ? (
        <section
          className="local-upload-results"
          aria-live="polite"
          aria-labelledby="local-upload-progress-title"
        >
          <div className="card-header-row">
            <div>
              <p className="app-eyebrow">上传进度</p>
              <h2 id="local-upload-progress-title">
                平台已确认 {completedTaskCount} / {tasks.length} 个视频
              </h2>
            </div>
            {uploadSummary ? (
              <StatusBadge tone={uploadSummary.color}>{uploadSummary.label}</StatusBadge>
            ) : null}
          </div>
          <p className="local-upload-results-summary" role="status">
            {uploadResultDescription(
              uploadState,
              completedTaskCount,
              failedTaskCount,
              tasks.length,
            )}
          </p>
          <p className="section-description">进度仅表示已传输字节；完成以平台校验结果为准。</p>
          {tasks.map((task) => (
            <article
              key={task.id}
              className="local-upload-task"
              aria-labelledby={`local-upload-task-${task.id}-title`}
            >
              <div className="file-heading">
                <div>
                  <p className="file-kind">
                    {task.status === 'COMPLETED'
                      ? '平台已确认'
                      : task.status === 'FAILED'
                        ? '需要处理'
                        : '处理中'}
                  </p>
                  <h3 id={`local-upload-task-${task.id}-title`}>{task.file.name}</h3>
                </div>
                <StatusBadge tone={uploadTaskTone(task.status)}>
                  {taskStatusLabel(task)}
                </StatusBadge>
              </div>
              <div
                className="local-upload-task-progress"
                role="group"
                aria-label={`${task.file.name} 的上传进度`}
              >
                <Progress
                  percent={taskPercent(task)}
                  format={() => taskProgressLabel(task)}
                  status={
                    task.status === 'FAILED'
                      ? 'exception'
                      : task.status === 'COMPLETED'
                        ? 'success'
                        : 'normal'
                  }
                />
              </div>
              <p className="task-description">{taskDescription(task)}</p>
              {task.progress?.batchId || task.captureSessionId ? (
                <details className="upload-technical-details">
                  <summary>技术信息</summary>
                  <MetadataList
                    items={[
                      ...(task.progress?.batchId
                        ? [
                            {
                              id: 'batch-id',
                              label: '上传批次 ID',
                              value: <code>{task.progress.batchId}</code>,
                            },
                          ]
                        : []),
                      ...(task.captureSessionId
                        ? [
                            {
                              id: 'capture-session-id',
                              label: '采集记录 ID',
                              value: <code>{task.captureSessionId}</code>,
                            },
                          ]
                        : []),
                    ]}
                  />
                </details>
              ) : null}
              {task.captureSessionId || (task.status === 'FAILED' && task.progress?.batchId) ? (
                <div className="local-upload-task-actions">
                  {task.captureSessionId ? (
                    <Link
                      className="text-link"
                      to={paths.captureGroup(task.projectId, task.captureSessionId)}
                    >
                      打开视频
                    </Link>
                  ) : null}
                  {task.status === 'FAILED' && task.progress?.batchId ? (
                    <Link
                      className="text-link"
                      to={`${paths.uploads}?batchId=${encodeURIComponent(task.progress.batchId)}`}
                    >
                      查看上传状态与异常
                    </Link>
                  ) : null}
                </div>
              ) : null}
              {task.errorCode ? (
                <p className="task-error" role="alert">
                  <strong>{task.errorCode}</strong>
                  <span>{task.errorMessage}</span>
                </p>
              ) : null}
            </article>
          ))}
        </section>
      ) : null}
      {projectLoadState === 'success' && projects.length === 0 ? (
        <EmptyState description="当前账号尚未加入项目，无法上传视频。请联系管理员添加项目成员。" />
      ) : null}
    </>
  )
}
