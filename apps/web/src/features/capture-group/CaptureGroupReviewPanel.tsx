import { Alert, App as AntdApp, Button, Checkbox, Input, Tooltip } from 'antd'
import { Check, CircleAlert, FileCheck2, History, Play } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { paths } from '@/app/paths'

import {
  reviewAnnotationRevision,
  type AnnotationLabelSchema,
  type AnnotationReview,
  type AnnotationSegment,
  type AnnotationTask,
  type CaptureGroupCleaning,
  type CaptureGroupEvidence,
  type CaptureGroupViewSet,
} from '@/api/annotation'
import type { CaptureGroup } from '@/api/capture-groups'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { MetadataList } from '@/components/data/MetadataList'
import { Section } from '@/components/workspace/Section'
import {
  cleaningLabel,
  cleaningTone,
  formatAnnotationTime,
  getActionDisplayName,
} from '@/features/capture-group/capture-group-detail-model'
import {
  receiptStateLabels,
  syncReliabilityDisplayLabel,
} from '@/features/capture-group-presentation'
import { formatDateTime } from '@/features/catalog/catalog-presentation'
import type { CaptureGroupDetailRefreshOptions } from '@/features/capture-group/use-capture-group-detail-data'

type CleaningResult = CaptureGroupCleaning

export interface CaptureGroupReviewPanelProps {
  projectId: string
  group: CaptureGroup
  task: AnnotationTask | null
  schema: AnnotationLabelSchema | null
  onRefresh: (options?: CaptureGroupDetailRefreshOptions) => Promise<boolean>
  onPlaySegment?: (startMs: number, endMs: number) => void
  activeCameraNumber?: number | null
  onSelectCamera?: (cameraNumber: number) => void
}

