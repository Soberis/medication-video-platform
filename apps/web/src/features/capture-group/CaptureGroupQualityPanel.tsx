import { App as AntdApp, Alert, Button, Input, Select } from 'antd'
import { useEffect, useState } from 'react'

import {
  recordQualityReview,
  type QualityReview,
  type QualityReviewCommandDecision,
  type QualityReviewReasonCode,
} from '@/api/quality'
import {
  recordCaptureGroupQualityReview,
  type CaptureGroup,
  type CaptureGroupQualityCommandResult,
  type CaptureGroupQualityReview,
} from '@/api/capture-groups'
import type { ClipAssetRead } from '@/api/catalog'
import { QualityReviewCard } from '@/components/AssetDetailSections'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { Section } from '@/components/workspace/Section'
import { InspectorSection } from '@/components/workspace/InspectorSection'
import {
  qualityReasonsByDecision,
  selectedQualityLoadState as getSelectedQualityLoadState,
  type GroupViewRow,
} from '@/features/capture-group/capture-group-detail-model'
import {
  groupQualityDisplayLabel,
  groupQualityDisplayTone,
  groupQualityLabels,
  groupQualityTone,
  type CaptureGroupQualityVisibility,
  viewQualityLabels,
} from '@/features/capture-group-presentation'
import { formatDateTime } from '@/features/catalog/catalog-presentation'

interface CaptureGroupViewQualitySectionProps {
  projectId: string
  selectedStream: GroupViewRow['stream']
  selectedDetail: ClipAssetRead | null
  selectedReview: QualityReview | null
  viewReviews: Record<string, QualityReview>
  viewReviewErrors: Record<string, string>
  viewQualityLoading: boolean
  onReviewSaved: (review: QualityReview) => void
  onRefresh: () => Promise<void>
}

export function CaptureGroupViewQualitySection({
  projectId,
  selectedStream,
  selectedDetail,
  selectedReview,
  viewReviews,
  viewReviewErrors,
  viewQualityLoading,
  onReviewSaved,
  onRefresh,
}: CaptureGroupViewQualitySectionProps) {
  const { message } = AntdApp.useApp()
  const [selectedDecision, setSelectedDecision] =
    useState<QualityReviewCommandDecision>('QUALIFIED')
  const [selectedReason, setSelectedReason] =
    useState<QualityReviewReasonCode>('MEETS_REQUIREMENTS')
  const [isSaving, setIsSaving] = useState(false)

  useEffect(() => {
    const decision = selectedReview?.decision
    const nextDecision: QualityReviewCommandDecision =
      decision === 'REJECTED' || decision === 'NEEDS_REVIEW' || decision === 'QUALIFIED'
        ? decision
        : 'QUALIFIED'
    const timer = window.setTimeout(() => {
      setSelectedDecision(nextDecision)
      setSelectedReason(
        selectedReview?.reason_code &&
          qualityReasonsByDecision[nextDecision].includes(selectedReview.reason_code)
          ? selectedReview.reason_code
          : (qualityReasonsByDecision[nextDecision][0] ?? 'MEETS_REQUIREMENTS'),
      )
    }, 0)
    return () => window.clearTimeout(timer)
  }, [selectedReview])

  const qualityLoadState = getSelectedQualityLoadState(
    selectedStream,
    viewReviews,
    viewReviewErrors,
    viewQualityLoading,
  )
  const selectedQualityError = selectedStream
    ? (viewReviewErrors[selectedStream.clip_asset_id] ?? '')
    : ''

  async function saveViewQuality() {
    if (!selectedStream || !selectedDetail || selectedDetail.status !== 'INGESTED' || isSaving) {
      return
    }
    setIsSaving(true)
    try {
      const review = await recordQualityReview(projectId, selectedStream.clip_asset_id, {
        decision: selectedDecision,
        reason_code: selectedReason,
        expected_version: selectedReview?.version ?? 0,
      })
      onReviewSaved(review)
      void message.success(`机位 ${selectedStream.camera_number} 的机位质量已保存。`)
      await onRefresh()
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '机位质量暂未保存，请稍后重试。')
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <Section
      className="capture-group-view-quality-workspace"
      title="机位质量"
      titleId="capture-group-view-quality-title"
      actions={<span className="capture-group-section-note">自动证据与人工决定分开保存</span>}
    >
      <div className="capture-group-quality-boundary">
        <div>
          <strong>自动检测事实</strong>
          <span>媒体检测、技术检测和手部检测只作为事实展示。</span>
        </div>
        <div>
          <strong>人工决定</strong>
          <span>合格、需复核或不合格由人工记录；不合格机位仍保留。</span>
        </div>
      </div>
      {selectedStream && selectedDetail ? (
        <QualityReviewCard
          assetStatus={selectedDetail.status}
          canReview={selectedDetail.status === 'INGESTED'}
          errorCode={qualityLoadState === 'error' ? 'VIEW_QUALITY_REQUEST_FAILED' : ''}
          errorMessage={selectedQualityError || '当前机位质量暂时无法读取。'}
          isSaving={isSaving}
          loadState={qualityLoadState}
          review={selectedReview}
          selectedDecision={selectedDecision}
          selectedReason={selectedReason}
          onSave={() => void saveViewQuality()}
          onSelectionChange={(decision, reason) => {
            setSelectedDecision(decision)
            setSelectedReason(reason)
          }}
        />
      ) : (
        <p className="form-hint">选择一路已接收机位后记录人工机位质量。</p>
      )}
    </Section>
  )
}

