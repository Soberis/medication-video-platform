import { Alert, Button, Modal } from 'antd'
import { RefreshCw } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useBlocker, useLocation, useParams } from 'react-router'

import { paths } from '@/app/paths'
import { useAuth } from '@/app/auth'
import { PageHeader } from '@/components/PageHeader'
import { ProjectBreadcrumb } from '@/components/ProjectBreadcrumb'
import { ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { CaptureGroupAnnotationPanel } from '@/features/capture-group/CaptureGroupAnnotationPanel'
import { CaptureGroupFocusedView } from '@/features/capture-group/CaptureGroupFocusedView'
import { CaptureGroupInspector } from '@/features/capture-group/CaptureGroupInspector'
import { CaptureGroupQualityPanel } from '@/features/capture-group/CaptureGroupQualityPanel'
import { CaptureGroupReviewCleaningPanel } from '@/features/capture-group/CaptureGroupReviewCleaningPanel'
import { CaptureGroupHeaderStatus } from '@/features/capture-group/CaptureGroupOverview'
import {
  createGroupViewRows,
  type GroupViewRow,
} from '@/features/capture-group/capture-group-detail-model'
import { useCaptureGroupDetailData } from '@/features/capture-group/use-capture-group-detail-data'
import { useCaptureGroupView } from '@/features/capture-group/use-capture-group-view'
import {
  captureGroupFocusFromLocation,
  captureGroupReviewCleaningFallbackTargetId,
  captureGroupTaskDestination,
  captureGroupFocusTargetIds,
} from '@/features/capture-group-presentation'
import { shortId } from '@/features/catalog/catalog-presentation'
import type { AnnotationTask } from '@/api/annotation'

export default function CaptureGroupDetailView() {
  const { projectId = '', captureGroupId = '' } = useParams()
  const { session } = useAuth()
  const location = useLocation()
  const data = useCaptureGroupDetailData(projectId, captureGroupId)
  const [isReceiptConfirmationOpen, setIsReceiptConfirmationOpen] = useState(false)
  const [isReceiptFinalizePending, setIsReceiptFinalizePending] = useState(false)
  const receiptFinalizeRequest = useRef(false)
  const receiptFinalizeTrigger = useRef<HTMLButtonElement | null>(null)
  const appliedFocus = useRef<string | null>(null)
  const focus = captureGroupFocusFromLocation(location.search, location.hash)
  const [panelOverride, setPanelOverride] = useState<{ locationKey: string; open: boolean } | null>(
    null,
  )
  const isPanelOpen =
    panelOverride?.locationKey === location.key ? panelOverride.open : Boolean(focus)
  const [hasUnsavedAnnotation, setHasUnsavedAnnotation] = useState(false)
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      hasUnsavedAnnotation && currentLocation.pathname !== nextLocation.pathname,
  )
  useEffect(() => {
    if (!hasUnsavedAnnotation) return
    const warnBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', warnBeforeUnload)
    return () => window.removeEventListener('beforeunload', warnBeforeUnload)
  }, [hasUnsavedAnnotation])
  const focusTargetId = focus ? captureGroupFocusTargetIds[focus] : null
  const fallbackFocusTargetId =
    focus === 'review' || focus === 'cleaning' ? captureGroupReviewCleaningFallbackTargetId : null
  const viewRows = useMemo(() => createGroupViewRows(data.group), [data.group])
  const selectedView = useMemo<GroupViewRow | null>(
    () => viewRows.find((view) => view.stream?.clip_asset_id === data.selectedClipAssetId) ?? null,
    [data.selectedClipAssetId, viewRows],
  )
  const [currentPlaybackTimeMs, setCurrentPlaybackTimeMs] = useState<number | null>(null)
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const segmentStopListenerRef = useRef<(() => void) | null>(null)

  const handlePlaySegment = useCallback((startMs: number, endMs: number) => {
    const video = videoRef.current
    if (!video) return
    if (segmentStopListenerRef.current) {
      segmentStopListenerRef.current()
      segmentStopListenerRef.current = null
    }
    video.currentTime = Math.max(0, startMs / 1000)
    void video.play().catch(() => undefined)
    const onTimeUpdate = () => {
      if (video.currentTime * 1000 >= endMs) {
        video.pause()
        video.removeEventListener('timeupdate', onTimeUpdate)
        segmentStopListenerRef.current = null
      }
    }
    video.addEventListener('timeupdate', onTimeUpdate)
    segmentStopListenerRef.current = () => {
      video.removeEventListener('timeupdate', onTimeUpdate)
    }
  }, [])

  useEffect(() => {
    return () => {
      if (segmentStopListenerRef.current) {
        segmentStopListenerRef.current()
        segmentStopListenerRef.current = null
      }
    }
  }, [])

  const handleSeekToMs = useCallback((timeMs: number) => {
    const video = videoRef.current
    if (!video) return
    video.currentTime = Math.max(0, timeMs / 1000)
  }, [])

  const viewState = useCaptureGroupView(projectId, selectedView)
  const totalDurationMs = useMemo(() => {
    if (viewState.selectedMediaReport?.duration_microseconds) {
      return Math.round(viewState.selectedMediaReport.duration_microseconds / 1000)
    }
    return null
  }, [viewState.selectedMediaReport])

  const isReceiptFinalizationPending = data.isFinalizingReceipt || isReceiptFinalizePending
  const humanTask = data.taskVisibility === 'visible' ? data.task : null
  const taskDestination = humanTask ? captureGroupTaskDestination(humanTask) : null
  const humanLabelSetup =
    data.group && !humanTask && data.taskVisibility === 'visible' ? (
      <CaptureGroupQualityPanel
        projectId={projectId}
        captureGroupId={captureGroupId}
        group={data.group}
        groupQuality={data.groupQuality}
        groupQualityVisibility={data.groupQualityVisibility}
        refreshVersion={data.refreshVersion}
        assigneeActorId={session?.username ?? ''}
        canAssignHumanLabel={session?.role === 'ADMIN'}
        selectedStream={viewState.selectedStream}
        selectedDetail={viewState.selectedDetail}
        selectedReview={
          viewState.selectedStream
            ? (data.viewReviews[viewState.selectedStream.clip_asset_id] ?? null)
            : null
        }
        viewReviews={data.viewReviews}
        viewReviewErrors={data.viewReviewErrors}
        viewQualityLoading={data.viewQualityLoading}
        onReviewSaved={data.updateViewReview}
        onRefresh={data.refresh}
      />
    ) : null
  const eventIdentity = data.group?.capture_group_key ?? shortId(captureGroupId)
  const eventTitle = data.group?.capture_group_key ?? `视频 · ${shortId(captureGroupId)}`

  const confirmReceiptFinalization = async () => {
    if (receiptFinalizeRequest.current || data.isFinalizingReceipt) return
    receiptFinalizeRequest.current = true
    setIsReceiptFinalizePending(true)
    try {
      const finalized = await data.finalizeReceipt()
      if (finalized) setIsReceiptConfirmationOpen(false)
    } finally {
      receiptFinalizeRequest.current = false
      setIsReceiptFinalizePending(false)
    }
  }

  const closeReceiptConfirmation = () => {
    if (isReceiptFinalizationPending) return
    setIsReceiptConfirmationOpen(false)
    receiptFinalizeTrigger.current?.focus()
  }

  useEffect(() => {
    if (
      !data.group ||
      data.groupLoadState !== 'success' ||
      !focusTargetId ||
      appliedFocus.current === location.key
    )
      return
    const timer = window.setTimeout(() => {
      const target =
        document.getElementById(focusTargetId) ??
        (fallbackFocusTargetId ? document.getElementById(fallbackFocusTargetId) : null)
      if (!target) return
      appliedFocus.current = location.key
      target.scrollIntoView?.({ block: 'start', behavior: 'auto' })
      if (target instanceof HTMLElement) target.focus({ preventScroll: true })
    }, 0)
    return () => window.clearTimeout(timer)
  }, [
    data.group,
    data.groupLoadState,
    fallbackFocusTargetId,
    focusTargetId,
    humanTask,
    location.key,
  ])

  const renderHumanLabelPanel = () => {
    if (!data.group || (!humanTask && !hasUnsavedAnnotation)) return null
    if (humanTask && !taskDestination && !hasUnsavedAnnotation) {
      return (
        <section className="capture-group-human-label-status" aria-label="人工标签任务">
          <p>{humanTaskStatusMessage(humanTask)}</p>
        </section>
      )
    }
    if (taskDestination?.focus === 'annotation' || hasUnsavedAnnotation) {
      return (
        <CaptureGroupAnnotationPanel
          projectId={projectId}
          captureGroupId={captureGroupId}
          group={data.group}
          groupQuality={data.groupQuality}
          task={humanTask}
          taskVisibility={data.taskVisibility}
          schema={data.schema}
          viewRows={viewRows}
          viewReviews={data.viewReviews}
          currentPlaybackTimeMs={
            data.selectedClipAssetId === humanTask?.primary_clip_asset_id
              ? currentPlaybackTimeMs
              : null
          }
          totalDurationMs={totalDurationMs}
          hasNoHandDetected={
            viewState.candidateResponse?.job?.status === 'SUCCEEDED' &&
            viewState.candidateResponse?.job?.hand_result === 'NO_HAND_DETECTED'
          }
          onPlaySegment={handlePlaySegment}
          onSeekToMs={handleSeekToMs}
          onDirtyChange={setHasUnsavedAnnotation}
          onTaskChange={data.setTask}
          onRefresh={data.refresh}
        />
      )
    }
    if (!humanTask) return null
    return (
      <CaptureGroupReviewCleaningPanel
        projectId={projectId}
        group={data.group}
        groupQuality={data.groupQuality}
        groupQualityVisibility={data.groupQualityVisibility}
        task={humanTask}
        schema={data.schema}
        onRefresh={data.refresh}
      />
    )
  }

  const renderInspector = () => {
    if (!data.group) return null
    return (
      <CaptureGroupInspector
        projectId={projectId}
        captureGroupId={captureGroupId}
        group={data.group}
        groupQuality={humanTask ? data.groupQuality : null}
        groupQualityVisibility={data.groupQualityVisibility}
        qualityHistory={humanTask ? data.qualityHistory : []}
        selectedView={selectedView}
        task={humanTask}
      />
    )
  }

  const humanLabelPanel = renderHumanLabelPanel()
  const actionPanel = humanLabelPanel ?? humanLabelSetup
  const isWaitingPanel = Boolean(humanTask && !taskDestination && !hasUnsavedAnnotation)

  return (
    <>
      <ProjectBreadcrumb projectId={projectId}>
        <Link to={paths.captureGroups(projectId)} state={location.state}>
          视频
        </Link>
        <span className="breadcrumb-separator" aria-hidden="true">
          /
        </span>
        <span>{eventIdentity}</span>
      </ProjectBreadcrumb>
      <PageHeader
        className="capture-group-page-header capture-group-detail-header"
        titleId="capture-group-detail-title"
        title={eventTitle}
        description={data.group ? <CaptureGroupHeaderStatus group={data.group} /> : undefined}
        actions={
          <>
            {actionPanel ? (
              <Button
                aria-expanded={isPanelOpen}
                aria-controls="human-label-panel"
                onClick={() => setPanelOverride({ locationKey: location.key, open: !isPanelOpen })}
              >
                {isPanelOpen ? '收起标注' : '标注'}
                {hasUnsavedAnnotation ? ' · 未提交' : ''}
              </Button>
            ) : null}
            {data.group?.receipt_state === 'RECEIVING' ? (
              <Button
                ref={receiptFinalizeTrigger}
                type="primary"
                loading={isReceiptFinalizationPending}
                onClick={() => setIsReceiptConfirmationOpen(true)}
              >
                结束接收
              </Button>
            ) : null}
            <Button
              icon={<RefreshCw aria-hidden="true" size={14} strokeWidth={1.8} />}
              loading={data.groupLoadState === 'loading'}
              onClick={() => void data.refresh()}
            >
              刷新
            </Button>
          </>
        }
      />
      {data.groupLoadState === 'loading' ? (
        <LoadingState>{data.group ? '正在刷新采集记录…' : '正在读取采集记录…'}</LoadingState>
      ) : null}
      {data.groupLoadState === 'error' ? (
        <ErrorState code={data.groupErrorCode} onRetry={() => void data.refresh()}>
          {data.groupErrorMessage}
        </ErrorState>
      ) : null}
      {data.group ? (
        <>
          {data.taskVisibility === 'unavailable' ? (
            <Alert
              type="warning"
              showIcon
              title="人工任务状态暂不可用；采集记录、播放器和机器证据仍可查看。"
            />
          ) : null}
          {humanTask && data.qualityErrorMessage ? (
            <Alert type="warning" showIcon title={data.qualityErrorMessage} />
          ) : null}
          {humanTask && data.taskErrorMessage ? (
            <Alert type="warning" showIcon title={data.taskErrorMessage} />
          ) : null}
          <div
            className={`capture-group-workspace${actionPanel && isPanelOpen && !isWaitingPanel ? '' : ' capture-group-workspace-machine-evidence'}`}
            data-stage={
              actionPanel && isPanelOpen
                ? taskDestination?.focus === 'annotation'
                  ? 'ANNOTATION'
                  : humanTask?.status === 'COMPLETED'
                    ? 'COMPLETED'
                    : undefined
                : undefined
            }
          >
            <div className="capture-group-video-column">
              <CaptureGroupFocusedView
                viewRows={viewRows}
                task={humanTask}
                viewReviews={data.viewReviews}
                viewState={viewState}
                externalVideoRef={videoRef}
                onSelectView={(clipAssetId) => {
                  setCurrentPlaybackTimeMs(null)
                  data.setSelectedClipAssetId(clipAssetId)
                }}
                onPlaybackTimeChange={setCurrentPlaybackTimeMs}
                isCompleted={humanTask?.status === 'COMPLETED'}
              />
            </div>
            {actionPanel && !isWaitingPanel ? (
              <div
                id="human-label-panel"
                className="capture-group-action-column"
                hidden={!isPanelOpen}
              >
                {actionPanel}
              </div>
            ) : null}
          </div>
          {isWaitingPanel ? (
            <div id="human-label-panel" hidden={!isPanelOpen}>
              {actionPanel}
            </div>
          ) : null}
          <details className="capture-group-record-details">
            <summary>更多信息</summary>
            {renderInspector()}
          </details>
        </>
      ) : null}
      <Modal
        open={blocker.state === 'blocked'}
        title="离开并丢弃未提交的标注？"
        okText="丢弃并离开"
        cancelText="继续编辑"
        onOk={() => blocker.state === 'blocked' && blocker.proceed()}
        onCancel={() => blocker.state === 'blocked' && blocker.reset()}
      >
        <p>当前时间段和备注尚未提交。留在页面可继续编辑。</p>
      </Modal>
      <Modal
        open={isReceiptConfirmationOpen}
        title="结束本次接收？"
        okText="结束接收"
        cancelText="取消"
        confirmLoading={isReceiptFinalizationPending}
        okButtonProps={{
          disabled: isReceiptFinalizationPending,
          'aria-label': '结束接收',
        }}
        cancelButtonProps={{
          disabled: isReceiptFinalizationPending,
          'aria-label': '取消',
        }}
        mask={{ closable: !isReceiptFinalizationPending }}
        keyboard={!isReceiptFinalizationPending}
        onCancel={closeReceiptConfirmation}
        onOk={() => void confirmReceiptFinalization()}
        destroyOnHidden
      >
        <p>系统将根据当前已收到的机位记录接收结果；媒体检查与 MediaPipe 检测会独立继续。</p>
      </Modal>
    </>
  )
}

function humanTaskStatusMessage(task: AnnotationTask) {
  if (task.status === 'COMPLETED') return '该人工标签任务已完成，可查看修订与复核事实。'
  if (task.status === 'SUBMITTED')
    return '标注已提交，等待有项目管理权限的管理员确认。管理员也可以确认自己提交的标注；当前不可操作时请检查项目权限与标注前检查状态。'
  if (task.annotation_revision?.review?.decision === 'REJECTED') {
    return '复核已退回该修订，等待任务执行人根据意见返工。'
  }
  return '等待任务执行人处理。'
}
