import { Alert, Button, Modal } from 'antd'
import { RefreshCw } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useBlocker, useLocation, useNavigate, useParams } from 'react-router'

import { paths } from '@/app/paths'
import { useAuth } from '@/app/auth'
import { PageHeader } from '@/components/PageHeader'
import { ProjectBreadcrumb } from '@/components/ProjectBreadcrumb'
import { ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { CaptureGroupAnnotationPanel } from '@/features/capture-group/CaptureGroupAnnotationPanel'
import { CaptureGroupFocusedView } from '@/features/capture-group/CaptureGroupFocusedView'
import { CaptureGroupInspector } from '@/features/capture-group/CaptureGroupInspector'
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

export default function CaptureGroupDetailView() {
  const { projectId = '', captureGroupId = '' } = useParams()
  const { session } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const data = useCaptureGroupDetailData(projectId, captureGroupId)
  const [isReceiptConfirmationOpen, setIsReceiptConfirmationOpen] = useState(false)
  const [isReceiptFinalizePending, setIsReceiptFinalizePending] = useState(false)
  const receiptFinalizeRequest = useRef(false)
  const receiptFinalizeTrigger = useRef<HTMLButtonElement | null>(null)
  const appliedFocus = useRef<string | null>(null)
  const focus = captureGroupFocusFromLocation(location.search, location.hash)
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
  }, [data.selectedClipAssetId])

  const handleSeekToMs = useCallback((timeMs: number) => {
    const video = videoRef.current
    if (!video) return
    segmentStopListenerRef.current?.()
    segmentStopListenerRef.current = null
    video.currentTime = Math.max(0, timeMs / 1000)
  }, [])

  const viewState = useCaptureGroupView(projectId, selectedView)
  const totalDurationMs = useMemo(() => {
    if (viewState.selectedMediaReport?.duration_microseconds) {
      return Math.floor(viewState.selectedMediaReport.duration_microseconds / 1000)
    }
    return null
  }, [viewState.selectedMediaReport])

  const isReceiptFinalizationPending = data.isFinalizingReceipt || isReceiptFinalizePending
  const humanTask = data.taskVisibility === 'visible' ? data.task : null
  const taskDestination = humanTask ? captureGroupTaskDestination(humanTask) : null
  const handleDiscardVideo = useCallback(async () => {
    await data.refresh()
    navigate(paths.captureGroups(projectId), { replace: true, state: location.state })
  }, [data, location.state, navigate, projectId])
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
    if (!data.group) return null
    if (taskDestination?.focus === 'review' || taskDestination?.focus === 'cleaning') {
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

    const activePrimaryId =
      humanTask?.primary_clip_asset_id ??
      viewState.selectedStream?.clip_asset_id ??
      viewRows.find((view) => view.stream?.clip_asset_id)?.stream?.clip_asset_id ??
      null

    return (
      <CaptureGroupAnnotationPanel
        projectId={projectId}
        captureGroupId={captureGroupId}
        group={data.group}
        groupQuality={data.groupQuality}
        task={humanTask}
        schema={data.schema}
        viewRows={viewRows}
        viewReviews={data.viewReviews}
        assigneeActorId={session?.username ?? ''}
        canPrepareTask={session?.role === 'ADMIN' && data.taskVisibility === 'visible'}
        currentPlaybackTimeMs={
          data.selectedClipAssetId === activePrimaryId ? currentPlaybackTimeMs : null
        }
        totalDurationMs={data.selectedClipAssetId === activePrimaryId ? totalDurationMs : null}
        hasNoHandDetected={
          data.selectedClipAssetId === activePrimaryId &&
          viewState.candidateResponse?.job?.status === 'SUCCEEDED' &&
          viewState.candidateResponse?.job?.hand_result === 'NO_HAND_DETECTED'
        }
        onPlaySegment={data.selectedClipAssetId === activePrimaryId ? handlePlaySegment : undefined}
        onSeekToMs={data.selectedClipAssetId === activePrimaryId ? handleSeekToMs : undefined}
        onDirtyChange={setHasUnsavedAnnotation}
        onTaskChange={data.setTask}
        onRefresh={data.refresh}
        onDiscardVideo={handleDiscardVideo}
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

  const actionPanel = renderHumanLabelPanel()

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
            className="capture-group-workspace"
            data-stage={
              taskDestination?.focus === 'annotation' || !humanTask
                ? 'ANNOTATION'
                : humanTask?.status === 'COMPLETED'
                  ? 'COMPLETED'
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
            {actionPanel ? (
              <div id="human-label-panel" className="capture-group-action-column">
                {actionPanel}
              </div>
            ) : null}
          </div>
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