export function CaptureGroupReviewPanel({
  projectId,
  group,
  task,
  schema,
  onRefresh,
  onPlaySegment,
  activeCameraNumber,
  onSelectCamera,
}: CaptureGroupReviewPanelProps) {
  const navigate = useNavigate()
  const { message, modal } = AntdApp.useApp()
  const [reviewingDecision, setReviewingDecision] = useState<AnnotationReview['decision'] | null>(
    null,
  )
  const revision = task?.annotation_revision ?? null
  const review = revision?.review ?? null
  const viewSet = revision?.confirmed_view_set ?? task?.confirmed_view_set ?? null
  const inputVersion = revision?.input_version ?? task?.group_input_version ?? null
  const qualifiedViews = Array.isArray(review?.qualified_views)
    ? review.qualified_views
    : qualifiedViewsFromViewSet(viewSet)
  const rejectedMissingViews = Array.isArray(review?.rejected_missing_views)
    ? review.rejected_missing_views
    : rejectedViewsFromViewSet(viewSet)
  const candidateEvidence = Array.isArray(review?.candidate_evidence)
    ? review.candidate_evidence
    : []
  const canReview = Boolean(task?.can_review && revision)
  const cleaning = revision?.capture_group_cleaning ?? null
  const groupDocument =
    revision?.annotation_document.schema_version === 'annotation-document.v2'
      ? revision.annotation_document
      : null
  const revisionCameraViews = groupDocument?.camera_views ?? null
  const cameraViews = revisionCameraViews ?? task?.camera_views ?? []
  const cameraViewState = (cameraNumber: number) => {
    const revisionView = revisionCameraViews?.find((view) => view.camera_number === cameraNumber)
    if (revisionView) return revisionView.included ? 'SUBMITTED' : 'EXCLUDED'
    return task?.camera_views?.find((view) => view.camera_number === cameraNumber)?.state
  }
  const cameraNumbers = [
    ...new Set([
      ...group.expected_camera_numbers,
      ...cameraViews.map((view) => view.camera_number),
      ...(viewSet?.views ?? []).map((view) => view.camera_number),
    ]),
  ].sort((a, b) => a - b)
  const revisionReviewKey = [
    revision?.annotation_revision_id ?? 'no-revision',
    review?.decision ?? 'no-review',
    ...(review?.return_camera_numbers ?? []),
  ].join(':')
  const [returnCameraSelection, setReturnCameraSelection] = useState<{
    key: string
    numbers: number[]
  }>({
    key: revisionReviewKey,
    numbers: review?.return_camera_numbers ?? [],
  })
  const returnCameraNumbers =
    returnCameraSelection.key === revisionReviewKey
      ? returnCameraSelection.numbers
      : (review?.return_camera_numbers ?? [])
  const allCamerasSubmittedOrExcluded =
    cameraNumbers.length > 0 &&
    cameraNumbers.every((number) => {
      const state = cameraViewState(number)
      return state === 'SUBMITTED' || state === 'EXCLUDED'
    })
  const unsubmittedCameraNumbers = cameraNumbers.filter((number) => {
    const state = cameraViewState(number)
    return state !== 'SUBMITTED' && state !== 'EXCLUDED'
  })
  const canFinalizeReview = Boolean(canReview && allCamerasSubmittedOrExcluded)
  const isReviewApproved = review?.decision === 'APPROVED'
  const [isCompletedDetailOpen, setIsCompletedDetailOpen] = useState(false)
  const [expandedReviewCameras, setExpandedReviewCameras] = useState<number[]>([])

  const includedCameraNumbers = cameraNumbers.filter(
    (number) => cameraViewState(number) === 'SUBMITTED',
  )
  const excludedCameraNumbers = cameraNumbers.filter(
    (number) => cameraViewState(number) === 'EXCLUDED',
  )
  const totalApprovedSegments = cameraViews.reduce((sum, view) => {
    const isIncluded = 'included' in view ? view.included : view.state === 'SUBMITTED'
    return sum + (isIncluded ? view.segments.length : 0)
  }, 0)
  const cameraSummaryDetail = [
    includedCameraNumbers.length > 0
      ? `机位 ${includedCameraNumbers.join('、')} 参与（共 ${totalApprovedSegments} 段动作）`
      : null,
    excludedCameraNumbers.length > 0 ? `机位 ${excludedCameraNumbers.join('、')} 已排除` : null,
  ]
    .filter(Boolean)
    .join('，')

  function updateReturnCamera(cameraNumber: number, shouldReturn: boolean) {
    setReturnCameraSelection((selection) => {
      const current =
        selection.key === revisionReviewKey
          ? selection.numbers
          : (review?.return_camera_numbers ?? [])
      return {
        key: revisionReviewKey,
        numbers: shouldReturn
          ? [...new Set([...current, cameraNumber])]
          : current.filter((number) => number !== cameraNumber),
      }
    })
  }

  function openReview(decision: AnnotationReview['decision']) {
    if (!task || !revision || !task.can_review || reviewingDecision) return
    if (!canFinalizeReview) {
      void message.warning('请先处理完所有机位：每个机位都必须提交标注或排除。')
      return
    }
    if (decision === 'REJECTED' && returnCameraNumbers.length === 0) {
      void message.warning('请至少选择一个需要退回的机位。')
      return
    }
    let reviewNote = ''
    const approved = decision === 'APPROVED'
    modal.confirm({
      title: approved ? '通过人工标签复核' : '退回人工标签修订',
      content: approved ? (
        <p>确认本次多机位动作标注正确并予以通过？通过后将生成正式版本用于后续质检与导出。</p>
      ) : (
        <div className="capture-group-review-confirmation">
          <p>退回这次人工标签修订，选中的机位将重新开放编辑，其余机位结果保留。</p>
          <Input.TextArea
            aria-label="退回修改说明（可选）"
            maxLength={1000}
            rows={3}
            placeholder="补充需要修改的内容（可选）"
            onChange={(event) => {
              reviewNote = event.target.value
            }}
          />
        </div>
      ),
      okText: approved ? '通过复核' : '退回修改',
      cancelText: '取消',
      okButtonProps: { danger: !approved },
      async onOk() {
        setReviewingDecision(decision)
        try {
          await reviewAnnotationRevision(
            projectId,
            revision.annotation_revision_id,
            decision,
            reviewNote,
            {
              reviewedCameraNumbers:
                decision === 'REJECTED'
                  ? cameraNumbers.filter((number) => !returnCameraNumbers.includes(number))
                  : cameraNumbers,
              returnCameraNumbers: decision === 'REJECTED' ? returnCameraNumbers : [],
            },
          )
          const refreshed = await onRefresh()
          void (refreshed
            ? message.success(approved ? '人工标签复核已通过。' : '已退回人工标签修订。')
            : message.warning(
                approved
                  ? '复核已通过，但最新事实暂未刷新；请手动刷新确认。'
                  : '修订已退回，但最新事实暂未刷新；请手动刷新确认。',
              ))
        } catch (error) {
          void message.error(
            error instanceof Error ? error.message : '复核暂未提交，请刷新后重试。',
          )
        } finally {
          setReviewingDecision(null)
        }
      },
    })
  }

  const evidenceAndHistory = revision ? (
    <>
      <ViewSetSummary viewSet={viewSet} />

      <div className="capture-group-review-evidence-grid">
        <EvidenceViewList
          title="参与机位"
          views={qualifiedViews}
          emptyText="当前没有已确认的参与机位。"
          tone="success"
        />
        <EvidenceViewList
          title="排除 / 缺失机位"
          views={rejectedMissingViews}
          emptyText="没有排除或缺失机位。"
          tone="warning"
        />
      </div>

      <div className="capture-group-review-evidence-block">
        <div className="capture-group-review-subheading">
          <div>
            <h3>相关手部检测证据</h3>
          </div>
          <span>{candidateEvidence.length} 条已固定证据</span>
        </div>
        {candidateEvidence.length ? (
          <ul className="capture-group-evidence-list" aria-label="相关手部检测证据">
            {candidateEvidence.map((evidence, index) => (
              <li key={evidenceKey(evidence, index)}>
                <strong>{evidenceViewName(evidence, index)}</strong>
                <span>
                  检测结果 {numberValue(evidence, 'candidate_count') ?? 0} 组
                  {stringValue(evidence, 'algorithm_key')
                    ? ` · ${stringValue(evidence, 'algorithm_key')}`
                    : ''}
                </span>
                <small>
                  {stringValue(evidence, 'algorithm_version') ?? '算法版本未提供'}
                  {stringValue(evidence, 'model_sha256')
                    ? ` · model ${shortEvidenceId(stringValue(evidence, 'model_sha256')!)}`
                    : ''}
                </small>
              </li>
            ))}
          </ul>
        ) : (
          <p className="form-hint">
            当前修订尚无已固定的手部检测证据；机位工作区中的检测结果仅供人工参考，不作为正式标注或质量结论。
          </p>
        )}
      </div>

      <div className="capture-group-review-annotation-history">
        <div className="capture-group-review-subheading">
          <div>
            <h3>标注备注与历史</h3>
          </div>
          <History aria-hidden="true" size={16} strokeWidth={1.8} />
        </div>
        <ol aria-label="配药动作修订与复核历史">
          <li>
            <strong>修订 {revision.revision_number} · 已提交</strong>
            <span>
              由 {revision.submitted_by} 提交于 {formatDateTime(revision.submitted_at)}
            </span>
            {revision.annotation_document?.note ? (
              <p>提交备注：{String(revision.annotation_document.note)}</p>
            ) : null}
          </li>
          {review ? (
            <li>
              <strong>复核决定 · {review.decision === 'APPROVED' ? '已通过' : '已退回'}</strong>
              <span>
                由 {review.reviewed_by} 决定于 {formatDateTime(review.reviewed_at)}
              </span>
              {review.review_note ? <p>复核说明：{review.review_note}</p> : null}
            </li>
          ) : null}
        </ol>
      </div>
    </>
  ) : null

  return (
    <Section
      className="capture-group-review-section capture-group-workflow-focus-target"
      id="capture-group-review"
      tabIndex={-1}
      title="标注复核"
      titleId="capture-group-review-title"
    >
      {isReviewApproved ? (
        <div className="capture-group-review-approved-banner">
          <div className="banner-status">
            <StatusIndicator tone="success">人工复核已通过</StatusIndicator>
            <p role="status">
              {group.expected_camera_count > 1
                ? `合组 ${group.received_camera_count} 个已接收机位的独立标注均已完成复核${cameraSummaryDetail ? `：${cameraSummaryDetail}` : ''}。可返回视频列表直接选择导出。`
                : '标注已完成人工复核确认，可返回视频列表选择导出。'}
            </p>
          </div>
          <div className="capture-group-approved-actions">
            <Button
              type="primary"
              size="small"
              onClick={() => navigate(paths.captureGroups(projectId))}
            >
              前往导出数据
            </Button>
            <Button
              type="text"
              size="small"
              aria-controls={isCompletedDetailOpen ? 'capture-group-review-details' : undefined}
              aria-expanded={isCompletedDetailOpen}
              onClick={() => setIsCompletedDetailOpen((open) => !open)}
            >
              {isCompletedDetailOpen ? '收起复核详情' : '查看复核详情'}
            </Button>
          </div>
        </div>
      ) : null}
      {revision && canReview && !review ? (
        <div className="capture-group-review-sticky-header">
          <div className="capture-group-review-summary-row">
            <div className="capture-group-review-summary-info">
              <StatusIndicator tone={allCamerasSubmittedOrExcluded ? 'success' : 'warning'}>
                {allCamerasSubmittedOrExcluded
                  ? `全组 ${cameraNumbers.length} 个机位标注已完备`
                  : `机位待就绪（剩余 ${unsubmittedCameraNumbers.length} 个机位待处理）`}
              </StatusIndicator>
            </div>
            <div className="capture-group-review-actions" aria-label="人工标签复核操作">
              <Tooltip
                title={
                  !allCamerasSubmittedOrExcluded
                    ? `机位 ${unsubmittedCameraNumbers.join('、')} 尚未处于提交或排除状态`
                    : undefined
                }
              >
                <span>
                  <Button
                    type="primary"
                    loading={reviewingDecision === 'APPROVED'}
                    disabled={reviewingDecision !== null || !canFinalizeReview}
                    onClick={() => openReview('APPROVED')}
                  >
                    通过复核
                  </Button>
                </span>
              </Tooltip>
              <Button
                danger
                loading={reviewingDecision === 'REJECTED'}
                disabled={
                  reviewingDecision !== null ||
                  !canFinalizeReview ||
                  returnCameraNumbers.length === 0
                }
                onClick={() => openReview('REJECTED')}
              >
                退回修改
              </Button>
            </div>
          </div>
          {!canFinalizeReview && !allCamerasSubmittedOrExcluded && (
            <div className="capture-group-review-disabled-hint" role="status">
              <span className="hint-warning">
                机位 {unsubmittedCameraNumbers.join('、')} 尚未提交或排除标注。
              </span>
            </div>
          )}
        </div>
      ) : null}
      {revision && cameraNumbers.length > 0 && (!isReviewApproved || isCompletedDetailOpen) ? (
        <section className="capture-group-review-camera-lanes" aria-label="逐机位复核">
          <div className="capture-group-review-subheading">
            <div>
              <h3>机位复核</h3>
            </div>
            <span>{cameraNumbers.length} 个机位</span>
          </div>
          {!isReviewApproved ? (
            <p className="form-hint">
              逐机位查看动作段；需要修改时勾选“退回此机位”，然后点击“退回修改”。
            </p>
          ) : null}
          {cameraNumbers.map((cameraNumber) => {
            const cameraView = cameraViews.find((view) => view.camera_number === cameraNumber)
            const state = cameraViewState(cameraNumber)
            const segmentCount = cameraView?.segments.length ?? 0
            const isSegmentsExpanded = expandedReviewCameras.includes(cameraNumber)
            const canReturnLane = state === 'SUBMITTED' || state === 'EXCLUDED'
            const laneStatus =
              state === 'SUBMITTED'
                ? '已提交'
                : state === 'DRAFT'
                  ? '草稿'
                  : state === 'EXCLUDED'
                    ? '已排除'
                    : '待处理'
            return (
              <article className="capture-group-review-camera-lane" key={cameraNumber}>
                <div className="capture-group-review-camera-lane-heading">
                  <div>
                    <h4>机位 {cameraNumber}</h4>
                    <StatusIndicator
                      tone={
                        state === 'SUBMITTED'
                          ? 'success'
                          : state === 'EXCLUDED'
                            ? 'warning'
                            : 'neutral'
                      }
                    >
                      {laneStatus}
                    </StatusIndicator>
                  </div>
                  <div className="capture-group-review-camera-lane-controls">
                    {canReturnLane && canReview && !review ? (
                      <Checkbox
                        checked={returnCameraNumbers.includes(cameraNumber)}
                        onChange={(event) => updateReturnCamera(cameraNumber, event.target.checked)}
                      >
                        退回此机位
                      </Checkbox>
                    ) : null}
                  </div>
                </div>
                <div className="capture-group-review-camera-segments-block">
                  <div className="capture-group-review-camera-segments-heading">
                    <span>动作段</span>
                    <span>{segmentCount ? `${segmentCount} 段` : '无动作段'}</span>
                  </div>
                  {segmentCount ? (
                    <>
                      <ol className="capture-group-review-camera-segments">
                        {cameraView!.segments
                          .slice(0, isSegmentsExpanded ? undefined : 6)
                          .map((segment, index) => (
                            <li key={segment.segment_id}>
                              <span className="capture-group-time-pill">
                                {formatAnnotationTime(segment.start_ms) ?? '时间暂不可用'} →{' '}
                                {formatAnnotationTime(segment.end_ms) ?? '时间暂不可用'}
                              </span>
                              <span>{getActionDisplayName(segment.label, schema)}</span>
                              <Button
                                type="text"
                                size="small"
                                icon={<Play size={12} />}
                                disabled={!onPlaySegment && !onSelectCamera}
                                onClick={() =>
                                  activeCameraNumber === cameraNumber
                                    ? onPlaySegment?.(segment.start_ms, segment.end_ms)
                                    : onSelectCamera?.(cameraNumber)
                                }
                              >
                                {activeCameraNumber === cameraNumber ? '播放' : '切换机位'}
                              </Button>
                              <span className="visually-hidden">
                                机位 {cameraNumber} 第 {index + 1} 段
                              </span>
                            </li>
                          ))}
                      </ol>
                      {segmentCount > 6 ? (
                        <Button
                          type="text"
                          size="small"
                          className="capture-group-review-expand-segments"
                          onClick={() =>
                            setExpandedReviewCameras((current) =>
                              isSegmentsExpanded
                                ? current.filter((number) => number !== cameraNumber)
                                : [...current, cameraNumber],
                            )
                          }
                        >
                          {isSegmentsExpanded ? '收起动作段' : `查看全部 ${segmentCount} 段`}
                        </Button>
                      ) : null}
                    </>
                  ) : (
                    <p className="form-hint">此机位没有已提交动作片段。</p>
                  )}
                </div>
                {state === 'EXCLUDED' && cameraView?.exclusion_reason ? (
                  <p className="form-hint">排除原因：{cameraView.exclusion_reason}</p>
                ) : null}
              </article>
            )
          })}
        </section>
      ) : null}
      {revision?.annotation_document.schema_version === 'annotation-document.v1' ? (
        <FormalAnnotationIntervals
          activeCameraNumber={activeCameraNumber}
          schema={schema}
          segments={revision.annotation_document.segments}
          onPlaySegment={onPlaySegment}
        />
      ) : null}
      {!task ? (
        <p className="form-hint">当前没有可见的人工标签任务，暂不能读取修订、复核事实。</p>
      ) : !revision ? (
        <p className="form-hint">尚无正式修订；提交后，授权的人工复核人可在此完成复核。</p>
      ) : !isReviewApproved || isCompletedDetailOpen ? (
        <div id="capture-group-review-details">
          {review?.decision === 'REJECTED' ? (
            <Alert
              type="warning"
              showIcon
              title="当前修订已退回，任务已回到待返工"
              description="复核结果来自服务端；不可对同一修订重复决定。"
            />
          ) : !canReview && !review ? (
            <p className="capture-group-review-permission" role="status">
              <CircleAlert aria-hidden="true" size={16} strokeWidth={1.8} />
              当前账号没有该修订的复核权限；仍可查看服务端返回的审阅事实。
            </p>
          ) : null}

          <div className="capture-group-review-boundary" role="status">
            <FileCheck2 aria-hidden="true" size={17} strokeWidth={1.8} />
            <div>
              <strong>人工复核操作</strong>
              <span>检测结果仅供人工参考，不作为正式标注或质量结论。</span>
            </div>
          </div>

          <div className="capture-group-review-facts">
            <MetadataList
              ariaLabel="人工标签复核核心事实"
              items={[
                {
                  id: 'review-group',
                  label: '采集记录',
                  value: group.capture_group_key ?? group.capture_group_id,
                },
                {
                  id: 'review-revision',
                  label: '修订',
                  value: `第 ${revision.revision_number} 次正式修订`,
                },
                {
                  id: 'review-cameras',
                  label: '复核机位数',
                  value: cameraNumbers.length,
                },
                {
                  id: 'review-input-version',
                  label: '确认输入版本',
                  value: (
                    <span title={inputVersion ?? undefined}>
                      {formatInputVersion(inputVersion)}
                    </span>
                  ),
                },
                {
                  id: 'review-submitted',
                  label: '提交事实',
                  value: revision.submitted_by,
                  secondary: formatDateTime(revision.submitted_at),
                },
              ]}
            />
          </div>

          {isReviewApproved ? (
            <details className="capture-group-review-detail-disclosure">
              <summary>查看已确认机位、检测证据与修订历史</summary>
              <div className="capture-group-review-detail-content">{evidenceAndHistory}</div>
            </details>
          ) : (
            evidenceAndHistory
          )}

          {cleaning ? <CaptureGroupCleaningCheck cleaning={cleaning} schema={schema} /> : null}
        </div>
      ) : null}
    </Section>
  )
}

