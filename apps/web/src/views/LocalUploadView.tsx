import { Alert, Button, Progress, Select } from 'antd'
import { useCallback, useEffect, useReducer, useRef, useState } from 'react'
import { Link } from 'react-router'

import { CatalogApiError } from '@/api/catalog'
import {
  LocalUploadApiError,
  type LocalUploadPhase,
  type LocalUploadProgress,
  uploadPlatformLocalVideo,
} from '@/api/local-upload'
import { listProjects, type DataProject } from '@/api/projects'
import { paths } from '@/app/paths'
import { PageHeader } from '@/components/PageHeader'
import { MetadataList } from '@/components/data/MetadataList'
import { StatusBadge, type StatusBadgeTone } from '@/components/StatusBadge'
import { EmptyState } from '@/components/WorkspaceFeedback'
import { Section } from '@/components/workspace/Section'
import { formatBytes } from '@/features/catalog/catalog-presentation'

type TaskStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
interface UploadTask {
  readonly id: number
  readonly file: File
  status: TaskStatus
  progress: LocalUploadProgress | null
  errorCode: string | null
  errorMessage: string | null
  captureSessionId: string | null
  clipAssetId: string | null
}
type TaskAction =
  | { type: 'initialize'; files: File[] }
  | { type: 'reset' }
  | { type: 'running'; id: number }
  | { type: 'progress'; id: number; progress: LocalUploadProgress }
  | { type: 'completed'; id: number; captureSessionId: string; clipAssetId: string | null }
  | { type: 'failed'; id: number; errorCode: string; errorMessage: string }
function taskReducer(tasks: UploadTask[], action: TaskAction): UploadTask[] {
  if (action.type === 'reset') return []
  if (action.type === 'initialize')
    return action.files.map((file, id) => ({
      id,
      file,
      status: 'QUEUED',
      progress: null,
      errorCode: null,
      errorMessage: null,
      captureSessionId: null,
      clipAssetId: null,
    }))
  return tasks.map((task) =>
    task.id !== action.id
      ? task
      : action.type === 'running'
        ? { ...task, status: 'RUNNING' }
        : action.type === 'progress'
          ? { ...task, progress: action.progress }
          : action.type === 'completed'
            ? {
                ...task,
                status: 'COMPLETED',
                captureSessionId: action.captureSessionId,
                clipAssetId: action.clipAssetId,
              }
            : {
                ...task,
                status: 'FAILED',
                errorCode: action.errorCode,
                errorMessage: action.errorMessage,
              },
  )
}
const phaseLabels: Record<LocalUploadPhase, string> = {
  HASHING: '正在计算文件摘要',
  INITIALIZING: '正在创建上传批次',
  REGISTERING: '正在登记视频文件',
  UPLOADING: '正在传输视频分片',
  VERIFYING: '平台正在校验文件',
  ASSEMBLING: '正在建立单机位配药事件记录',
  COMPLETED: '平台已确认上传完成',
}
function taskPercent(task: UploadTask) {
  const progress = task.progress
  if (!progress || progress.totalBytes < 1) return 0
  return Math.round(
    ((progress.phase === 'HASHING' ? progress.processedBytes : progress.uploadedBytes) /
      progress.totalBytes) *
      100,
  )
}
function taskDescription(task: UploadTask) {
  if (task.status === 'QUEUED') return '等待前一个视频处理完成。'
  if (task.status === 'FAILED') {
    const message = task.errorMessage ?? '本轮处理未完成，请检查网络和本地服务后重试。'
    return message.includes('重新选择') ? message : `${message} 请重新选择该文件后开始新一轮上传。`
  }
  if (task.status === 'COMPLETED') {
    return task.captureSessionId
      ? '平台已确认上传完成并建立配药事件；可直接进入事件工作台继续处理。'
      : '平台已确认上传完成，并建立配药事件。'
  }
  return task.progress ? phaseLabels[task.progress.phase] : '正在准备上传。'
}

function taskStatusLabel(task: UploadTask) {
  if (task.status === 'QUEUED') return '等待处理'
  if (task.status === 'FAILED') return '处理失败'
  if (task.status === 'COMPLETED') return '已确认完成'
  return task.progress ? phaseLabels[task.progress.phase] : '准备中'
}

function taskProgressLabel(task: UploadTask) {
  if (task.status === 'QUEUED') return '等待开始'
  if (task.status === 'FAILED') return '本轮未完成'
  if (task.status === 'COMPLETED') return '平台已确认完成'
  const progress = task.progress
  if (!progress) return '准备中'
  if (progress.phase === 'HASHING') return `摘要已处理 ${taskPercent(task)}%`
  if (progress.phase === 'UPLOADING') return `已传输 ${taskPercent(task)}%`
  if (progress.phase === 'VERIFYING') return '传输已完成，平台校验中'
  if (progress.phase === 'ASSEMBLING') return '校验通过，正在建立记录'
  return phaseLabels[progress.phase]
}

function uploadTaskTone(status: TaskStatus): 'error' | 'processing' | 'success' {
  if (status === 'FAILED') return 'error'
  return status === 'COMPLETED' ? 'success' : 'processing'
}

function uploadResultDescription(
  uploadState: 'idle' | 'uploading' | 'completed',
  completedTaskCount: number,
  failedTaskCount: number,
  totalTaskCount: number,
) {
  if (uploadState === 'uploading') {
    return `平台已确认 ${completedTaskCount} / ${totalTaskCount} 个视频；其余文件仍在处理。`
  }
  if (failedTaskCount === 0) {
    return '所有视频都已由平台完成校验并确认；现在可以进入视频管理继续处理。'
  }
  if (completedTaskCount === 0) {
    return '本轮没有视频得到平台确认；请根据每个文件的原因重新选择后再上传。'
  }
  return `平台已确认 ${completedTaskCount} 个视频，${failedTaskCount} 个文件需要重新选择后再上传。`
}

