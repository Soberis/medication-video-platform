import { Alert, Button, Modal } from 'antd'
import { RefreshCw } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'

import { paths } from '@/app/paths'
import { useAuth } from '@/app/auth'
import { PageHeader } from '@/components/PageHeader'
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
import { HumanAnnotationExportButton } from '@/features/capture-group/HumanAnnotationExportButton'
import { HandLandmarkExportButton } from '@/features/capture-group/HandLandmarkExportButton'
import { useCaptureGroupDetailData } from '@/features/capture-group/use-capture-group-detail-data'
import { useCaptureGroupView } from '@/features/capture-group/use-capture-group-view'
import {
  captureGroupFocusFromLocation,
  captureGroupReviewCleaningFallbackTargetId,
  captureGroupTaskDestination,
  captureGroupFocusTargetIds,
  isHumanAnnotationExportReady,
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
  const focus = captureGroupFocusFromLocation(location.search, location.hash)
  const focusTargetId = focus ? captureGroupFocusTargetIds[focus] : null
  const fallbackFocusTargetId =
    focus === 'review' || focus === 'cleaning' ? captureGroupReviewCleaningFallbackTargetId : null
  const viewRows = useMemo(() => createGroupViewRows(data.group), [data.group])
  const selectedView = useMemo<GroupViewRow | null>(
    () => viewRows.find((view) => view.stream?.clip_asset_id === data.selectedClipAssetId) ?? null,
    [data.selectedClipAssetId, viewRows],
  )
  const [currentPlaybackTimeMs, setCurrentPlaybackTimeMs] = useState<number | null>(null)
  const viewState = useCaptureGroupView(projectId, selectedView)
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
  const eventTitle = data.group?.capture_group_key ?? `配药事件 · ${shortId(captureGroupId)}`

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
    if (!data.group || data.groupLoadState !== 'success' || !focusTargetId) return
    const timer = window.setTimeout(() => {
      const target =
        document.getElementById(focusTargetId) ??
        (fallbackFocusTargetId ? document.getElementById(fallbackFocusTargetId) : null)
      if (!target) return
      target.scrollIntoView?.({ block: 'start', behavior: 'auto' })
      if (target instanceof HTMLElement) target.focus({ preventScroll: true })
    }, 0)
    return () => window.clearTimeout(timer)
  }, [data.group, data.groupLoadState, data.refreshVersion, fallbackFocusTargetId, focusTargetId])

  const renderHumanLabelPanel = () => {
    if (!data.group || !humanTask) return null
    if (!taskDestination) {
      return (
        <section className="capture-group-human-label-status" aria-label="人工标签任务">
          <p className="app-eyebrow">可选人工标签</p>
          <h2>已有人工标签任务</h2>
          <p>{humanTaskStatusMessage(humanTask)}</p>
        </section>
      )
    }
    if (taskDestination.focus === 'annotation') {
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
          refreshVersion={data.refreshVersion}
          currentPlaybackTimeMs={currentPlaybackTimeMs}
          onTaskChange={data.setTask}
          onRefresh={data.refresh}
        />
      )
    }
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

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑导航">
        <Link to={{ pathname: paths.captureGroups(projectId), search: location.search }}>
          配药事件
        </Link>
        <span aria-hidden="true">/</span>
        <span>{eventIdentity}</span>
      </nav>
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
                size="small"
                type="primary"
                loading={isReceiptFinalizationPending}
                onClick={() => setIsReceiptConfirmationOpen(true)}
              >
                结束接收
              </Button>
            ) : null}
            {session?.role === 'ADMIN' && isHumanAnnotationExportReady(humanTask) ? (
              <HumanAnnotationExportButton projectId={projectId} captureGroupId={captureGroupId} />
            ) : null}
            {session?.role === 'ADMIN' ? (
              <HandLandmarkExportButton projectId={projectId} captureGroupId={captureGroupId} />
            ) : null}
            <Button
              size="small"
              icon={<RefreshCw aria-hidden="true" size={14} strokeWidth={1.8} />}
              loading={data.groupLoadState === 'loading'}
              onClick={() => void data.refresh()}
            >
              刷新事实
            </Button>
          </>
        }
      />
      {data.groupLoadState === 'loading' ? (
        <LoadingState>{data.group ? '正在刷新配药事件…' : '正在读取配药事件…'}</LoadingState>
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
              title="人工任务状态暂不可用；配药事件、播放器和机器证据仍可查看。"
            />
          ) : null}
          {humanTask && data.qualityErrorMessage ? (
            <Alert type="warning" showIcon title={data.qualityErrorMessage} />
          ) : null}
          {humanTask && data.taskErrorMessage ? (
            <Alert type="warning" showIcon title={data.taskErrorMessage} />
          ) : null}
          <div
            className={`capture-group-workspace${actionPanel ? '' : ' capture-group-workspace-machine-evidence'}`}
            data-stage={
              actionPanel
                ? taskDestination?.focus === 'annotation'
                  ? 'ANNOTATION'
                  : humanTask?.status === 'COMPLETED'
                    ? 'COMPLETED'
                    : undefined
                : undefined
            }
          >
            {actionPanel ? <div className="capture-group-action-column">{actionPanel}</div> : null}
            <div className="capture-group-video-column">
              <CaptureGroupFocusedView
                viewRows={viewRows}
                task={humanTask}
                viewReviews={data.viewReviews}
                viewState={viewState}
                onSelectView={(clipAssetId) => {
                  setCurrentPlaybackTimeMs(null)
                  data.setSelectedClipAssetId(clipAssetId)
                }}
                onPlaybackTimeChange={setCurrentPlaybackTimeMs}
                isCompleted={humanTask?.status === 'COMPLETED'}
              />
            </div>
          </div>
          {renderInspector()}
        </>
      ) : null}
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
  if (task.status === 'SUBMITTED') return '已提交人工修订，等待有权限的复核人员处理。'
  if (task.annotation_revision?.review?.decision === 'REJECTED') {
    return '复核已退回该修订，等待任务执行人根据意见返工。'
  }
  return '等待任务执行人处理。'
}