interface ViewSetSummaryProps {
  viewSet: CaptureGroupViewSet | null | undefined
}

function ViewSetSummary({ viewSet }: ViewSetSummaryProps) {
  return (
    <div className="capture-group-view-set-summary">
      <div className="capture-group-review-subheading">
        <div>
          <span className="app-eyebrow">已确认机位集合</span>
          <h3>已确认输入与机位集合</h3>
        </div>
        <span>{viewSet ? `${viewSet.views.length} 个机位` : '未提供'}</span>
      </div>
      {viewSet ? (
        <MetadataList
          ariaLabel="已确认机位集合摘要"
          items={[
            {
              id: 'view-set-schema',
              label: '标注结构版本',
              value: viewSet.schema_version,
            },
            {
              id: 'view-set-group-version',
              label: '采集记录版本',
              value: viewSet.capture_group_version,
            },
            {
              id: 'view-set-camera-set',
              label: '相机集合快照',
              value: shortEvidenceId(viewSet.camera_set_snapshot_id),
            },
            {
              id: 'view-set-receipt',
              label: '接收状态',
              value: receiptStateLabels[viewSet.receipt_state],
            },
          ]}
        />
      ) : (
        <p className="form-hint">未提供已确认机位集合。</p>
      )}
    </div>
  )
}

