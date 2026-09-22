import { Alert, Button, Modal, Skeleton } from 'antd'
import { ArrowLeft } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useBlocker, useLocation, useNavigate, useParams } from 'react-router'

import { paths } from '@/app/paths'
import { useAuth } from '@/app/auth'
import { PageHeader } from '@/components/PageHeader'
import { ProjectBreadcrumb } from '@/components/ProjectBreadcrumb'
import { RefreshButton } from '@/components/RefreshButton'
import { ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { ErrorBoundary } from '@/components/ErrorBoundary'
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
  const [playbackTimeByClipAssetId, setPlaybackTimeByClipAssetId] = useState<
    Record<string, number>
  >({})
  const currentPlaybackTimeMs = data.selectedClipAssetId
    ? (playbackTimeByClipAssetId[data.selectedClipAssetId] ?? null)
    : null
  const preservedPlaybackTimeMs = currentPlaybackTimeMs
  const shouldAutoStartPlaybackRef = useRef(false)
  const pendingPlaySegmentRef = useRef<{ startMs: number; endMs: number } | null>(null)
  const pendingPlayAtMsRef = useRef<number | null>(null)
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const segmentStopListenerRef = useRef<(() => void) | null>(null)

  const handlePlaybackTimeChange = useCallback(
    (timeMs: number | null) => {
      if (timeMs === null || !data.selectedClipAssetId) return
      const clipAssetId = data.selectedClipAssetId
      setPlaybackTimeByClipAssetId((previous) => ({ ...previous, [clipAssetId]: timeMs }))
    },
    [data.selectedClipAssetId],
  )

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

  const executePlaySegment = useCallback(
    (video: HTMLVideoElement, startMs: number, endMs: number) => {
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
    },
    [],
  )

  const handlePlaySegment = useCallback(
    (startMs: number, endMs: number) => {
      if (data.selectedClipAssetId) {
        const clipAssetId = data.selectedClipAssetId
        setPlaybackTimeByClipAssetId((previous) => ({
          ...previous,
          [clipAssetId]: Math.max(0, startMs),
        }))
      }
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
    [
      canPlay,
      executePlaySegment,
      isRequestingPlayback,
      playbackGrant,
      playbackState,
      startPlayback,
      data.selectedClipAssetId,
    ],
  )

  const handlePlayAtMs = useCallback(
    (timeMs: number) => {
      const validMs = Math.max(0, timeMs)
      if (data.selectedClipAssetId) {
        const clipAssetId = data.selectedClipAssetId
        setPlaybackTimeByClipAssetId((previous) => ({ ...previous, [clipAssetId]: validMs }))
      }
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
    [
      canPlay,
      data.selectedClipAssetId,
      isRequestingPlayback,
      playbackGrant,
      playbackState,
      startPlayback,
    ],
  )

  const handleSeekToMs = useCallback(
    (timeMs: number) => {
      if (data.selectedClipAssetId) {
        const clipAssetId = data.selectedClipAssetId
        setPlaybackTimeByClipAssetId((previous) => ({
          ...previous,
          [clipAssetId]: Math.max(0, timeMs),
        }))
      }
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
    [
      canPlay,
      data.selectedClipAssetId,
      isRequestingPlayback,
      playbackGrant,
      playbackState,
      startPlayback,
    ],
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

  const isReceiptFinalizationPending = data.isFinalizingReceipt || isReceiptFinalizePending
  const humanTask = data.taskVisibility === 'visible' ? data.task : null
  const taskDestination = humanTask ? captureGroupTaskDestination(humanTask) : null
  const activeCameraNumber = selectedStream?.camera_number ?? selectedView?.cameraNumber ?? null

  const handleSelectView = useCallback(
    (clipAssetId: string) => {
      data.setSelectedClipAssetId(clipAssetId)
      shouldAutoStartPlaybackRef.current = true
    },
    [data],
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
    selectedDetail?.status,
    selectedStream?.clip_asset_id,
    startPlayback,
  ])

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
      target.scrollIntoView?.({ block: 'nearest', behavior: 'auto' })
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
          task={humanTask}
          schema={data.schema}
          onRefresh={data.refresh}
          onPlaySegment={handlePlaySegment}
          activeCameraNumber={activeCameraNumber}
          onSelectCamera={(cameraNumber) => {
            const clipAssetId = viewRows.find((view) => view.cameraNumber === cameraNumber)?.stream
              ?.clip_asset_id
            if (clipAssetId) handleSelectView(clipAssetId)
          }}
        />
      )
    }

    const handlePlaySegmentForAnnotation = (startMs: number, endMs: number) => {
      handlePlaySegment(startMs, endMs)
    }

    const handlePlayAtMsForAnnotation = (timeMs: number) => {
      handlePlayAtMs(timeMs)
    }

    const handleSeekToMsForAnnotation = (timeMs: number) => {
      handleSeekToMs(timeMs)
    }

    return (
      <CaptureGroupAnnotationPanel
        projectId={projectId}
        captureGroupId={captureGroupId}
        group={data.group}
        task={humanTask}
        schema={data.schema}
        viewRows={viewRows}
        activeCameraNumber={activeCameraNumber}
        activeClipAssetId={selectedStream?.clip_asset_id ?? null}
        assigneeActorId={session?.username ?? ''}
        canPrepareTask={Boolean(session) && data.taskVisibility === 'visible'}
        currentPlaybackTimeMs={currentPlaybackTimeMs}
        totalDurationMs={totalDurationMs}
        onPlaySegment={handlePlaySegmentForAnnotation}
        onPlayAtMs={handlePlayAtMsForAnnotation}
        onSeekToMs={handleSeekToMsForAnnotation}
        onDirtyChange={setHasUnsavedAnnotation}
        onTaskChange={data.setTask}
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
      {data.groupLoadState === 'loading' && !data.group ? (
        <div
          className="capture-group-workspace capture-group-workspace-skeleton"
          aria-hidden="true"
        >
          <div className="capture-group-video-column">
            <div className="video-stage-placeholder">
              <Skeleton.Node active style={{ width: '100%', height: '100%' }}>
                <span className="muted-text">视频预览准备中…</span>
              </Skeleton.Node>
            </div>
          </div>
          <div className="capture-group-action-column">
            <div className="form-surface working-surface skeleton-column-surface">
              <Skeleton active paragraph={{ rows: 6 }} />
            </div>
          </div>
          <div className="capture-group-inspector-column">
            <div className="form-surface working-surface skeleton-column-surface">
              <Skeleton active paragraph={{ rows: 8 }} />
            </div>
          </div>
        </div>
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
            <ErrorBoundary title="工作台局部异常">
              <div className="capture-group-video-column">
                <CaptureGroupFocusedView
                  viewRows={viewRows}
                  viewState={viewState}
                  task={humanTask}
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
            </ErrorBoundary>
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
        width={440}
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
        width={440}
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
