import { Alert, Button } from 'antd'
import { ArrowRight, Camera, RefreshCw } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import {
  CaptureGroupApiError,
  getCaptureGroupQualityReview,
  listCaptureGroups,
  type CaptureGroup,
  type CaptureGroupQualityReview,
} from '@/api/capture-groups'
import { AnnotationTaskApiError, listAnnotationTasks, type AnnotationTask } from '@/api/annotation'
import { paths } from '@/app/paths'
import { PageHeader } from '@/components/PageHeader'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { EmptyState, ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import {
  formatViewQualitySummary,
  groupQualityLabels,
  groupQualityTone,
  receiptStateLabels,
  receiptTone,
  taskNextAction,
} from '@/features/capture-group-presentation'
import { formatDateTime, shortId } from '@/features/catalog/catalog-presentation'

interface CaptureGroupListRow {
  group: CaptureGroup
  quality: CaptureGroupQualityReview | null
  task: AnnotationTask | null
}

export default function CaptureGroupListView() {
  const { projectId = '' } = useParams()
  const [rows, setRows] = useState<CaptureGroupListRow[]>([])
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const activeRequest = useRef<AbortController | null>(null)

  const loadGroups = useCallback(async () => {
    activeRequest.current?.abort()
    const request = new AbortController()
    activeRequest.current = request
    setLoadState('loading')
    setErrorCode('')
    setErrorMessage('')
    try {
      const [groupList, taskList] = await Promise.all([
        listCaptureGroups(projectId, request.signal),
        listAnnotationTasks(projectId, request.signal),
      ])
      const indexedTasks = taskList.items.filter((task) => Boolean(task.capture_group_id))
      const loadedRows = await Promise.all(
        groupList.items.map(async (group): Promise<CaptureGroupListRow | null> => {
          const quality = await getCaptureGroupQualityReview(
            projectId,
            group.capture_group_id,
            request.signal,
          ).catch(() => null)
          return {
            group,
            quality,
            task:
              indexedTasks.find(
                (candidate) => candidate.capture_group_id === group.capture_group_id,
              ) ?? null,
          }
        }),
      )
      if (request.signal.aborted || activeRequest.current !== request) return
      setRows(loadedRows.filter((row): row is CaptureGroupListRow => row !== null))
      setLoadState('success')
    } catch (error) {
      if (request.signal.aborted || activeRequest.current !== request) return
      setLoadState('error')
      setErrorCode(
        error instanceof CaptureGroupApiError || error instanceof AnnotationTaskApiError
          ? error.errorCode
          : 'CAPTURE_GROUP_LIST_REQUEST_FAILED',
      )
      setErrorMessage(
        error instanceof CaptureGroupApiError || error instanceof AnnotationTaskApiError
          ? error.message
          : '暂时无法读取 CaptureGroup 列表，请稍后重试。',
      )
    } finally {
      if (activeRequest.current === request) activeRequest.current = null
    }
  }, [projectId])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadGroups(), 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
    }
  }, [loadGroups])

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑导航">
        <Link to={paths.videos(projectId)}>视频管理</Link>
        <span aria-hidden="true">/</span>
        <span>CaptureGroup 多机位</span>
      </nav>
      <PageHeader
        className="capture-group-page-header"
        eyebrow="V1.25 · 多机位工作流"
        titleId="capture-group-list-title"
        title="CaptureGroup 多机位"
        description="按服务端 CaptureGroup 事实进入接收、逐视图质量和组级人工工作流；列表不推断未返回的组。"
        actions={
          <Button
            size="small"
            icon={<RefreshCw aria-hidden="true" size={14} strokeWidth={1.8} />}
            loading={loadState === 'loading'}
            onClick={() => void loadGroups()}
          >
            刷新
          </Button>
        }
      />
      {loadState === 'loading' ? <LoadingState>正在读取 CaptureGroup…</LoadingState> : null}
      {loadState === 'error' ? (
        <ErrorState code={errorCode} onRetry={() => void loadGroups()}>
          {errorMessage}
        </ErrorState>
      ) : null}
      {loadState === 'success' && !rows.length ? (
        <EmptyState description="当前项目暂无服务端 CaptureGroup。" />
      ) : null}
      {loadState === 'success' && rows.length ? (
        <section className="capture-group-list-surface" aria-labelledby="capture-group-queue-title">
          <header className="capture-group-list-heading">
            <div>
              <p className="app-eyebrow">操作队列</p>
              <h2 id="capture-group-queue-title">CaptureGroup</h2>
            </div>
            <span className="capture-group-list-count">{rows.length} 个服务端索引组</span>
          </header>
          <div className="capture-group-list" role="list" aria-label="CaptureGroup 操作列表">
            {rows.map(({ group, quality, task }) => (
              <article
                className="capture-group-list-row"
                key={group.capture_group_id}
                role="listitem"
                aria-label={`CaptureGroup ${group.capture_group_key ?? shortId(group.capture_group_id)}`}
              >
                <header className="capture-group-list-row-header">
                  <div className="capture-group-list-identity">
                    <Camera aria-hidden="true" size={18} strokeWidth={1.7} />
                    <div>
                      <Link
                        className="text-link capture-group-list-title"
                        to={paths.captureGroup(projectId, group.capture_group_id)}
                      >
                        {group.capture_group_key ??
                          `CaptureGroup ${shortId(group.capture_group_id)}`}
                      </Link>
                      <span className="capture-group-list-id" title={group.capture_group_id}>
                        {shortId(group.capture_group_id)}
                      </span>
                    </div>
                  </div>
                  <StatusIndicator tone={receiptTone(group.receipt_state)}>
                    回执 · {receiptStateLabels[group.receipt_state]}
                  </StatusIndicator>
                </header>
                <dl className="capture-group-list-facts">
                  <div>
                    <dt>采集时间</dt>
                    <dd>{formatDateTime(group.device_time_utc ?? group.created_at)}</dd>
                  </div>
                  <div>
                    <dt>视图</dt>
                    <dd>
                      <span>
                        应 {group.expected_camera_count} · 已收{' '}
                        {group.received_camera_numbers.length} · 缺失{' '}
                        {group.missing_camera_numbers.length}
                      </span>
                      {group.missing_camera_numbers.length ? (
                        <span className="capture-group-list-missing-views">
                          缺失：
                          {group.missing_camera_numbers
                            .map((cameraNumber) => `Cam ${cameraNumber}`)
                            .join('、')}
                        </span>
                      ) : null}
                    </dd>
                  </div>
                  <div>
                    <dt>View Quality</dt>
                    <dd>{formatViewQualitySummary(quality)}</dd>
                  </div>
                  <div>
                    <dt>Group Quality</dt>
                    <dd>
                      <StatusIndicator tone={groupQualityTone(quality?.result ?? 'UNREVIEWED')}>
                        {groupQualityLabels[quality?.result ?? 'UNREVIEWED']}
                      </StatusIndicator>
                    </dd>
                  </div>
                </dl>
                <footer className="capture-group-list-row-footer">
                  <span>
                    下一步：{taskNextAction(group, quality, task)}
                    {task ? ` · 任务 ${shortId(task.annotation_task_id)}` : ''}
                  </span>
                  <Link
                    className="capture-group-list-open"
                    to={paths.captureGroup(projectId, group.capture_group_id)}
                    aria-label={`打开 CaptureGroup ${group.capture_group_key ?? shortId(group.capture_group_id)}`}
                  >
                    打开详情 <ArrowRight aria-hidden="true" size={14} strokeWidth={1.8} />
                  </Link>
                </footer>
              </article>
            ))}
          </div>
        </section>
      ) : null}
      {loadState === 'success' && rows.some((row) => row.quality === null) ? (
        <Alert
          className="capture-group-list-note"
          type="info"
          showIcon
          title="部分 Group Quality 暂不可用"
          description="列表保留 CaptureGroup 与回执事实；质量结论将在详情页按服务端状态重试读取。"
        />
      ) : null}
    </>
  )
}