const INITIAL_SEGMENT_VISIBLE_COUNT = 6
const INITIAL_INTERVAL_VISIBLE_COUNT = 4

function FormalAnnotationIntervals({
  activeCameraNumber,
  schema,
  segments,
  onPlaySegment,
}: {
  activeCameraNumber?: number | null
  schema: AnnotationLabelSchema | null
  segments: AnnotationSegment[] | undefined
  onPlaySegment?: (startMs: number, endMs: number) => void
}) {
  const safeSegments = Array.isArray(segments) ? segments : []
  const [isExpanded, setIsExpanded] = useState(false)
  const hasMore = safeSegments.length > INITIAL_SEGMENT_VISIBLE_COUNT
  const displayedSegments = isExpanded
    ? safeSegments
    : safeSegments.slice(0, INITIAL_SEGMENT_VISIBLE_COUNT)

  return (
    <div className="capture-group-formal-intervals">
      <div className="capture-group-review-subheading">
        <div>
          <span className="app-eyebrow">正式标注动作</span>
          <h3>正式动作区间（已生效）</h3>
        </div>
        <div className="capture-group-intervals-actions">
          <span>
            {activeCameraNumber ? `当前机位 ${activeCameraNumber} 本地时间 · ` : '历史单路时间 · '}
            共 {safeSegments.length} 个动作段
          </span>
          {hasMore ? (
            <Button type="text" size="small" onClick={() => setIsExpanded((prev) => !prev)}>
              {isExpanded ? '收起' : `展开全部 (${safeSegments.length})`}
            </Button>
          ) : null}
        </div>
      </div>
      {safeSegments.length ? (
        <ol className="capture-group-formal-interval-list" aria-label="正式动作区间列表">
          {displayedSegments.map((segment, index) => {
            const labelName = getActionDisplayName(segment.label, schema)
            const formattedStart = formatAnnotationTime(segment.start_ms)
            const formattedEnd = formatAnnotationTime(segment.end_ms)
            return (
              <li key={`${segment.label}-${segment.start_ms}-${segment.end_ms}-${index}`}>
                <span className="capture-group-time-pill">
                  {formattedStart && formattedEnd
                    ? `${formattedStart} → ${formattedEnd}`
                    : '时间暂不可用'}
                </span>
                <span className="capture-group-action-name">{labelName ?? segment.label}</span>
                {onPlaySegment ? (
                  <Button
                    type="text"
                    size="small"
                    icon={<Play aria-hidden="true" size={12} />}
                    onClick={() => onPlaySegment(segment.start_ms, segment.end_ms)}
                    style={{ marginLeft: 'auto', flex: 'none' }}
                    title="在当前机位播放此动作片段"
                  >
                    播放
                  </Button>
                ) : null}
              </li>
            )
          })}
        </ol>
      ) : (
        <p className="form-hint">当前修订未包含正式标注动作区间。</p>
      )}
    </div>
  )
}

