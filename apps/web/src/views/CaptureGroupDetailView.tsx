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
import { CaptureGroupHeaderStatus } from '@/features/capture-group/CaptureGroupOverview'
import {
  CaptureGroupQualityPanel,
  CaptureGroupViewQualitySection,
} from '@/features/capture-group/CaptureGroupQualityPanel'
import { MedicationEventCurrentAction } from '@/features/capture-group/MedicationEventCurrentAction'
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
import {
  deriveMedicationEventWorkflow,
  type MedicationEventWorkflowFacts,
  type MedicationEventWorkflowModel,
  type MedicationEventWorkflowPrimaryAction,
} from '@/features/capture-group/medication-event-workflow'
import type { AnnotationTask } from '@/api/annotation'
import type { CaptureGroup, CaptureGroupQualityReview } from '@/api/capture-groups'
import type { ClipAssetRead } from '@/api/catalog'
import type { QualityReview } from '@/api/quality'
import {
  useCaptureGroupDetailData,
  type CaptureGroupDetailRefreshOptions,
} from '@/features/capture-group/use-capture-group-detail-data'
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
  const workflow = useMemo<MedicationEventWorkflowModel | null>(
    () =>
      data.group
        ? deriveMedicationEventWorkflow(
            createMedicationEventWorkflowFacts({
              group: data.group,
              groupQuality: data.groupQuality,
              groupQualityVisibility: data.groupQualityVisibility,
              task: data.task,
              taskVisibility: data.taskVisibility,
              viewRows,
              viewReviews: data.viewReviews,
              viewReviewErrors: data.viewReviewErrors,
              viewQualityLoading: data.viewQualityLoading,
              annotationSchemaAvailable: Boolean(data.schema?.labels.length),
              selectedView,
              selectedDetail: viewState.selectedDetail,
            }),
          )
        : null,
    [
      data.group,
      data.groupQuality,
      data.groupQualityVisibility,
      data.task,
      data.taskVisibility,
      data.schema,
      data.viewQualityLoading,
      data.viewReviewErrors,
      data.viewReviews,
      selectedView,
      viewRows,
      viewState.selectedDetail,
    ],
  )
  const isReviewWorkflowAction =
    workflow?.primaryAction?.target === 'review' ||
    workflow?.primaryAction?.target === 'training-eligibility'
  const primaryActionHasForm =
    workflow?.primaryAction?.target === 'view-quality' ||
    workflow?.primaryAction?.target === 'group-quality' ||
    workflow?.primaryAction?.target === 'annotation'
  const isCompletedWorkflow = Boolean(
    workflow?.steps.length && workflow.steps.every((step) => step.state === 'completed'),
  )

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

  const handlePrimaryAction = (action: MedicationEventWorkflowPrimaryAction) => {
    if (action.key === 'finalize-receipt') {
      setIsReceiptConfirmationOpen(true)
      return
    }
    const targetId = {
      'view-quality': 'capture-group-view-quality',
      'group-quality': 'capture-group-group-quality',
      annotation: 'capture-group-annotation',
      review: 'capture-group-review-cleaning',
      'training-eligibility': 'capture-group-review-cleaning',
      receipt: '',
    }[action.target]
    if (!targetId) return
    const target = document.getElementById(targetId)
    if (!target) return
    target.scrollIntoView?.({ block: 'start', behavior: 'smooth' })
    if (target instanceof HTMLElement) target.focus({ preventScroll: true })
  }

  const renderActionForm = () => {
    if (!data.group || !workflow?.primaryAction) return null
    return (
      <CaptureGroupActionForm
        action={workflow.primaryAction}
        projectId={projectId}
        captureGroupId={captureGroupId}
        group={data.group}
        groupQuality={data.groupQuality}
        groupQualityVisibility={data.groupQualityVisibility}
        qualityHistory={data.qualityHistory}
        refreshVersion={data.refreshVersion}
        task={data.task}
        taskVisibility={data.taskVisibility}
        schema={data.schema}
        viewRows={viewRows}
        viewReviews={data.viewReviews}
        viewReviewErrors={data.viewReviewErrors}
        viewQualityLoading={data.viewQualityLoading}
        selectedStream={viewState.selectedStream}
        selectedDetail={viewState.selectedDetail}
        selectedReview={selectedReview}
        onReviewSaved={data.updateViewReview}
        onTaskChange={(nextTask) => data.setTask(nextTask)}
        onRefresh={data.refresh}
      />
    )
  }

  const renderReviewCleaningPanel = () => {
    if (!data.group) return null
    return (
      <CaptureGroupReviewCleaningPanel
        projectId={projectId}
        group={data.group}
        groupQuality={data.groupQuality}
        groupQualityVisibility={data.groupQualityVisibility}
        task={data.task}
        schema={data.schema}
        onRefresh={data.refresh}
      />
    )
  }

  const renderInspector = () => {
    if (!data.group) return null
    const inspector = (
      <CaptureGroupInspector
        projectId={projectId}
        captureGroupId={captureGroupId}
        group={data.group}
        groupQuality={data.groupQuality}
        groupQualityVisibility={data.groupQualityVisibility}
        qualityHistory={data.qualityHistory}
        selectedView={selectedView}
        task={data.task}
        taskVisibility={data.taskVisibility}
        isCompleted={isCompletedWorkflow}
      />
    )
    return isCompletedWorkflow ? (
      <details className="detail-disclosure capture-group-completed-inspector-details">
        <summary>事实、历史与审计</summary>
        <div className="detail-disclosure-content">{inspector}</div>
      </details>
    ) : (
      inspector
    )
  }

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
        titleId="capture-group-detail-title"
        title={data.group?.capture_group_key ?? '配药事件详情'}
        description={data.group ? <CaptureGroupHeaderStatus group={data.group} /> : undefined}
        actions={
          <>
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
          {data.qualityErrorMessage ? (
            <Alert type="warning" showIcon title={data.qualityErrorMessage} />
          ) : null}
          {data.taskErrorMessage ? (
            <Alert type="warning" showIcon title={data.taskErrorMessage} />
          ) : null}
          {workflow ? (
            isCompletedWorkflow ? (
              <div className="capture-group-completed-workspace">
                <MedicationEventCurrentAction workflow={workflow} />
                {renderReviewCleaningPanel()}
                <CaptureGroupFocusedView
                  viewRows={viewRows}
                  task={data.task}
                  viewReviews={data.viewReviews}
                  viewState={viewState}
                  onSelectView={data.setSelectedClipAssetId}
                  isCompleted
                />
              </div>
            ) : (
              <div className="capture-group-workspace">
                <div className="capture-group-video-column">
                  <CaptureGroupFocusedView
                    viewRows={viewRows}
                    task={data.task}
                    viewReviews={data.viewReviews}
                    viewState={viewState}
                    onSelectView={data.setSelectedClipAssetId}
                  />
                </div>
                <div className="capture-group-action-column">
                  <MedicationEventCurrentAction
                    workflow={workflow}
                    primaryActionHasForm={primaryActionHasForm}
                    primaryActionDisabled={
                      workflow.primaryAction?.key === 'finalize-receipt' &&
                      isReceiptFinalizationPending
                    }
                    primaryActionLoading={
                      workflow.primaryAction?.key === 'finalize-receipt' &&
                      isReceiptFinalizationPending
                    }
                    primaryActionRef={
                      workflow.primaryAction?.key === 'finalize-receipt'
                        ? receiptFinalizeTrigger
                        : undefined
                    }
                    onPrimaryAction={handlePrimaryAction}
                  />
                  {!isReviewWorkflowAction ? renderActionForm() : null}
                </div>
              </div>
            )
          ) : null}
          {!isCompletedWorkflow && isReviewWorkflowAction ? renderActionForm() : null}
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
        <p>系统将根据当前已收到的机位形成完整或有缺失的接收结果，并进入后续事件质量判断。</p>
      </Modal>
    </>
  )
}

