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
import { StatusBadge } from '@/components/StatusBadge'
import { StatusIndicator, type StatusIndicatorTone } from '@/components/status/StatusIndicator'
import { MetadataList } from '@/components/data/MetadataList'
import { Section } from '@/components/workspace/Section'
import { cleaningLabel, cleaningTone } from '@/features/capture-group/capture-group-detail-model'
import {
  groupQualityLabels,
  groupQualityTone,
  receiptStateLabels,
  viewQualityLabels,
} from '@/features/capture-group-presentation'
import { formatDateTime } from '@/features/catalog/catalog-presentation'

interface CaptureGroupReviewCleaningPanelProps {
  projectId: string
  group: CaptureGroup
  groupQuality: CaptureGroupQualityReview | null
  task: AnnotationTask | null
  schema: AnnotationLabelSchema | null
  onRefresh: () => Promise<void>
}

export function CaptureGroupReviewCleaningPanel({
  projectId,
  group,
  groupQuality,
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
  const qualifiedViews = review?.qualified_views ?? qualifiedViewsFromViewSet(viewSet)
  const rejectedMissingViews = review?.rejected_missing_views ?? rejectedViewsFromViewSet(viewSet)
  const candidateEvidence = review?.candidate_evidence ?? []
  const canReview = Boolean(task?.can_review && revision)
  const cleaning = revision?.capture_group_cleaning ?? null
  const primaryClipAssetId = revision?.primary_clip_asset_id ?? task?.primary_clip_asset_id ?? null
  const primaryCameraNumber = revision?.primary_camera_number ?? task?.primary_camera_number ?? null

  function openReview(decision: AnnotationReview['decision']) {
    if (!task || !revision || !task.can_review || reviewingDecision) return
    let reviewNote = ''
    const approved = decision === 'APPROVED'
    modal.confirm({
      title: approved ? '通过 CaptureGroup Review' : '退回 CaptureGroup Revision',
      content: approved ? (
        <p>确认当前正式动作时间段、Primary View 和组内证据均可接受，并完成这次人工 Review？</p>
      ) : (
        <div className="capture-group-review-confirmation">
          <p>退回这次 CaptureGroup Revision，任务将回到原执行人并等待下一次 Revision。</p>
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
      okText: approved ? '通过 Review' : '退回修改',
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
          void message.success(
            approved ? 'CaptureGroup Review 已通过。' : '已退回 CaptureGroup Revision。',
          )
          await onRefresh()
        } catch (error) {
          void message.error(
            error instanceof Error ? error.message : 'Review 暂未提交，请刷新后重试。',
          )
        } finally {
          setReviewingDecision(null)
        }
      },
    })
  }

  async function recheckCleaning() {
    if (!revision || !task?.can_recheck_post_cleaning || isRechecking) return
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

  return (
    <Section
      className="capture-group-review-cleaning-section capture-group-workflow-focus-target"
      id="capture-group-review-cleaning"
      tabIndex={-1}
      title="Review / 训练资格检查"
      titleId="capture-group-review-cleaning-title"
      actions={
        <span className="capture-group-section-note">Review 是人工闸门 · 检查由服务端执行</span>
      }
    >
      {!task ? (
        <p className="form-hint">
          当前没有可见的 CaptureGroup 任务，暂不能读取 Revision、Review 或训练资格事实。
        </p>
      ) : !revision ? (
        <p className="form-hint">
          尚无正式 AnnotationRevision；提交后，授权的人工复核人可在此 Review。
        </p>
      ) : (
        <>
          <div className="capture-group-review-boundary" role="status">
            <FileCheck2 aria-hidden="true" size={17} strokeWidth={1.8} />
            <div>
              <strong>人工 Review 操作</strong>
              <span>Candidate 和手部检测只作为证据展示，不会自动批准 Review。</span>
            </div>
          </div>

          <div className="capture-group-review-facts">
            <MetadataList
              ariaLabel="CaptureGroup Review 核心事实"
              items={[
                {
                  id: 'review-group',
                  label: 'CaptureGroup',
                  value: group.capture_group_key ?? group.capture_group_id,
                  secondary: group.capture_group_id,
                },
                {
                  id: 'review-revision',
                  label: 'Revision',
                  value: `第 ${revision.revision_number} 次正式修订`,
                  secondary: revision.annotation_revision_id,
                },
                {
                  id: 'review-primary',
                  label: 'Primary View',
                  value: primaryCameraNumber ? `Cam ${primaryCameraNumber}` : '未提供',
                  secondary: primaryClipAssetId ?? undefined,
                },
                {
                  id: 'review-input-version',
                  label: '冻结 input_version',
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
              <span className="app-eyebrow">Current Group Quality</span>
              <StatusIndicator tone={groupQualityTone(groupQuality?.result ?? 'UNREVIEWED')}>
                {groupQualityLabels[groupQuality?.result ?? 'UNREVIEWED']}
              </StatusIndicator>
              {groupQuality?.reason ? <p>理由：{groupQuality.reason}</p> : null}
              {groupQuality?.decided_at ? (
                <span>记录时间：{formatDateTime(groupQuality.decided_at)}</span>
              ) : null}
            </div>
          </div>

          <ViewSetSummary viewSet={viewSet} />

          <FormalAnnotationIntervals
            primaryCameraNumber={primaryCameraNumber}
            schema={schema}
            segments={revision.annotation_document.segments}
          />

          <div className="capture-group-review-evidence-grid">
            <EvidenceViewList
              title="参与的合格视图"
              views={qualifiedViews}
              emptyText="当前没有已确认的参与视图。"
              tone="success"
            />
            <EvidenceViewList
              title="拒绝 / 缺失视图"
              views={rejectedMissingViews}
              emptyText="没有拒绝或缺失视图。"
              tone="warning"
            />
          </div>

          <div className="capture-group-review-evidence-block">
            <div className="capture-group-review-subheading">
              <div>
                <span className="app-eyebrow">Hand-detection evidence</span>
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
                      候选 {numberValue(evidence, 'candidate_count') ?? 0} 组
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
                当前 Revision 尚无已固定的 Candidate evidence；Camera / View
                工作区中的手部检测仍仅作辅助事实。
              </p>
            )}
          </div>

          <div className="capture-group-review-annotation-history">
            <div className="capture-group-review-subheading">
              <div>
                <span className="app-eyebrow">Annotation history</span>
                <h3>标注备注与历史</h3>
              </div>
              <History aria-hidden="true" size={16} strokeWidth={1.8} />
            </div>
            <ol aria-label="Annotation Revision 与 Review 历史">
              <li>
                <strong>Revision {revision.revision_number} · 已提交</strong>
                <span>
                  {revision.submitted_by} · {formatDateTime(revision.submitted_at)}
                </span>
                <p>{revision.annotation_document.note ?? '未提供 Revision 备注。'}</p>
              </li>
              {review ? (
                <li>
                  <strong>Review · {review.decision === 'APPROVED' ? '已通过' : '已退回'}</strong>
                  <span>
                    {review.reviewed_by} · {formatDateTime(review.reviewed_at)}
                  </span>
                  <p>{review.review_note ?? '未提供 Review 备注。'}</p>
                </li>
              ) : null}
            </ol>
          </div>

          {canReview ? (
            <div className="capture-group-review-actions" aria-label="CaptureGroup Review 操作">
              <Button
                type="primary"
                loading={reviewingDecision === 'APPROVED'}
                disabled={reviewingDecision !== null}
                onClick={() => openReview('APPROVED')}
              >
                通过 Review
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
                  ? '当前 Revision 已完成 Review'
                  : '当前 Revision 已退回，任务已回到待修改'
              }
              description="Review 结果来自服务端；不可对同一 Revision 重复决定。"
            />
          ) : (
            <p className="capture-group-review-permission" role="status">
              <CircleAlert aria-hidden="true" size={16} strokeWidth={1.8} />
              当前账号没有该 Revision 的 Review 权限；仍可查看服务端返回的审阅事实。
            </p>
          )}

          <TrainingEligibilityCheck
            cleaning={cleaning}
            primaryCameraNumber={primaryCameraNumber}
            schema={schema}
            review={review}
            canRecheck={Boolean(task.can_recheck_post_cleaning && review?.decision === 'APPROVED')}
            isRechecking={isRechecking}
            onRecheck={() => void recheckCleaning()}
          />
        </>
      )}
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
          <span className="app-eyebrow">Frozen View Set</span>
          <h3>冻结输入与 View Set</h3>
        </div>
        <span>{viewSet ? `${viewSet.views.length} 路` : '未提供'}</span>
      </div>
      {viewSet ? (
        <MetadataList
          ariaLabel="冻结 View Set 摘要"
          items={[
            { id: 'view-set-schema', label: 'schema', value: viewSet.schema_version },
            {
              id: 'view-set-group-version',
              label: 'CaptureGroup 版本',
              value: viewSet.capture_group_version,
            },
            {
              id: 'view-set-snapshot',
              label: 'CameraSetSnapshot',
              value: viewSet.camera_set_snapshot_id,
            },
            {
              id: 'view-set-receipt',
              label: '收件状态',
              value: receiptStateLabels[viewSet.receipt_state],
            },
          ]}
        />
      ) : (
        <p className="form-hint">当前 Revision 没有返回冻结 View Set 摘要。</p>
      )}
    </div>
  )
}

interface EvidenceViewListProps {
  title: string
  views: CaptureGroupEvidence[]
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
      <strong>Primary View 正式动作时间段</strong>
      <span>
        {primaryCameraNumber ? `Cam ${primaryCameraNumber} · ` : ''}当前 Revision 的正式 action
        timeline
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
        <span>当前 Revision 没有正式动作时间段。</span>
      )}
    </div>
  )
}