interface EvidenceViewListProps {
  title: string
  views: CaptureGroupEvidence[] | undefined
  emptyText: string
  tone: 'success' | 'warning'
}

function EvidenceViewList({ title, views, emptyText, tone }: EvidenceViewListProps) {
  const safeViews = Array.isArray(views) ? views : []

  return (
    <div className="capture-group-review-view-list">
      <div className="capture-group-review-subheading">
        <strong>{title}</strong>
        <span>{safeViews.length} 个机位</span>
      </div>
      {safeViews.length ? (
        <ul className="capture-group-evidence-list" aria-label={title}>
          {safeViews.map((view, index) => {
            const rejectionReason = stringValue(view, 'rejection_reason')
            const actionLinked = booleanValue(view, 'primary')
            return (
              <li key={evidenceKey(view, index)}>
                <strong>
                  {evidenceViewName(view, index)}
                  {actionLinked ? ' · 动作区间关联' : ''}
                </strong>
                {rejectionReason ? <span>{viewRejectionReasonLabel(rejectionReason)}</span> : null}
                <StatusIndicator tone={tone}>
                  {tone === 'success' ? '参与' : '排除'}
                </StatusIndicator>
              </li>
            )
          })}
        </ul>
      ) : (
        <p className="form-hint">{emptyText}</p>
      )}
    </div>
  )
}