interface CaptureGroupActionFormProps {
  action: MedicationEventWorkflowPrimaryAction
  projectId: string
  captureGroupId: string
  group: CaptureGroup
  groupQuality: CaptureGroupQualityReview | null
  groupQualityVisibility: MedicationEventWorkflowFacts['group_quality_visibility']
  qualityHistory: CaptureGroupQualityReview[]
  refreshVersion: number
  task: AnnotationTask | null
  taskVisibility: MedicationEventWorkflowFacts['task_visibility']
  schema: ReturnType<typeof useCaptureGroupDetailData>['schema']
  viewRows: GroupViewRow[]
  viewReviews: Record<string, QualityReview>
  viewReviewErrors: Record<string, string>
  viewQualityLoading: boolean
  selectedStream: GroupViewRow['stream']
  selectedDetail: ClipAssetRead | null
  selectedReview: QualityReview | null
  onReviewSaved: (review: QualityReview) => void
  onTaskChange: (task: AnnotationTask) => void
  onRefresh: (options?: CaptureGroupDetailRefreshOptions) => Promise<boolean>
}

function CaptureGroupActionForm({
  action,
  projectId,
  captureGroupId,
  group,
  groupQuality,
  groupQualityVisibility,
  qualityHistory,
  refreshVersion,
  task,
  taskVisibility,
  schema,
  viewRows,
  viewReviews,
  viewReviewErrors,
  viewQualityLoading,
  selectedStream,
  selectedDetail,
  selectedReview,
  onReviewSaved,
  onTaskChange,
  onRefresh,
}: CaptureGroupActionFormProps) {
  if (action.target === 'view-quality') {
    return (
      <CaptureGroupViewQualitySection
        projectId={projectId}
        selectedStream={selectedStream}
        selectedDetail={selectedDetail}
        selectedReview={selectedReview}
        viewReviews={viewReviews}
        viewReviewErrors={viewReviewErrors}
        viewQualityLoading={viewQualityLoading}
        onReviewSaved={onReviewSaved}
        onRefresh={onRefresh}
      />
    )
  }
  if (action.target === 'group-quality' || action.target === 'receipt') {
    return (
      <CaptureGroupQualityPanel
        projectId={projectId}
        captureGroupId={captureGroupId}
        group={group}
        groupQuality={groupQuality}
        groupQualityVisibility={groupQualityVisibility}
        qualityHistory={qualityHistory}
        refreshVersion={refreshVersion}
        onRefresh={onRefresh}
        isSecondary={action.target === 'receipt'}
      />
    )
  }
  if (action.target === 'annotation') {
    return (
      <CaptureGroupAnnotationPanel
        projectId={projectId}
        captureGroupId={captureGroupId}
        group={group}
        groupQuality={groupQuality}
        task={task}
        taskVisibility={taskVisibility}
        schema={schema}
        viewRows={viewRows}
        viewReviews={viewReviews}
        refreshVersion={refreshVersion}
        onTaskChange={onTaskChange}
        onRefresh={onRefresh}
      />
    )
  }
  if (action.target === 'review' || action.target === 'training-eligibility') {
    return (
      <CaptureGroupReviewCleaningPanel
        projectId={projectId}
        group={group}
        groupQuality={groupQuality}
        groupQualityVisibility={groupQualityVisibility}
        task={task}
        schema={schema}
        onRefresh={onRefresh}
      />
    )
  }
  return null
}

