import { Alert, Button, Modal } from 'antd'
import { RefreshCw } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'

import { PageHeader } from '@/components/PageHeader'
import { ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { paths } from '@/app/paths'
import { CaptureGroupAnnotationPanel } from '@/features/capture-group/CaptureGroupAnnotationPanel'
import { CaptureGroupFocusedView } from '@/features/capture-group/CaptureGroupFocusedView'
import { CaptureGroupInspector } from '@/features/capture-group/CaptureGroupInspector'
import { CaptureGroupReviewCleaningPanel } from '@/features/capture-group/CaptureGroupReviewCleaningPanel'
import {
  CaptureGroupOverview,
  CaptureGroupHeaderStatus,
} from '@/features/capture-group/CaptureGroupOverview'
import { CaptureGroupViewQualitySection } from '@/features/capture-group/CaptureGroupQualityPanel'
import { CaptureGroupViewRail } from '@/features/capture-group/CaptureGroupViewRail'
import {
  createGroupViewRows,
  type GroupViewRow,
} from '@/features/capture-group/capture-group-detail-model'
import { shortId } from '@/features/catalog/catalog-presentation'
import {
  captureGroupFocusFromLocation,
  captureGroupReviewCleaningFallbackTargetId,
  captureGroupFocusTargetIds,
} from '@/features/capture-group-presentation'
import { useCaptureGroupDetailData } from '@/features/capture-group/use-capture-group-detail-data'
import { useCaptureGroupView } from '@/features/capture-group/use-capture-group-view'

export default function CaptureGroupDetailView() {
  const { projectId = '', captureGroupId = '' } = useParams()
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
  const viewState = useCaptureGroupView(projectId, selectedView)
  const selectedReview = viewState.selectedStream
    ? (data.viewReviews[viewState.selectedStream.clip_asset_id] ?? null)
    : null
  const isReceiptFinalizationPending = data.isFinalizingReceipt || isReceiptFinalizePending

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

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑导航">
        <Link to={{ pathname: paths.captureGroups(projectId), search: location.search }}>
          配药事件
        </Link>
        <span aria-hidden="true">/</span>
        <span>{data.group?.capture_group_key ?? shortId(captureGroupId)}</span>
      </nav>
      <PageHeader
        className="capture-group-page-header capture-group-detail-header"
        eyebrow="配药事件"
        titleId="capture-group-detail-title"
        title={data.group?.capture_group_key ?? '配药事件详情'}
        description={data.group ? <CaptureGroupHeaderStatus group={data.group} /> : undefined}
        actions={
          <>
            {data.group?.receipt_state === 'RECEIVING' ? (
              <Button
                ref={receiptFinalizeTrigger}
                size="small"
                loading={data.isFinalizingReceipt}
                disabled={isReceiptFinalizationPending}
                onClick={() => setIsReceiptConfirmationOpen(true)}
              >
                结束接收
              </Button>
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
          <CaptureGroupOverview
            group={data.group}
            groupQuality={data.groupQuality}
            groupQualityVisibility={data.groupQualityVisibility}
          />
          {data.qualityErrorMessage ? (
            <Alert type="warning" showIcon title={data.qualityErrorMessage} />
          ) : null}
          {data.taskErrorMessage ? (
            <Alert type="warning" showIcon title={data.taskErrorMessage} />
          ) : null}
          <div className="capture-group-workspace">
            <div className="capture-group-main-column">
              <CaptureGroupViewRail
                group={data.group}
                viewRows={viewRows}
                selectedClipAssetId={data.selectedClipAssetId}
                viewReviews={data.viewReviews}
                candidateResponse={viewState.candidateResponse}
                candidateState={viewState.candidateState}
                candidateTone={viewState.candidateTone}
                onSelectView={data.setSelectedClipAssetId}
              />
              <CaptureGroupFocusedView
                viewRows={viewRows}
                task={data.task}
                viewReviews={data.viewReviews}
                viewState={viewState}
                onSelectView={data.setSelectedClipAssetId}
              />
              <CaptureGroupViewQualitySection
                projectId={projectId}
                selectedStream={viewState.selectedStream}
                selectedDetail={viewState.selectedDetail}
                selectedReview={selectedReview}
                viewReviews={data.viewReviews}
                viewReviewErrors={data.viewReviewErrors}
                viewQualityLoading={data.viewQualityLoading}
                onReviewSaved={data.updateViewReview}
                onRefresh={data.refresh}
              />
              <CaptureGroupAnnotationPanel
                projectId={projectId}
                captureGroupId={captureGroupId}
                group={data.group}
                groupQuality={data.groupQuality}
                task={data.task}
                taskVisibility={data.taskVisibility}
                schema={data.schema}
                viewRows={viewRows}
                viewReviews={data.viewReviews}
                refreshVersion={data.refreshVersion}
                onTaskChange={(nextTask) => data.setTask(nextTask)}
                onRefresh={data.refresh}
              />
              <CaptureGroupReviewCleaningPanel
                projectId={projectId}
                group={data.group}
                groupQuality={data.groupQuality}
                groupQualityVisibility={data.groupQualityVisibility}
                task={data.task}
                schema={data.schema}
                onRefresh={data.refresh}
              />
            </div>
            <CaptureGroupInspector
              projectId={projectId}
              captureGroupId={captureGroupId}
              group={data.group}
              groupQuality={data.groupQuality}
              groupQualityVisibility={data.groupQualityVisibility}
              qualityHistory={data.qualityHistory}
              refreshVersion={data.refreshVersion}
              selectedView={selectedView}
              task={data.task}
              onRefresh={data.refresh}
            />
          </div>
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
        <p>系统将根据当前已收到的机位形成完整或有缺失的接收结果，并进入后续事件质量判断。</p>
      </Modal>
    </>
  )
}
