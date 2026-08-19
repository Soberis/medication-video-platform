import { Alert, Button, Card, Empty, Progress, Select, Tag } from 'antd'
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
}
type TaskAction =
  | { type: 'initialize'; files: File[] }
  | { type: 'reset' }
  | { type: 'running'; id: number }
  | { type: 'progress'; id: number; progress: LocalUploadProgress }
  | { type: 'completed'; id: number; captureSessionId: string }
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
    }))
  return tasks.map((task) =>
    task.id !== action.id
      ? task
      : action.type === 'running'
        ? { ...task, status: 'RUNNING' }
        : action.type === 'progress'
          ? { ...task, progress: action.progress }
          : action.type === 'completed'
            ? { ...task, status: 'COMPLETED', captureSessionId: action.captureSessionId }
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
  UPLOADING: '正在上传分片',
  VERIFYING: '服务器正在校验视频',
  ASSEMBLING: '正在建立单视角配药操作记录',
  COMPLETED: '上传完成',
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
  if (task.status === 'QUEUED') return '等待前一个视频完成。'
  if (task.status === 'FAILED') return task.errorMessage ?? '上传未完成。'
  if (task.status === 'COMPLETED') return '已完成校验，并建立单视角配药操作记录。'
  return task.progress ? phaseLabels[task.progress.phase] : '正在准备上传。'
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
  const uploadSummary =
    uploadState !== 'completed'
      ? { label: '上传中', color: 'processing' }
      : failedTaskCount === 0
        ? { label: '全部完成', color: 'success' }
        : completedTaskCount === 0
          ? { label: '本轮失败', color: 'error' }
          : { label: '部分完成', color: 'warning' }

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
        error instanceof CatalogApiError ? error.message : '暂时无法读取授权项目，请稍后重试。',
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
        dispatchTasks({ type: 'completed', id, captureSessionId: result.captureSessionId })
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
              : '本地视频上传未完成，请检查网络和本地服务后重试。',
        })
      }
    }
    if (uploadRequest.current === request) uploadRequest.current = null
    setUploadState('completed')
    setSelectedFiles([])
  }

  return (
    <>
      <section className="page-heading" aria-labelledby="local-upload-title">
        <div>
          <p className="app-eyebrow">平台本地上传</p>
          <h1 id="local-upload-title">选择视频并上传</h1>
          <p>
            每个视频使用独立批次上传、校验并建立单视角配药操作记录；不会从文件名推断多相机关系。
          </p>
        </div>
        <Button
          loading={projectLoadState === 'loading'}
          disabled={isUploading}
          onClick={() => void loadProjects()}
        >
          刷新项目
        </Button>
      </section>
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
        <Card className="local-upload-card" variant="outlined">
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
                  label: `${project.display_name}（${project.current_role === 'ADMIN' ? '管理员' : '普通用户'}）`,
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
                aria-describedby="local-upload-files-hint"
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
              <ul className="selected-file-list" aria-label="已选择的视频">
                {selectedFiles.map((file) => (
                  <li key={`${file.name}:${file.size}:${file.lastModified}`}>
                    <strong>{file.name}</strong>
                    <span>{formatBytes(file.size)}</span>
                  </li>
                ))}
              </ul>
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
                <Link className="text-link" to={paths.videos(selectedProjectId)}>
                  查看项目视频
                </Link>
              ) : null}
            </div>
          </form>
        </Card>
      ) : null}
      {tasks.length > 0 ? (
        <section
          className="local-upload-results"
          aria-live="polite"
          aria-labelledby="local-upload-progress-title"
        >
          <div className="card-header-row">
            <div>
              <p className="app-eyebrow">真实上传进度</p>
              <h2 id="local-upload-progress-title">
                已完成 {completedTaskCount} / {tasks.length} 个视频
              </h2>
            </div>
            <Tag color={uploadSummary.color}>{uploadSummary.label}</Tag>
          </div>
          {tasks.map((task) => (
            <article key={task.id} className="local-upload-task">
              <div className="file-heading">
                <div>
                  <p className="file-kind">
                    {task.status === 'COMPLETED'
                      ? '已完成'
                      : task.status === 'FAILED'
                        ? '失败'
                        : '视频上传'}
                  </p>
                  <h3>{task.file.name}</h3>
                </div>
                <Tag
                  color={
                    task.status === 'FAILED'
                      ? 'error'
                      : task.status === 'COMPLETED'
                        ? 'success'
                        : 'processing'
                  }
                >
                  {task.status === 'QUEUED'
                    ? '等待中'
                    : task.status === 'RUNNING'
                      ? '处理中'
                      : task.status === 'COMPLETED'
                        ? '完成'
                        : '失败'}
                </Tag>
              </div>
              <Progress
                percent={taskPercent(task)}
                status={
                  task.status === 'FAILED'
                    ? 'exception'
                    : task.status === 'COMPLETED'
                      ? 'success'
                      : 'normal'
                }
              />
              <p className="task-description">{taskDescription(task)}</p>
              {task.progress?.batchId ? (
                <p className="batch-meta">
                  批次 ID：<code>{task.progress.batchId}</code>
                </p>
              ) : null}
              {task.captureSessionId ? (
                <p className="batch-meta">
                  配药操作 ID：<code>{task.captureSessionId}</code>
                </p>
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
        <Empty description="当前账号尚未加入项目，无法上传视频。请联系管理员添加项目成员。" />
      ) : null}
    </>
  )
}