interface WorkflowFactInput {
  group: CaptureGroup
  groupQuality: CaptureGroupQualityReview | null
  groupQualityVisibility: MedicationEventWorkflowFacts['group_quality_visibility']
  task: AnnotationTask | null
  taskVisibility: MedicationEventWorkflowFacts['task_visibility']
  viewRows: GroupViewRow[]
  viewReviews: Record<string, QualityReview>
  viewReviewErrors: Record<string, string>
  viewQualityLoading: boolean
  annotationSchemaAvailable: boolean
  selectedView: GroupViewRow | null
  selectedDetail: ClipAssetRead | null
}

function createMedicationEventWorkflowFacts({
  group,
  groupQuality,
  groupQualityVisibility,
  task,
  taskVisibility,
  viewRows,
  viewReviews,
  viewReviewErrors,
  viewQualityLoading,
  annotationSchemaAvailable,
  selectedView,
  selectedDetail,
}: WorkflowFactInput): MedicationEventWorkflowFacts {
  return {
    receipt_state: group.receipt_state,
    view_quality: viewRows.map((view) => {
      const stream = view.stream
      const review = stream ? viewReviews[stream.clip_asset_id] : null
      const visibility = !stream
        ? 'loaded'
        : viewReviewErrors[stream.clip_asset_id]
          ? 'unavailable'
          : viewQualityLoading
            ? 'loading'
            : review
              ? 'loaded'
              : 'unavailable'
      const canDecide = Boolean(
        stream &&
          selectedView?.stream?.clip_asset_id === stream.clip_asset_id &&
          selectedDetail?.status === 'INGESTED',
      )
      const mediaUnavailable =
        stream?.stream_status === 'INGEST_FAILED' || stream?.stream_status === 'UNAVAILABLE'
      const mediaPending = Boolean(
        stream && stream.stream_status !== 'INGESTED' && !mediaUnavailable,
      )
      return {
        camera_number: view.cameraNumber,
        clip_asset_id: stream?.clip_asset_id ?? null,
        present: Boolean(stream),
        visibility,
        decision: review?.decision ?? 'UNREVIEWED',
        can_decide: canDecide,
        blocker: mediaUnavailable
          ? `机位 ${view.cameraNumber} 的媒体不可用，暂不能判断机位质量。`
          : undefined,
        waiting_reason: mediaPending
          ? `机位 ${view.cameraNumber} 的媒体正在处理，等待媒体事实完成。`
          : stream && !canDecide
            ? '等待当前机位进入可判断上下文。'
            : undefined,
      }
    }),
    group_quality_visibility: groupQualityVisibility,
    group_quality: groupQuality,
    task_visibility: taskVisibility,
    task_status: taskVisibility === 'visible' ? (task?.status ?? null) : null,
    primary_view: task
      ? {
          clip_asset_id: task.primary_clip_asset_id ?? null,
          camera_number: task.primary_camera_number ?? null,
        }
      : null,
    annotation_revision: task?.annotation_revision ?? null,
    review: task?.annotation_revision?.review ?? null,
    cleaning: task?.annotation_revision?.capture_group_cleaning ?? null,
    can_select_primary: Boolean(task?.can_select_primary),
    can_submit: Boolean(task?.can_submit),
    can_review: Boolean(task?.can_review),
    can_recheck_capture_group_cleaning: Boolean(task?.can_recheck_capture_group_cleaning),
    can_finalize_receipt: group.receipt_state === 'RECEIVING',
    can_decide_group_quality: groupQualityVisibility === 'loaded' && Boolean(groupQuality),
    annotation_schema_available: annotationSchemaAvailable,
  }
}