const executedCheckMeta: Record<string, { label: string; desc: string }> = {
  REVIEW_APPROVED: { label: '人工复核通过', desc: '经授权复核人审核确认' },
  GROUP_QUALITY_ADMITTING: { label: '整体质量准入', desc: '采集记录综合质量评定通过' },
  PRIMARY_VIEW_AUTHORITATIVE_INTERVAL: {
    label: '机位动作区间有效',
    desc: '动作时间段通过既有规则核验',
  },
  QUALIFIED_VIEWS_ONLY: { label: '已排除不合格机位', desc: '非合格视角不参与动作计算' },
  SUPPORTING_VIEWS_EVENT_LEVEL_EVIDENCE: {
    label: '全机位证据已记录',
    desc: '各机位证据分别保留',
  },
  RAW_MEDIA_RETAINED: { label: '原始视频资产完整', desc: '原始媒体已安全归档' },
}

interface CaptureGroupCleaningCheckProps {
  cleaning: CleaningResult | null
  schema: AnnotationLabelSchema | null
}

function CaptureGroupCleaningCheck({ cleaning, schema }: CaptureGroupCleaningCheckProps) {
  if (!cleaning) return null

  return (
    <div className="capture-group-cleaning-check" aria-label="历史标注检查快照">
      <div className="capture-group-review-subheading">
        <div>
          <span className="app-eyebrow">历史快照（只读）</span>
          <h3>标注检查</h3>
        </div>
        <StatusIndicator tone={cleaningTone(cleaning.outcome)}>
          {cleaningLabel(cleaning.outcome)}
        </StatusIndicator>
      </div>
      <div className="capture-group-cleaning-summary">
        <StatusIndicator tone={cleaningTone(cleaning.outcome)}>
          {cleaning.outcome === 'PASS' ? '标注检查通过' : '标注检查未通过'}
        </StatusIndicator>
        <span>评估时间：{formatDateTime(cleaning.evaluated_at)}</span>
        {cleaning?.evaluated_by ? <span>评估人：{cleaning.evaluated_by}</span> : null}
      </div>
      <details className="capture-group-progressive-detail capture-group-cleaning-detail">
        <summary>
          <span>完整检查记录</span>
          <small>规则来源 · 原因 · 已执行检查 · 机位证据</small>
        </summary>
        <MetadataList
          ariaLabel="标注检查规则来源"
          items={[
            ...(cleaning.rule_set_key
              ? [{ id: 'cleaning-rule-key', label: '规则集', value: cleaning.rule_set_key }]
              : []),
            ...(cleaning.rule_set_version
              ? [
                  {
                    id: 'cleaning-rule-version',
                    label: '规则版本',
                    value: cleaning.rule_set_version,
                  },
                ]
              : []),
            ...(cleaning.rule_set_digest
              ? [
                  {
                    id: 'cleaning-rule-digest',
                    label: '规则摘要',
                    value: <code>{shortEvidenceId(cleaning.rule_set_digest)}</code>,
                  },
                ]
              : []),
            ...(cleaning?.authorization_basis
              ? [
                  {
                    id: 'cleaning-authorization',
                    label: '授权依据',
                    value: cleaning.authorization_basis,
                  },
                ]
              : []),
          ]}
        />
        <CleaningList title="检查原因" items={cleaning.reason_codes} emptyText="没有阻断原因。" />
        <CleaningChecksList items={cleaning.executed_checks} />
        <div className="capture-group-cleaning-evidence-grid">
          <EvidenceViewList
            title="参与机位"
            views={cleaning?.participating_views}
            emptyText="没有参与机位。"
            tone="success"
          />
          <EvidenceViewList
            title="排除机位"
            views={cleaning?.excluded_views}
            emptyText="没有排除机位。"
            tone="warning"
          />
        </div>
        <CleaningAnnotationIntervals
          historicalIntervals={cleaning?.primary_interval ?? []}
          schema={schema}
        />
        <EventLevelViewEvidence viewEvidence={cleaning?.view_evidence ?? []} />
      </details>
    </div>
  )
}