interface CaptureGroupQualityPanelProps {
  projectId: string
  captureGroupId: string
  group: CaptureGroup
  groupQuality: CaptureGroupQualityReview | null
  groupQualityVisibility: CaptureGroupQualityVisibility
  qualityHistory: CaptureGroupQualityReview[]
  refreshVersion: number
  onRefresh: () => Promise<void>
}

export function CaptureGroupQualityPanel({
  projectId,
  captureGroupId,
  group,
  groupQuality,
  groupQualityVisibility,
  qualityHistory,
  refreshVersion,
  onRefresh,
}: CaptureGroupQualityPanelProps) {
  const { message } = AntdApp.useApp()
  const [groupDecision, setGroupDecision] =
    useState<CaptureGroupQualityCommandResult>('NEEDS_REVIEW')
  const [groupReason, setGroupReason] = useState('')
  const [isSaving, setIsSaving] = useState(false)
  const receiving = group.receipt_state === 'RECEIVING'
  const qualityReadable = groupQualityVisibility === 'loaded' && Boolean(groupQuality)
  const saveDisabled =
    !qualityReadable ||
    !groupReason.trim() ||
    isSaving ||
    (receiving && groupDecision !== 'NEEDS_REVIEW')

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setGroupDecision(
        groupQuality?.result && groupQuality.result !== 'UNREVIEWED'
          ? groupQuality.result
          : 'NEEDS_REVIEW',
      )
      setGroupReason(groupQuality?.reason ?? '')
    }, 0)
    return () => window.clearTimeout(timer)
  }, [groupQuality, refreshVersion])

  async function saveGroupQuality() {
    if (!qualityReadable || !groupQuality || !groupReason.trim() || isSaving) return
    if (receiving && groupDecision !== 'NEEDS_REVIEW') return
    setIsSaving(true)
    try {
      await recordCaptureGroupQualityReview(
        projectId,
        captureGroupId,
        groupDecision,
        groupReason,
        groupQuality.revision,
      )
      void message.success('事件质量已记录，正在刷新任务与机位快照。')
      await onRefresh()
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '事件质量暂未保存，请稍后重试。')
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <InspectorSection title="事件质量" titleId="capture-group-quality-inspector">
      <div className="capture-group-inspector-status">
        <StatusIndicator tone={groupQualityDisplayTone(groupQualityVisibility, groupQuality)}>
          {groupQualityDisplayLabel(groupQualityVisibility, groupQuality)}
        </StatusIndicator>
        {groupQuality?.reviewer_actor_id ? (
          <span>记录人：{groupQuality.reviewer_actor_id}</span>
        ) : null}
        {groupQuality?.decided_at ? <span>{formatDateTime(groupQuality.decided_at)}</span> : null}
      </div>
      {groupQuality?.reason ? <p className="form-hint">理由：{groupQuality.reason}</p> : null}
      {groupQualityVisibility === 'unavailable' ? (
        <Alert
          type="warning"
          showIcon
          title="事件质量暂不可用"
          description="当前不能判断或修改事件质量；请刷新后重试。"
        />
      ) : null}
      {groupQualityVisibility === 'forbidden' ? (
        <Alert
          type="warning"
          showIcon
          title="事件质量无权查看"
          description="当前账号不能读取或修改事件质量；接收与机位事实仍可查看。"
        />
      ) : null}
      {groupQualityVisibility === 'loading' ? (
        <p className="form-hint" role="status">
          正在读取事件质量…
        </p>
      ) : null}
      {qualityReadable ? (
        <>
          {receiving ? (
            <Alert
              type="info"
              showIcon
              title="接收仍在进行中"
              description="最终“合格”或“合格（排除部分机位）”操作已禁用；服务器只接受“需复核”。"
            />
          ) : null}
          <label className="capture-group-decision-field">
            <span>记录新的事件质量结论</span>
            <Select
              aria-label="事件质量结论"
              value={groupDecision}
              options={[
                {
                  value: 'QUALIFIED',
                  label: groupQualityLabels.QUALIFIED,
                  disabled: receiving,
                },
                {
                  value: 'QUALIFIED_WITH_EXCLUSIONS',
                  label: groupQualityLabels.QUALIFIED_WITH_EXCLUSIONS,
                  disabled: receiving,
                },
                { value: 'NEEDS_REVIEW', label: groupQualityLabels.NEEDS_REVIEW },
                {
                  value: 'REJECTED',
                  label: groupQualityLabels.REJECTED,
                  disabled: receiving,
                },
              ]}
              onChange={(value) => setGroupDecision(value as CaptureGroupQualityCommandResult)}
            />
          </label>
          <label className="capture-group-decision-field">
            <span>结论理由</span>
            <Input.TextArea
              value={groupReason}
              maxLength={1000}
              autoSize={{ minRows: 2, maxRows: 4 }}
              onChange={(event) => setGroupReason(event.target.value)}
            />
          </label>
          <Button
            type="primary"
            loading={isSaving}
            disabled={saveDisabled}
            onClick={() => void saveGroupQuality()}
          >
            保存事件质量
          </Button>
          {groupQuality?.confirmed_view_set ? (
            <div className="capture-group-confirmed-view-set capture-group-stack">
              <strong>本次结论使用的机位集合</strong>
              <span>
                {groupQuality.confirmed_view_set.views.length} 个机位 · 输入版本{' '}
                {groupQuality.input_version ?? '未提供'}
              </span>
              <ul>
                {groupQuality.confirmed_view_set.views.map((view) => (
                  <li key={view.camera_number}>
                    机位 {view.camera_number} · {viewQualityLabels[view.quality_review_decision]}
                    {view.quality_review_decision === 'REJECTED' ? ' · 已排除但保留' : ''}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </>
      ) : null}
      {qualityHistory.length ? (
        <details className="capture-group-quality-history capture-group-stack">
          <summary>查看追加质量历史（{qualityHistory.length} 条）</summary>
          <ol>
            {qualityHistory.map((entry) => (
              <li key={entry.capture_group_quality_review_id ?? `revision-${entry.revision}`}>
                <StatusIndicator tone={groupQualityTone(entry.result)}>
                  第 {entry.revision} 次 · {groupQualityLabels[entry.result]}
                </StatusIndicator>
                <span>{entry.reason ?? '未提供理由'}</span>
                <small>{formatDateTime(entry.decided_at)}</small>
              </li>
            ))}
          </ol>
        </details>
      ) : null}
    </InspectorSection>
  )
}
