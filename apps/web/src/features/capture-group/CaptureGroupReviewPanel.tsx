import { Alert, App as AntdApp, Button, Input } from 'antd'
import { Check, CircleAlert, FileCheck2, History } from 'lucide-react'
import { useState } from 'react'

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
import type { CaptureGroup, CaptureGroupQualityReview } from '@/api/capture-groups'
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
  groupQualityDisplayLabel,
  groupQualityDisplayTone,
  receiptStateLabels,
  syncReliabilityDisplayLabel,
  type CaptureGroupQualityVisibility,
  viewQualityLabels,
} from '@/features/capture-group-presentation'
import { formatDateTime } from '@/features/catalog/catalog-presentation'
import type { CaptureGroupDetailRefreshOptions } from '@/features/capture-group/use-capture-group-detail-data'

type CleaningResult = CaptureGroupCleaning

export interface CaptureGroupReviewPanelProps {
  projectId: string
  group: CaptureGroup
  groupQuality: CaptureGroupQualityReview | null
  groupQualityVisibility: CaptureGroupQualityVisibility
  task: AnnotationTask | null
  schema: AnnotationLabelSchema | null
  onRefresh: (options?: CaptureGroupDetailRefreshOptions) => Promise<boolean>
}

export function CaptureGroupReviewPanel({
  projectId,
  group,
  groupQuality,
  groupQualityVisibility,
  task,
  schema,
  onRefresh,
}: CaptureGroupReviewPanelProps) {
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
  const primaryCameraNumber = revision?.primary_camera_number ?? task?.primary_camera_number ?? null
  const isReviewApproved = review?.decision === 'APPROVED'
  const [isCompletedDetailOpen, setIsCompletedDetailOpen] = useState(false)

  function openReview(decision: AnnotationReview['decision']) {
    if (!task || !revision || !task.can_review || reviewingDecision) return
    let reviewNote = ''
    const approved = decision === 'APPROVED'
    modal.confirm({
      title: approved ? '通过人工标签复核' : '退回人工标签修订',
      content: approved ? (
        <p>
          确认本次标注内容正确？管理员可确认自己提交的标注，无需另一人复核。确认后仍须通过当前规则和来源检查才能导出。
        </p>
      ) : (
        <div className="capture-group-review-confirmation">
          <p>退回这次人工标签修订，任务将回到原执行人并等待下一次修订。</p>
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
          title="参与的合格机位"
          views={qualifiedViews}
          emptyText="当前没有已确认的参与机位。"
          tone="success"
        />
        <EvidenceViewList
          title="拒绝 / 缺失机位"
          views={rejectedMissingViews}
          emptyText="没有拒绝或缺失机位。"
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
            <p role="status">标注已完成人工复核确认，可返回视频列表选择导出。</p>
          </div>
          <Button
            type="link"
            size="small"
            aria-controls={isCompletedDetailOpen ? 'capture-group-review-details' : undefined}
            aria-expanded={isCompletedDetailOpen}
            onClick={() => setIsCompletedDetailOpen((open) => !open)}
          >
            {isCompletedDetailOpen ? '收起复核详情' : '查看复核详情'}
          </Button>
        </div>
      ) : null}
      {!task ? (
        <p className="form-hint">当前没有可见的人工标签任务，暂不能读取修订、复核事实。</p>
      ) : !revision ? (
        <p className="form-hint">尚无正式修订；提交后，授权的人工复核人可在此完成复核。</p>
      ) : !isReviewApproved || isCompletedDetailOpen ? (
        <div id="capture-group-review-details">
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
                  id: 'review-primary',
                  label: '主视图',
                  value: primaryCameraNumber ? `机位 ${primaryCameraNumber}` : '未提供',
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
            <div className="capture-group-review-quality-fact">
              <span className="app-eyebrow">当前整体质量</span>
              <StatusIndicator tone={groupQualityDisplayTone(groupQualityVisibility, groupQuality)}>
                {groupQualityDisplayLabel(groupQualityVisibility, groupQuality)}
              </StatusIndicator>
              {groupQuality?.reason ? <p>理由：{groupQuality.reason}</p> : null}
              {groupQuality?.decided_at ? (
                <span>记录时间：{formatDateTime(groupQuality.decided_at)}</span>
              ) : null}
            </div>
          </div>

          {canReview ? (
            <div className="capture-group-review-actions" aria-label="人工标签复核操作">
              <Button
                type="primary"
                loading={reviewingDecision === 'APPROVED'}
                disabled={reviewingDecision !== null}
                onClick={() => openReview('APPROVED')}
              >
                通过复核
              </Button>
              <Button
                danger
                loading={reviewingDecision === 'REJECTED'}
                disabled={reviewingDecision !== null}
                onClick={() => openReview('REJECTED')}
              >
                退回修改
              </Button>
            </div>
          ) : review ? (
            <Alert
              type={review.decision === 'APPROVED' ? 'success' : 'warning'}
              showIcon
              title={
                review.decision === 'APPROVED'
                  ? '当前修订已完成复核'
                  : '当前修订已退回，任务已回到待修改'
              }
              description="复核结果来自服务端；不可对同一修订重复决定。"
            />
          ) : (
            <p className="capture-group-review-permission" role="status">
              <CircleAlert aria-hidden="true" size={16} strokeWidth={1.8} />
              当前账号没有该修订的复核权限；仍可查看服务端返回的审阅事实。
            </p>
          )}

          <FormalAnnotationIntervals
            primaryCameraNumber={primaryCameraNumber}
            schema={schema}
            segments={revision.annotation_document.segments}
          />

          {isReviewApproved ? (
            <details className="capture-group-review-detail-disclosure">
              <summary>查看已确认机位、检测证据与修订历史</summary>
              <div className="capture-group-review-detail-content">{evidenceAndHistory}</div>
            </details>
          ) : (
            evidenceAndHistory
          )}

          {cleaning ? (
            <CaptureGroupCleaningCheck
              cleaning={cleaning}
              primaryCameraNumber={primaryCameraNumber}
              schema={schema}
            />
          ) : null}
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
  primaryCameraNumber,
  schema,
  segments,
}: {
  primaryCameraNumber: number | null
  schema: AnnotationLabelSchema | null
  segments: AnnotationSegment[] | undefined
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
          <h3>正式动作区间</h3>
        </div>
        <div className="capture-group-intervals-actions">
          <span>
            {primaryCameraNumber ? `机位 ${primaryCameraNumber} · ` : ''}
            共 {safeSegments.length} 个动作段
          </span>
          {hasMore ? (
            <Button
              type="link"
              size="small"
              onClick={() => setIsExpanded((prev) => !prev)}
            >
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
            const qualityDecision = viewQualityDecision(view)
            const rejectionReason = stringValue(view, 'rejection_reason')
            const primary = booleanValue(view, 'primary')
            return (
              <li key={evidenceKey(view, index)}>
                <strong>
                  {evidenceViewName(view, index)}
                  {primary ? ' · 主视图' : ''}
                </strong>
                <span>
                  {qualityDecision ? `质量判断：${viewQualityLabels[qualityDecision]}` : ''}
                  {qualityDecision && rejectionReason ? ' · ' : ''}
                  {rejectionReason ? viewRejectionReasonLabel(rejectionReason) : ''}
                </span>
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
  PRIMARY_VIEW_AUTHORITATIVE_INTERVAL: { label: '主机位权威区间有效', desc: '动作时间段与主机位基准对齐' },
  QUALIFIED_VIEWS_ONLY: { label: '已排除不合格机位', desc: '非合格视角不参与动作计算' },
  SUPPORTING_VIEWS_EVENT_LEVEL_EVIDENCE: { label: '辅机位参考证据就绪', desc: '多视角同步证据已记录' },
  RAW_MEDIA_RETAINED: { label: '原始视频资产完整', desc: '原始媒体已安全归档' },
}

interface CaptureGroupCleaningCheckProps {
  cleaning: CleaningResult | null
  primaryCameraNumber: number | null
  schema: AnnotationLabelSchema | null
}

function CaptureGroupCleaningCheck({
  cleaning,
  primaryCameraNumber,
  schema,
}: CaptureGroupCleaningCheckProps) {
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
        {cleaning?.evaluated_by ? (
          <span>评估人：{cleaning.evaluated_by}</span>
        ) : null}
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
        <CleaningPrimaryInterval
          primaryCameraNumber={primaryCameraNumber}
          primaryInterval={cleaning?.primary_interval ?? []}
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

function CleaningPrimaryInterval({
  primaryCameraNumber,
  primaryInterval,
  schema,
}: {
  primaryCameraNumber: number | null
  primaryInterval: CaptureGroupEvidence[]
  schema: AnnotationLabelSchema | null
}) {
  const [isExpanded, setIsExpanded] = useState(false)
  const safeIntervals = primaryInterval
  const hasMore = safeIntervals.length > INITIAL_INTERVAL_VISIBLE_COUNT
  const displayed = isExpanded ? safeIntervals : safeIntervals.slice(0, INITIAL_INTERVAL_VISIBLE_COUNT)

  return (
    <div className="capture-group-cleaning-interval">
      <div className="capture-group-cleaning-interval-header">
        <div>
          <strong>主视图正式时间轴</strong>
          <span>{primaryCameraNumber ? `机位 ${primaryCameraNumber} · ` : ''}只展示正式动作时间段</span>
        </div>
        {hasMore ? (
          <Button
            type="link"
            size="small"
            onClick={() => setIsExpanded((prev) => !prev)}
          >
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
                <span className="capture-group-action-name">{labelName ?? label ?? '未提供标签'}</span>
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
                <span>{primary ? '主视图 · 时间基准' : '参考视图 · 仅供采集记录级参考'}</span>
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

function viewQualityDecision(evidence: CaptureGroupEvidence) {
  const decision =
    stringValue(evidence, 'quality_decision') ?? stringValue(evidence, 'quality_review_decision')
  return decision && decision in viewQualityLabels
    ? (decision as keyof typeof viewQualityLabels)
    : null
}

const viewRejectionReasonLabels: Record<string, string> = {
  MISSING_VIEW: '机位缺失',
  VIEW_NOT_REVIEWABLE: '机位暂不可复核',
  VIEW_NOT_QUALIFIED: '机位质量未合格',
  VIEW_QUALITY_NOT_QUALIFIED: '机位质量未合格',
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
