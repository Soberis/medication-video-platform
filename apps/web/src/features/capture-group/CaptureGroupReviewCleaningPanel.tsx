import { Alert, App as AntdApp, Button, Input } from 'antd'
import { CircleAlert, FileCheck2, History } from 'lucide-react'
import { useState } from 'react'

import {
  recheckCaptureGroupCleaning,
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
import { cleaningLabel, cleaningTone } from '@/features/capture-group/capture-group-detail-model'
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

interface CaptureGroupReviewCleaningPanelProps {
  projectId: string
  group: CaptureGroup
  groupQuality: CaptureGroupQualityReview | null
  groupQualityVisibility: CaptureGroupQualityVisibility
  task: AnnotationTask | null
  schema: AnnotationLabelSchema | null
  onRefresh: (options?: CaptureGroupDetailRefreshOptions) => Promise<boolean>
}

export function CaptureGroupReviewCleaningPanel({
  projectId,
  group,
  groupQuality,
  groupQualityVisibility,
  task,
  schema,
  onRefresh,
}: CaptureGroupReviewCleaningPanelProps) {
  const { message, modal } = AntdApp.useApp()
  const [reviewingDecision, setReviewingDecision] = useState<AnnotationReview['decision'] | null>(
    null,
  )
  const [isRechecking, setIsRechecking] = useState(false)
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
  const primaryClipAssetId = revision?.primary_clip_asset_id ?? task?.primary_clip_asset_id ?? null
  const primaryCameraNumber = revision?.primary_camera_number ?? task?.primary_camera_number ?? null
  const hasCompletedReviewAndCleaning =
    review?.decision === 'APPROVED' && cleaning?.outcome === 'PASS'
  const [isCompletedDetailOpen, setIsCompletedDetailOpen] = useState(false)

  function openReview(decision: AnnotationReview['decision']) {
    if (!task || !revision || !task.can_review || reviewingDecision) return
    let reviewNote = ''
    const approved = decision === 'APPROVED'
    modal.confirm({
      title: approved ? '通过配药事件复核' : '退回配药事件修订',
      content: approved ? (
        <p>确认当前正式动作时间段、主视图和事件内证据均可接受，并完成这次人工复核？</p>
      ) : (
        <div className="capture-group-review-confirmation">
          <p>退回这次配药事件修订，任务将回到原执行人并等待下一次修订。</p>
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
          void message.success(approved ? '配药事件复核已通过。' : '已退回配药事件修订。')
          await onRefresh()
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

  async function recheckCleaning() {
    if (!revision || !task?.can_recheck_capture_group_cleaning || isRechecking) return
    setIsRechecking(true)
    try {
      await recheckCaptureGroupCleaning(projectId, revision.annotation_revision_id)
      void message.success('训练资格已重新检查，正在刷新服务端事实。')
      await onRefresh()
    } catch (error) {
      void message.error(
        error instanceof Error ? error.message : '训练资格检查暂未完成，请刷新后重试。',
      )
    } finally {
      setIsRechecking(false)
    }
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
            <span className="app-eyebrow">手部检测证据</span>
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
            <span className="app-eyebrow">修订历史</span>
            <h3>标注备注与历史</h3>
          </div>
          <History aria-hidden="true" size={16} strokeWidth={1.8} />
        </div>
        <ol aria-label="配药动作修订与复核历史">
          <li>
            <strong>修订 {revision.revision_number} · 已提交</strong>
            <span>
              {revision.submitted_by} · {formatDateTime(revision.submitted_at)}
            </span>
            <p>{revision.annotation_document.note ?? '未提供修订备注。'}</p>
          </li>
          {review ? (
            <li>
              <strong>复核 · {review.decision === 'APPROVED' ? '已通过' : '已退回'}</strong>
              <span>
                {review.reviewed_by} · {formatDateTime(review.reviewed_at)}
              </span>
              <p>{review.review_note ?? '未提供复核备注。'}</p>
            </li>
          ) : null}
        </ol>
      </div>
    </>
  ) : null

  return (
    <Section
      className="capture-group-review-cleaning-section capture-group-workflow-focus-target"
      id="capture-group-review-cleaning"
      tabIndex={-1}
      title="人工复核 / 训练资格检查"
      titleId="capture-group-review-cleaning-title"
      actions={
        <span className="capture-group-section-note">复核由人工完成 · 训练资格检查按规则执行</span>
      }
    >
      {hasCompletedReviewAndCleaning ? (
        <div className="capture-group-review-complete-summary" role="status">
          <StatusIndicator tone="success">人工复核已通过 · 训练资格检查通过</StatusIndicator>
          <p>当前事件已完成既定流程；完整修订、证据与规则事实已收起。</p>
          <Button
            type="link"
            size="small"
            aria-controls="capture-group-review-cleaning-details"
            aria-expanded={isCompletedDetailOpen}
            onClick={() => setIsCompletedDetailOpen((open) => !open)}
          >
            {isCompletedDetailOpen ? '收起复核与训练资格详情' : '查看复核与训练资格详情'}
          </Button>
        </div>
      ) : null}
      {!task ? (
        <p className="form-hint">
          当前没有可见的配药事件任务，暂不能读取修订、复核或训练资格事实。
        </p>
      ) : !revision ? (
        <p className="form-hint">尚无正式修订；提交后，授权的人工复核人可在此完成复核。</p>
      ) : !hasCompletedReviewAndCleaning || isCompletedDetailOpen ? (
        <div id="capture-group-review-cleaning-details">
          <div className="capture-group-review-boundary" role="status">
            <FileCheck2 aria-hidden="true" size={17} strokeWidth={1.8} />
            <div>
              <strong>人工复核操作</strong>
              <span>检测结果仅供人工参考，不作为正式标注或质量结论。</span>
            </div>
          </div>

          <div className="capture-group-review-facts">
            <MetadataList
              ariaLabel="配药事件复核核心事实"
              items={[
                {
                  id: 'review-group',
                  label: '配药事件',
                  value: group.capture_group_key ?? group.capture_group_id,
                },
                {
                  id: 'review-revision',
                  label: '修订',
                  value: `第 ${revision.revision_number} 次正式修订`,
                  secondary: shortEvidenceId(revision.annotation_revision_id),
                },
                {
                  id: 'review-primary',
                  label: '主视图',
                  value: primaryCameraNumber ? `机位 ${primaryCameraNumber}` : '未提供',
                  secondary: primaryClipAssetId ? shortEvidenceId(primaryClipAssetId) : undefined,
                },
                {
                  id: 'review-input-version',
                  label: '确认输入版本',
                  value: inputVersion ?? '未提供',
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
              <span className="app-eyebrow">当前事件质量</span>
              <StatusIndicator tone={groupQualityDisplayTone(groupQualityVisibility, groupQuality)}>
                {groupQualityDisplayLabel(groupQualityVisibility, groupQuality)}
              </StatusIndicator>
              {groupQuality?.reason ? <p>理由：{groupQuality.reason}</p> : null}
              {groupQuality?.decided_at ? (
                <span>记录时间：{formatDateTime(groupQuality.decided_at)}</span>
              ) : null}
            </div>
          </div>

          <FormalAnnotationIntervals
            primaryCameraNumber={primaryCameraNumber}
            schema={schema}
            segments={revision.annotation_document.segments}
          />

          {hasCompletedReviewAndCleaning ? (
            <details className="capture-group-review-detail-disclosure">
              <summary>查看已确认机位、检测证据与修订历史</summary>
              <div className="capture-group-review-detail-content">{evidenceAndHistory}</div>
            </details>
          ) : (
            evidenceAndHistory
          )}

          {canReview ? (
            <div className="capture-group-review-actions" aria-label="配药事件复核操作">
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

          <TrainingEligibilityCheck
            cleaning={cleaning}
            primaryCameraNumber={primaryCameraNumber}
            schema={schema}
            review={review}
            canRecheck={Boolean(
              task.can_recheck_capture_group_cleaning && review?.decision === 'APPROVED',
            )}
            isRechecking={isRechecking}
            onRecheck={() => void recheckCleaning()}
          />
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
              label: '配药事件版本',
              value: viewSet.capture_group_version,
            },
            {
              id: 'view-set-snapshot',
              label: '机位配置快照',
              value: viewSet.camera_set_snapshot_id,
            },
            {
              id: 'view-set-receipt',
              label: '接收状态',
              value: receiptStateLabels[viewSet.receipt_state],
            },
          ]}
        />
      ) : (
        <p className="form-hint">当前修订没有返回已确认机位集合摘要。</p>
      )}
    </div>
  )
}

interface EvidenceViewListProps {
  title: string
  views: CaptureGroupEvidence[] | null | undefined
  emptyText: string
  tone: 'success' | 'warning'
}

function FormalAnnotationIntervals({
  primaryCameraNumber,
  schema,
  segments,
}: {
  primaryCameraNumber: number | null
  schema: AnnotationLabelSchema | null
  segments: AnnotationSegment[]
}) {
  return (
    <div className="capture-group-cleaning-interval capture-group-review-formal-intervals">
      <strong>主视图正式动作时间段</strong>
      <span>
        {primaryCameraNumber ? `机位 ${primaryCameraNumber} · ` : ''}当前修订的正式动作时间轴
      </span>
      {segments.length ? (
        <ol>
          {segments.map((segment, index) => {
            const labelName = schema?.labels.find(
              (label) => label.label_id === segment.label,
            )?.display_name
            return (
              <li key={`${segment.start_ms}-${segment.end_ms}-${segment.label}-${index}`}>
                <strong>
                  {segment.start_ms}–{segment.end_ms} 毫秒
                </strong>
                <span>{labelName ?? segment.label}</span>
              </li>
            )
          })}
        </ol>
      ) : (
        <span>当前修订没有正式动作时间段。</span>
      )}
    </div>
  )
}

function EvidenceViewList({ title, views, emptyText, tone }: EvidenceViewListProps) {
  const safeViews = Array.isArray(views) ? views : []
  return (
    <div className="capture-group-review-evidence-block">
      <div className="capture-group-review-subheading">
        <h3>{title}</h3>
        <span>{safeViews.length} 路</span>
      </div>
      {safeViews.length ? (
        <ul className="capture-group-evidence-list" aria-label={title}>
          {safeViews.map((view, index) => {
            const decision = viewQualityDecision(view)
            return (
              <li key={evidenceKey(view, index)}>
                <StatusIndicator tone={tone === 'success' ? 'success' : 'warning'}>
                  {evidenceViewName(view, index)}
                </StatusIndicator>
                <span>
                  {decision ? (viewQualityLabels[decision] ?? decision) : '机位事实已固定'}
                  {stringValue(view, 'rejection_reason')
                    ? ` · ${viewRejectionReasonLabel(stringValue(view, 'rejection_reason')!)}`
                    : ''}
                </span>
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

interface TrainingEligibilityCheckProps {
  cleaning: CleaningResult | null
  primaryCameraNumber: number | null
  schema: AnnotationLabelSchema | null
  review: AnnotationReview | null
  canRecheck: boolean
  isRechecking: boolean
  onRecheck: () => void
}

function TrainingEligibilityCheck({
  cleaning,
  primaryCameraNumber,
  schema,
  review,
  canRecheck,
  isRechecking,
  onRecheck,
}: TrainingEligibilityCheckProps) {
  const captureGroupCleaning = cleaning

  return (
    <div className="capture-group-training-check" aria-label="训练资格检查">
      <div className="capture-group-review-subheading">
        <div>
          <span className="app-eyebrow">事件级训练资格检查</span>
          <h3>训练资格检查</h3>
        </div>
        {cleaning ? (
          <StatusIndicator tone={cleaningTone(cleaning.outcome)}>
            {cleaningLabel(cleaning.outcome)}
          </StatusIndicator>
        ) : null}
      </div>
      {cleaning ? (
        <>
          <div className="capture-group-cleaning-summary">
            <StatusIndicator tone={cleaningTone(cleaning.outcome)}>
              {cleaning.outcome === 'PASS' ? '训练资格检查通过' : '训练资格检查未通过'}
            </StatusIndicator>
            <span>评估时间：{formatDateTime(cleaning.evaluated_at)}</span>
            {captureGroupCleaning?.evaluated_by ? (
              <span>评估人：{captureGroupCleaning.evaluated_by}</span>
            ) : null}
          </div>
          <MetadataList
            ariaLabel="训练资格检查规则来源"
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
                      value: <code>{cleaning.rule_set_digest}</code>,
                    },
                  ]
                : []),
              ...(captureGroupCleaning?.authorization_basis
                ? [
                    {
                      id: 'cleaning-authorization',
                      label: '授权依据',
                      value: captureGroupCleaning.authorization_basis,
                    },
                  ]
                : []),
            ]}
          />
          <CleaningList title="检查原因" items={cleaning.reason_codes} emptyText="没有阻断原因。" />
          <CleaningList
            title="已执行检查"
            items={cleaning.executed_checks}
            emptyText="未返回检查项。"
          />
          <div className="capture-group-cleaning-evidence-grid">
            <EvidenceViewList
              title="参与机位"
              views={captureGroupCleaning?.participating_views}
              emptyText="没有参与机位。"
              tone="success"
            />
            <EvidenceViewList
              title="排除机位"
              views={captureGroupCleaning?.excluded_views}
              emptyText="没有排除机位。"
              tone="warning"
            />
          </div>
          <CleaningPrimaryInterval
            primaryCameraNumber={primaryCameraNumber}
            primaryInterval={captureGroupCleaning?.primary_interval ?? []}
            schema={schema}
          />
          <EventLevelViewEvidence viewEvidence={captureGroupCleaning?.view_evidence ?? []} />
          {canRecheck ? (
            <Button type="link" size="small" loading={isRechecking} onClick={onRecheck}>
              重新检查训练资格
            </Button>
          ) : null}
        </>
      ) : (
        <p className="form-hint">
          {review?.decision === 'APPROVED'
            ? '人工复核已通过；服务端尚未返回训练资格检查结果。'
            : '人工复核通过后，服务端会在同一事务内生成训练资格检查结果。这里不是第二个人工质量决定。'}
        </p>
      )}
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

function CleaningPrimaryInterval({
  primaryCameraNumber,
  primaryInterval,
  schema,
}: {
  primaryCameraNumber: number | null
  primaryInterval: CaptureGroupEvidence[]
  schema: AnnotationLabelSchema | null
}) {
  return (
    <div className="capture-group-cleaning-interval">
      <strong>主视图正式时间轴</strong>
      <span>{primaryCameraNumber ? `机位 ${primaryCameraNumber} · ` : ''}只展示正式动作时间段</span>
      {primaryInterval.length ? (
        <ol>
          {primaryInterval.map((interval, index) => {
            const label = stringValue(interval, 'label')
            const labelName = schema?.labels.find(
              (candidate) => candidate.label_id === label,
            )?.display_name
            return (
              <li key={evidenceKey(interval, index)}>
                <strong>
                  {numberValue(interval, 'start_ms') ?? 0}–{numberValue(interval, 'end_ms') ?? 0}{' '}
                  毫秒
                </strong>
                <span>{labelName ?? label ?? '未提供标签'}</span>
              </li>
            )
          })}
        </ol>
      ) : (
        <span>未返回正式时间段。</span>
      )}
    </div>
  )
}

function EventLevelViewEvidence({ viewEvidence }: { viewEvidence: CaptureGroupEvidence[] }) {
  return (
    <div className="capture-group-event-evidence">
      <div className="capture-group-review-subheading">
        <strong>事件级参考视图证据</strong>
        <span>{viewEvidence.length} 个机位</span>
      </div>
      {viewEvidence.length ? (
        <ul className="capture-group-evidence-list" aria-label="事件级参考视图证据">
          {viewEvidence.map((view, index) => {
            const primary = booleanValue(view, 'action_aligned_interval')
            const syncReliability = stringValue(view, 'sync_reliability')
            return (
              <li key={evidenceKey(view, index)}>
                <strong>{evidenceViewName(view, index)}</strong>
                <span>{primary ? '主视图 · 正式标注时间基准' : '参考视图 · 仅供事件级参考'}</span>
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
        <p className="form-hint">未返回事件级机位证据。</p>
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