function CleaningList({
  title,
  items,
  emptyText,
}: {
  title: string
  items: string[] | null | undefined
  emptyText: string
}) {
  const safeItems = Array.isArray(items) ? items : []
  return (
    <div className="capture-group-cleaning-list">
      <strong>{title}</strong>
      {safeItems.length ? (
        <ul>
          {safeItems.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>
      ) : (
        <span>{emptyText}</span>
      )}
    </div>
  )
}

function CleaningChecksList({ items }: { items: string[] | null | undefined }) {
  const safeItems = Array.isArray(items) ? items : []
  return (
    <div className="capture-group-cleaning-list">
      <strong>已执行检查</strong>
      {safeItems.length ? (
        <div className="capture-group-checks-grid" role="list" aria-label="已执行检查">
          {safeItems.map((item) => {
            const meta = executedCheckMeta[item]
            return (
              <div key={item} className="capture-group-check-item" role="listitem">
                <Check size={13} className="capture-group-check-icon" aria-hidden="true" />
                <div className="capture-group-check-info">
                  <strong>{meta?.label ?? item}</strong>
                  <code className="capture-group-check-code">{item}</code>
                </div>
              </div>
            )
          })}
        </div>
      ) : (
        <span>未返回检查项。</span>
      )}
    </div>
  )
}

function CleaningAnnotationIntervals({
  historicalIntervals,
  schema,
}: {
  historicalIntervals: CaptureGroupEvidence[]
  schema: AnnotationLabelSchema | null
}) {
  const [isExpanded, setIsExpanded] = useState(false)
  const safeIntervals = historicalIntervals
  const hasMore = safeIntervals.length > INITIAL_INTERVAL_VISIBLE_COUNT
  const displayed = isExpanded
    ? safeIntervals
    : safeIntervals.slice(0, INITIAL_INTERVAL_VISIBLE_COUNT)

  return (
    <div className="capture-group-cleaning-interval">
      <div className="capture-group-cleaning-interval-header">
        <div>
          <strong>历史正式动作区间</strong>
          <span>该历史快照汇总的正式动作时间段</span>
        </div>
        {hasMore ? (
          <Button type="text" size="small" onClick={() => setIsExpanded((prev) => !prev)}>
            {isExpanded ? '收起' : `展开全部 (${safeIntervals.length})`}
          </Button>
        ) : null}
      </div>
      {safeIntervals.length ? (
        <ol className="capture-group-formal-interval-list">
          {displayed.map((interval, index) => {
            const label = stringValue(interval, 'label')
            const labelName = label ? getActionDisplayName(label, schema) : null
            const startMs = numberValue(interval, 'start_ms')
            const endMs = numberValue(interval, 'end_ms')
            const formattedStart = formatAnnotationTime(startMs)
            const formattedEnd = formatAnnotationTime(endMs)
            return (
              <li key={evidenceKey(interval, index)}>
                <span className="capture-group-time-pill">
                  {formattedStart && formattedEnd
                    ? `${formattedStart} → ${formattedEnd}`
                    : '时间暂不可用'}
                </span>
                <span className="capture-group-action-name">
                  {labelName ?? label ?? '未提供标签'}
                </span>
              </li>
            )
          })}
        </ol>
      ) : (
        <span>未返回动作时间段。</span>
      )}
    </div>
  )
}

function EventLevelViewEvidence({ viewEvidence }: { viewEvidence: CaptureGroupEvidence[] }) {
  return (
    <div className="capture-group-event-evidence">
      <div className="capture-group-review-subheading">
        <strong>采集记录级参考视图证据</strong>
        <span>{viewEvidence.length} 个机位</span>
      </div>
      {viewEvidence.length ? (
        <ul className="capture-group-evidence-list" aria-label="采集记录级参考视图证据">
          {viewEvidence.map((view, index) => {
            const primary = booleanValue(view, 'action_aligned_interval')
            const syncReliability = stringValue(view, 'sync_reliability')
            return (
              <li key={evidenceKey(view, index)}>
                <strong>{evidenceViewName(view, index)}</strong>
                <span>{primary ? '关联动作区间证据' : '采集记录级参考证据'}</span>
                <small>
                  {syncReliability
                    ? `同步声明：${syncReliabilityDisplayLabel(syncReliability)} · 仅供参考，不代表精确时间对齐`
                    : '不生成参考视图动作时间点'}
                </small>
              </li>
            )
          })}
        </ul>
      ) : (
        <p className="form-hint">未返回采集记录级机位证据。</p>
      )}
    </div>
  )
}

function qualifiedViewsFromViewSet(viewSet: CaptureGroupViewSet | null | undefined) {
  return (viewSet?.views ?? [])
    .filter(
      (view) =>
        view.present &&
        view.asset_available &&
        view.media_status === 'INGESTED' &&
        view.quality_review_decision === 'QUALIFIED',
    )
    .map((view) => ({
      camera_number: view.camera_number,
      clip_asset_id: view.clip_asset_id,
      present: view.present,
      media_status: view.media_status,
      quality_decision: view.quality_review_decision,
    }))
}

function rejectedViewsFromViewSet(viewSet: CaptureGroupViewSet | null | undefined) {
  return (viewSet?.views ?? [])
    .filter(
      (view) =>
        !view.present ||
        !view.asset_available ||
        view.media_status !== 'INGESTED' ||
        view.quality_review_decision !== 'QUALIFIED',
    )
    .map((view) => ({
      camera_number: view.camera_number,
      clip_asset_id: view.clip_asset_id,
      present: view.present,
      media_status: view.media_status,
      quality_decision: view.quality_review_decision,
      rejection_reason: !view.present ? 'MISSING_VIEW' : 'VIEW_NOT_QUALIFIED',
    }))
}

function evidenceKey(evidence: CaptureGroupEvidence, index: number) {
  return `${stringValue(evidence, 'camera_number') ?? stringValue(evidence, 'clip_asset_id') ?? 'evidence'}-${index}`
}

function evidenceViewName(evidence: CaptureGroupEvidence, index: number) {
  const cameraNumber = numberValue(evidence, 'camera_number')
  return cameraNumber === null ? `机位 ${index + 1}` : `机位 ${cameraNumber}`
}

const viewRejectionReasonLabels: Record<string, string> = {
  MISSING_VIEW: '机位缺失',
  VIEW_NOT_REVIEWABLE: '机位暂不可复核',
  VIEW_NOT_QUALIFIED: '机位暂不可标注',
  VIEW_QUALITY_NOT_QUALIFIED: '机位暂不可标注',
}

function viewRejectionReasonLabel(value: string) {
  return viewRejectionReasonLabels[value] ?? '存在排除原因'
}

function stringValue(evidence: CaptureGroupEvidence, key: string) {
  const value = evidence[key]
  return typeof value === 'string' && value.length ? value : null
}

function numberValue(evidence: CaptureGroupEvidence, key: string) {
  const value = evidence[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function booleanValue(evidence: CaptureGroupEvidence, key: string) {
  return evidence[key] === true
}

function shortEvidenceId(value: string) {
  return value.length > 16 ? `${value.slice(0, 8)}…${value.slice(-8)}` : value
}

function formatInputVersion(version: string | null | undefined): string {
  if (!version) return '未提供'
  const colonIndex = version.indexOf(':')
  if (colonIndex !== -1) {
    const rawPrefix = version.slice(0, colonIndex)
    const prefix = rawPrefix.replace('capture-group-view-set.', '')
    return `${prefix}（快照已锁定）`
  }
  return version
}