function EvidenceViewList({ title, views, emptyText, tone }: EvidenceViewListProps) {
  return (
    <div className="capture-group-review-evidence-block">
      <div className="capture-group-review-subheading">
        <h3>{title}</h3>
        <span>{views.length} 路</span>
      </div>
      {views.length ? (
        <ul className="capture-group-evidence-list" aria-label={title}>
          {views.map((view, index) => {
            const decision = viewQualityDecision(view)
            return (
              <li key={evidenceKey(view, index)}>
                <StatusIndicator tone={tone === 'success' ? 'success' : 'warning'}>
                  {evidenceViewName(view, index)}
                </StatusIndicator>
                <span>
                  {decision ? (viewQualityLabels[decision] ?? decision) : '视图事实已固定'}
                  {stringValue(view, 'rejection_reason')
                    ? ` · ${stringValue(view, 'rejection_reason')}`
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
  cleaning: CaptureGroupCleaning | null
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
  return (
    <div className="capture-group-training-check" aria-label="训练资格检查">
      <div className="capture-group-review-subheading">
        <div>
          <span className="app-eyebrow">Event-level cleaning</span>
          <h3>训练资格检查</h3>
        </div>
        {cleaning ? (
          <StatusBadge tone={cleaningTone(cleaning.outcome)}>
            {cleaning.outcome} · {cleaningLabel(cleaning.outcome)}
          </StatusBadge>
        ) : null}
      </div>
      {cleaning ? (
        <>
          <div className="capture-group-cleaning-summary">
            <StatusIndicator tone={cleaningIndicatorTone(cleaning.outcome)}>
              {cleaning.outcome === 'PASS' ? '训练资格检查通过' : '训练资格检查未通过'}
            </StatusIndicator>
            <span>评估时间：{formatDateTime(cleaning.evaluated_at)}</span>
            {cleaning.evaluated_by ? <span>评估人：{cleaning.evaluated_by}</span> : null}
          </div>
          <MetadataList
            ariaLabel="训练资格检查规则 provenance"
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
              ...(cleaning.authorization_basis
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
          <CleaningList
            title="已执行检查"
            items={cleaning.executed_checks}
            emptyText="未返回检查项。"
          />
          <div className="capture-group-cleaning-evidence-grid">
            <EvidenceViewList
              title="参与视图"
              views={cleaning.participating_views}
              emptyText="没有参与视图。"
              tone="success"
            />
            <EvidenceViewList
              title="排除视图"
              views={cleaning.excluded_views}
              emptyText="没有排除视图。"
              tone="warning"
            />
          </div>
          <CleaningPrimaryInterval
            primaryCameraNumber={primaryCameraNumber}
            primaryInterval={cleaning.primary_interval ?? []}
            schema={schema}
          />
          <EventLevelViewEvidence viewEvidence={cleaning.view_evidence ?? []} />
          {canRecheck ? (
            <Button type="link" size="small" loading={isRechecking} onClick={onRecheck}>
              重新检查训练资格
            </Button>
          ) : null}
        </>
      ) : (
        <p className="form-hint">
          {review?.decision === 'APPROVED'
            ? 'Review 已通过；服务端尚未返回训练资格检查结果。'
            : 'Review 通过后，服务端会在同一事务内生成训练资格检查结果。这里不是第二个人工质量决定。'}
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
  items: string[]
  emptyText: string
}) {
  return (
    <div className="capture-group-cleaning-list">
      <strong>{title}</strong>
      {items.length ? (
        <ul>
          {items.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>
      ) : (
        <span>{emptyText}</span>
      )}
    </div>
  )
}

function cleaningIndicatorTone(outcome: CaptureGroupCleaning['outcome']): StatusIndicatorTone {
  if (outcome === 'PASS') return 'success'
  if (outcome === 'REJECT') return 'destructive'
  return 'warning'
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
      <strong>Primary View 正式时间轴</strong>
      <span>
        {primaryCameraNumber ? `Cam ${primaryCameraNumber} · ` : ''}只展示正式 action interval
      </span>
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
        <strong>事件级 supporting view evidence</strong>
        <span>{viewEvidence.length} 路</span>
      </div>
      {viewEvidence.length ? (
        <ul className="capture-group-evidence-list" aria-label="事件级 supporting view evidence">
          {viewEvidence.map((view, index) => {
            const primary = booleanValue(view, 'action_aligned_interval')
            return (
              <li key={evidenceKey(view, index)}>
                <strong>{evidenceViewName(view, index)}</strong>
                <span>
                  {primary ? 'Primary View · 正式时间基准' : 'Supporting View · 事件级证据'}
                </span>
                <small>
                  {stringValue(view, 'sync_reliability')
                    ? `同步证据：${stringValue(view, 'sync_reliability')}`
                    : '不生成支持视图 action timestamp'}
                </small>
              </li>
            )
          })}
        </ul>
      ) : (
        <p className="form-hint">未返回事件级视图证据。</p>
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
  return cameraNumber === null ? `视图 ${index + 1}` : `Cam ${cameraNumber}`
}

function viewQualityDecision(evidence: CaptureGroupEvidence) {
  const decision =
    stringValue(evidence, 'quality_decision') ?? stringValue(evidence, 'quality_review_decision')
  return decision && decision in viewQualityLabels
    ? (decision as keyof typeof viewQualityLabels)
    : null
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
