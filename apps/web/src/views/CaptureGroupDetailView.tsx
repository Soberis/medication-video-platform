import { Alert, Button, Modal } from 'antd'
import { ArrowLeft } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useBlocker, useLocation, useNavigate, useParams } from 'react-router'

import { paths } from '@/app/paths'
import { useAuth } from '@/app/auth'
import { PageHeader } from '@/components/PageHeader'
import { ProjectBreadcrumb } from '@/components/ProjectBreadcrumb'
import { RefreshButton } from '@/components/RefreshButton'
import { ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { CaptureGroupAnnotationPanel } from '@/features/capture-group/CaptureGroupAnnotationPanel'
import { CaptureGroupFocusedView } from '@/features/capture-group/CaptureGroupFocusedView'
import { CaptureGroupInspector } from '@/features/capture-group/CaptureGroupInspector'
import { CaptureGroupReviewPanel } from '@/features/capture-group/CaptureGroupReviewPanel'
import {
  createGroupViewRows,
  type GroupViewRow,
} from '@/features/capture-group/capture-group-detail-model'
import { useCaptureGroupDetailData } from '@/features/capture-group/use-capture-group-detail-data'
import { useCaptureGroupView } from '@/features/capture-group/use-capture-group-view'
import {
  captureGroupFocusFromLocation,
  captureGroupReviewFallbackTargetId,
  captureGroupTaskDestination,
  captureGroupFocusTargetIds,
} from '@/features/capture-group-presentation'
import { shortId } from '@/features/catalog/catalog-presentation'

import type { CaptureGroup } from '@/api/capture-groups'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { receiptStateLabels, receiptTone } from '@/features/capture-group-presentation'
import { formatDateTime } from '@/features/catalog/catalog-presentation'

function CaptureGroupHeaderStatus({ group }: { group: CaptureGroup }) {
  const captureTime = group.device_time_utc ?? group.created_at
  const captureTimeLabel = group.device_time_utc ? '拍摄' : '记录'
  const viewCount =
    group.received_camera_count === group.expected_camera_count
      ? `${group.expected_camera_count} 个机位`
      : `已收到 ${group.received_camera_count}/${group.expected_camera_count} 个机位`

  return (
    <span className="capture-group-header-status">
      <StatusIndicator tone={receiptTone(group.receipt_state)}>
        {receiptStateLabels[group.receipt_state]}
      </StatusIndicator>
      <span>
        {captureTimeLabel} {formatDateTime(captureTime)} · {viewCount}
      </span>
    </span>
  )
}

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
    focus === 'review' || focus === 'cleaning' ? captureGroupReviewFallbackTargetId : null
  const viewRows = useMemo(() => createGroupViewRows(data.group), [data.group])
  const selectedView = useMemo<GroupViewRow | null>(
    () => viewRows.find((view) => view.stream?.clip_asset_id === data.selectedClipAssetId) ?? null,
    [data.selectedClipAssetId, viewRows],
  )
  const [currentPlaybackTimeMs, setCurrentPlaybackTimeMs] = useState<number | null>(null)
  const [preservedPlaybackTimeMs, setPreservedPlaybackTimeMs] = useState<number | null>(null)
  const shouldAutoStartPlaybackRef = useRef(false)
  const pendingPlaySegmentRef = useRef<{ startMs: number; endMs: number } | null>(null)
  const pendingPlayAtMsRef = useRef<number | null>(null)
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const segmentStopListenerRef = useRef<(() => void) | null>(null)

  const handlePlaybackTimeChange = useCallback((timeMs: number | null) => {
    setCurrentPlaybackTimeMs(timeMs)
    if (timeMs !== null) {
      setPreservedPlaybackTimeMs(timeMs)
    }
  }, [])

  const viewState = useCaptureGroupView(projectId, selectedView)
  const {
    canPlay,
    isRequestingPlayback,
    playbackGrant,
    playbackState,
    selectedDetail,
    selectedMediaReport,
    selectedStream,
    startPlayback,
  } = viewState

  const executePlaySegment = useCallback((video: HTMLVideoElement, startMs: number, endMs: number) => {
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

  const handlePlaySegment = useCallback(
    (startMs: number, endMs: number) => {
      setPreservedPlaybackTimeMs(Math.max(0, startMs))
      const video = videoRef.current
      if (!video || playbackState !== 'ready') {
        pendingPlaySegmentRef.current = { startMs, endMs }
        pendingPlayAtMsRef.current = null
        if (canPlay && !isRequestingPlayback && (playbackState === 'idle' || !playbackGrant)) {
          shouldAutoStartPlaybackRef.current = true
          void startPlayback()
        }
        return
      }
      executePlaySegment(video, startMs, endMs)
    },
    [canPlay, executePlaySegment, isRequestingPlayback, playbackGrant, playbackState, startPlayback],
  )

  const handlePlayAtMs = useCallback(
    (timeMs: number) => {
      const validMs = Math.max(0, timeMs)
      setPreservedPlaybackTimeMs(validMs)
      if (segmentStopListenerRef.current) {
        segmentStopListenerRef.current()
        segmentStopListenerRef.current = null
      }
      const video = videoRef.current
      if (!video || playbackState !== 'ready') {
        pendingPlayAtMsRef.current = validMs
        pendingPlaySegmentRef.current = null
        if (canPlay && !isRequestingPlayback && (playbackState === 'idle' || !playbackGrant)) {
          shouldAutoStartPlaybackRef.current = true
          void startPlayback()
        }
        return
      }
      video.currentTime = validMs / 1000
      void video.play().catch(() => undefined)
    },
    [canPlay, isRequestingPlayback, playbackGrant, playbackState, startPlayback],
  )

  const handleSeekToMs = useCallback(
    (timeMs: number) => {
      setPreservedPlaybackTimeMs(Math.max(0, timeMs))
      const video = videoRef.current
      if (!video || playbackState !== 'ready') {
        if (canPlay && !isRequestingPlayback && (playbackState === 'idle' || !playbackGrant)) {
          shouldAutoStartPlaybackRef.current = true
          void startPlayback()
        }
        return
      }
      segmentStopListenerRef.current?.()
      segmentStopListenerRef.current = null
      video.currentTime = Math.max(0, timeMs / 1000)
    },
    [canPlay, isRequestingPlayback, playbackGrant, playbackState, startPlayback],
  )

  useEffect(() => {
    if (playbackState === 'ready' && videoRef.current) {
      if (pendingPlayAtMsRef.current !== null) {
        const targetMs = pendingPlayAtMsRef.current
        pendingPlayAtMsRef.current = null
        if (segmentStopListenerRef.current) {
          segmentStopListenerRef.current()
          segmentStopListenerRef.current = null
        }
        videoRef.current.currentTime = targetMs / 1000
        void videoRef.current.play().catch(() => undefined)
      } else if (pendingPlaySegmentRef.current) {
        const { startMs, endMs } = pendingPlaySegmentRef.current
        pendingPlaySegmentRef.current = null
        executePlaySegment(videoRef.current, startMs, endMs)
      }
    }
  }, [executePlaySegment, playbackState])

  useEffect(() => {
    return () => {
      if (segmentStopListenerRef.current) {
        segmentStopListenerRef.current()
        segmentStopListenerRef.current = null
      }
    }
  }, [data.selectedClipAssetId])

  const totalDurationMs = useMemo(() => {
    if (selectedMediaReport?.duration_microseconds) {
      return Math.floor(selectedMediaReport.duration_microseconds / 1000)
    }
    return null
  }, [selectedMediaReport])

  const handleSelectView = useCallback(
    (clipAssetId: string) => {
      const wasPlaybackActive = Boolean(playbackGrant || playbackState === 'ready')
      data.setSelectedClipAssetId(clipAssetId)
      if (wasPlaybackActive) {
        shouldAutoStartPlaybackRef.current = true
      }
    },
    [data, playbackGrant, playbackState],
  )

  useEffect(() => {
    if (
      shouldAutoStartPlaybackRef.current &&
      data.selectedClipAssetId &&
      selectedStream?.clip_asset_id === data.selectedClipAssetId &&
      selectedDetail?.status === 'INGESTED' &&
      canPlay &&
      !playbackGrant &&
      !isRequestingPlayback &&
      playbackState === 'idle'
    ) {
      shouldAutoStartPlaybackRef.current = false
      void startPlayback()
    }
  }, [
    canPlay,
    data.selectedClipAssetId,
    isRequestingPlayback,
    playbackGrant,
    playbackState,
    preservedPlaybackTimeMs,
    selectedDetail?.status,
    selectedStream?.clip_asset_id,
    startPlayback,
  ])

  const isReceiptFinalizationPending = data.isFinalizingReceipt || isReceiptFinalizePending
  const humanTask = data.taskVisibility === 'visible' ? data.task : null
  const taskDestination = humanTask ? captureGroupTaskDestination(humanTask) : null
  const handleDiscardVideo = useCallback(async () => {
    await data.refresh()
    navigate(paths.captureGroups(projectId), { replace: true, state: location.state })
  }, [data, location.state, navigate, projectId])
  const eventIdentity = data.group?.capture_group_key ?? shortId(captureGroupId)

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
        <CaptureGroupReviewPanel
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

    const handlePlaySegmentForAnnotation = (startMs: number, endMs: number) => {
      if (activePrimaryId && data.selectedClipAssetId !== activePrimaryId) {
        handleSelectView(activePrimaryId)
      }
      handlePlaySegment(startMs, endMs)
    }

    const handlePlayAtMsForAnnotation = (timeMs: number) => {
      if (activePrimaryId && data.selectedClipAssetId !== activePrimaryId) {
        handleSelectView(activePrimaryId)
      }
      handlePlayAtMs(timeMs)
    }

    const handleSeekToMsForAnnotation = (timeMs: number) => {
      if (activePrimaryId && data.selectedClipAssetId !== activePrimaryId) {
        handleSelectView(activePrimaryId)
      }
      handleSeekToMs(timeMs)
    }

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
        onPlaySegment={handlePlaySegmentForAnnotation}
        onPlayAtMs={handlePlayAtMsForAnnotation}
        onSeekToMs={handleSeekToMsForAnnotation}
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
        eyebrow="采集记录"
        title={eventIdentity}
        description={data.group ? <CaptureGroupHeaderStatus group={data.group} /> : undefined}
        actions={
          <>
            <Button
              icon={<ArrowLeft aria-hidden="true" size={14} strokeWidth={1.8} />}
              onClick={() =>
                void navigate(paths.captureGroups(projectId), { state: location.state })
              }
            >
              返回视频列表
            </Button>
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
            <RefreshButton
              loading={data.groupLoadState === 'loading'}
              onClick={() => void data.refresh()}
            />
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
                initialPlaybackTimeMs={preservedPlaybackTimeMs}
                onSelectView={handleSelectView}
                onPlaybackTimeChange={handlePlaybackTimeChange}
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