export default function LocalUploadView() {
  const [projects, setProjects] = useState<DataProject[]>([])
  const [selectedProjectId, setSelectedProjectId] = useState('')
  const [selectedFiles, setSelectedFiles] = useState<File[]>([])
  const [tasks, dispatchTasks] = useReducer(taskReducer, [])
  const [projectLoadState, setProjectLoadState] = useState<'loading' | 'success' | 'error'>(
    'loading',
  )
  const [uploadState, setUploadState] = useState<'idle' | 'uploading' | 'completed'>('idle')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const projectRequest = useRef<AbortController | null>(null)
  const uploadRequest = useRef<AbortController | null>(null)
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
      setSelectedProjectId((current) =>
        response.some((project) => project.project_id === current) ? current : '',
      )
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
  }, [])
  useEffect(() => {
    const timer = window.setTimeout(() => void loadProjects(), 0)
    return () => {
      window.clearTimeout(timer)
      projectRequest.current?.abort()
      uploadRequest.current?.abort()
    }
  }, [loadProjects])
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setSelectedFiles([])
      dispatchTasks({ type: 'reset' })
      setUploadState('idle')
      setErrorCode('')
      setErrorMessage('')
    }, 0)
    return () => window.clearTimeout(timer)
  }, [selectedProjectId])

  function selectFiles(event: React.ChangeEvent<HTMLInputElement>) {
    setSelectedFiles(Array.from(event.target.files ?? []))
    event.target.value = ''
    dispatchTasks({ type: 'reset' })
    setUploadState('idle')
    setErrorCode('')
    setErrorMessage('')
  }
  async function startUpload() {
    if (!canStart) return
    const files = [...selectedFiles]
    const projectId = selectedProjectId
    const request = new AbortController()
    uploadRequest.current = request
    dispatchTasks({ type: 'initialize', files })
    setUploadState('uploading')
    for (let id = 0; id < files.length; id += 1) {
      const file = files[id]
      if (!file) continue
      dispatchTasks({ type: 'running', id })
      try {
        const result = await uploadPlatformLocalVideo(
          { projectId, file },
          (progress) => dispatchTasks({ type: 'progress', id, progress }),
          request.signal,
        )
        dispatchTasks({
          type: 'completed',
          id,
          captureSessionId: result.captureSessionId,
          clipAssetId: result.clipAssetId,
        })
      } catch (error) {
        if (request.signal.aborted) {
          if (uploadRequest.current === request) uploadRequest.current = null
          return
        }
        dispatchTasks({
          type: 'failed',
          id,
          errorCode: error instanceof LocalUploadApiError ? error.errorCode : 'LOCAL_UPLOAD_FAILED',
          errorMessage:
            error instanceof LocalUploadApiError
              ? error.message
              : '本地视频上传失败，请检查网络和本地服务后重试。',
        })
      }
    }
    if (uploadRequest.current === request) uploadRequest.current = null
    setUploadState('completed')
    setSelectedFiles([])
  }

  return (
    <>
      <PageHeader
        eyebrow="平台本地上传"
        titleId="local-upload-title"
        title="选择视频并上传"
        description="每个视频单独上传并建立可追踪记录；平台不会根据文件名猜测多机位关系。"
        actions={
          <Button
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
          title="上传准备"
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
                onChange={setSelectedProjectId}
                options={projects.map((project) => ({
                  value: project.project_id,
                  label: `${project.display_name}（${project.current_role === 'ADMIN' ? '管理员' : '成员'}）`,
                }))}
              />
            </div>
            <div className="local-upload-field local-upload-files-field">
              <label htmlFor="local-upload-files">本机视频</label>
              <input
                id="local-upload-files"
                className="local-file-input"
                type="file"
                accept="video/*"
                multiple
                aria-describedby={`local-upload-files-hint${selectedFiles.length > 0 ? ' local-upload-selected-files' : ''}`}
                disabled={isUploading || !selectedProjectId}
                onChange={selectFiles}
              />
              <p id="local-upload-files-hint" className="form-hint">
                {selectedProjectId
                  ? '可一次选择多个视频；平台会按顺序分别上传，避免把多个视频误组装为同一次拍摄。'
                  : '请先选择所属项目，再选择本机视频。'}
              </p>
            </div>
            {selectedFiles.length > 0 && tasks.length === 0 ? (
              <div className="selected-file-selection">
                <p id="local-upload-selected-files" className="selected-file-count" role="status">
                  已选择 {selectedFiles.length} 个视频
                </p>
                <ul className="selected-file-list" aria-label="已选择视频">
                  {selectedFiles.map((file) => (
                    <li key={`${file.name}:${file.size}:${file.lastModified}`}>
                      <strong>{file.name}</strong>
                      <span>
                        {file.type || '视频文件'} · {formatBytes(file.size)}
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
              {selectedProjectId ? (
                <Link className="text-link" to={paths.captureGroups(selectedProjectId)}>
                  进入视频管理
                </Link>
              ) : null}
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
          <p className="section-description">
            传输进度只代表浏览器已发送的字节；只有平台完成校验并建立记录后，文件才会标记为“已确认完成”。
          </p>
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
                        : '上传流程'}
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
                  <summary>查看技术信息</summary>
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
                              label: '配药事件 ID',
                              value: <code>{task.captureSessionId}</code>,
                            },
                          ]
                        : []),
                    ]}
                  />
                </details>
              ) : null}
              {task.captureSessionId ? (
                <div className="local-upload-task-actions">
                  <Link
                    className="text-link"
                    to={paths.captureGroup(selectedProjectId, task.captureSessionId)}
                  >
                    打开配药事件
                  </Link>
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
