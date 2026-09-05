import { Alert, App as AntdApp, Button, Input, Select } from 'antd'
import { useEffect, useState } from 'react'

import { assignCaptureGroupHumanLabelTask } from '@/api/annotation'
import {
  recordCaptureGroupQualityReview,
  type CaptureGroup,
  type CaptureGroupQualityCommandResult,
  type CaptureGroupQualityReview,
} from '@/api/capture-groups'
import {
  recordQualityReview,
  type QualityReview,
  type QualityReviewCommandDecision,
  type QualityReviewReasonCode,
} from '@/api/quality'
import type { ClipAssetRead } from '@/api/catalog'
import { QualityReviewCard } from '@/components/AssetDetailSections'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { Section } from '@/components/workspace/Section'
import { InspectorSection } from '@/components/workspace/InspectorSection'
import {
  currentGroupQualityAdmitting,
  qualityReasonsByDecision,
  selectedQualityLoadState,
  type GroupViewRow,
} from '@/features/capture-group/capture-group-detail-model'
import {
  groupQualityDisplayLabel,
  groupQualityDisplayTone,
  groupQualityLabels,
  type CaptureGroupQualityVisibility,
} from '@/features/capture-group-presentation'
import type { CaptureGroupDetailRefreshOptions } from '@/features/capture-group/use-capture-group-detail-data'

interface CaptureGroupQualityPanelProps {
  projectId: string
  captureGroupId: string
  group: CaptureGroup
  groupQuality: CaptureGroupQualityReview | null
  groupQualityVisibility: CaptureGroupQualityVisibility
  refreshVersion: number
  selectedStream: GroupViewRow['stream']
  selectedDetail: ClipAssetRead | null
  selectedReview: QualityReview | null
  viewReviews: Record<string, QualityReview>
  viewReviewErrors: Record<string, string>
  viewQualityLoading: boolean
  onReviewSaved: (review: QualityReview) => void
  assigneeActorId: string
  canAssignHumanLabel: boolean
  onRefresh: (options?: CaptureGroupDetailRefreshOptions) => Promise<boolean>
}

export function CaptureGroupQualityPanel({
  projectId,
  captureGroupId,
  group,
  groupQuality,
  groupQualityVisibility,
  refreshVersion,
  selectedStream,
  selectedDetail,
  selectedReview,
  viewReviews,
  viewReviewErrors,
  viewQualityLoading,
  onReviewSaved,
  assigneeActorId,
  canAssignHumanLabel,
  onRefresh,
}: CaptureGroupQualityPanelProps) {
  const { message } = AntdApp.useApp()
  const [viewDecision, setViewDecision] = useState<QualityReviewCommandDecision>('QUALIFIED')
  const [viewReason, setViewReason] = useState<QualityReviewReasonCode>('MEETS_REQUIREMENTS')
  const [groupDecision, setGroupDecision] =
    useState<CaptureGroupQualityCommandResult>('NEEDS_REVIEW')
  const [groupReason, setGroupReason] = useState('')
  const [saving, setSaving] = useState<'view' | 'group' | 'task' | null>(null)

  useEffect(() => {
    const decision = selectedReview?.decision
    const nextDecision: QualityReviewCommandDecision =
      decision === 'QUALIFIED' || decision === 'REJECTED' || decision === 'NEEDS_REVIEW'
        ? decision
        : 'QUALIFIED'
    const timer = window.setTimeout(() => {
      setViewDecision(nextDecision)
      setViewReason(
        selectedReview?.reason_code &&
          qualityReasonsByDecision[nextDecision].includes(selectedReview.reason_code)
          ? selectedReview.reason_code
          : (qualityReasonsByDecision[nextDecision][0] ?? 'MEETS_REQUIREMENTS'),
      )
    }, 0)
    return () => window.clearTimeout(timer)
  }, [selectedReview])

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

  const viewQualityState = selectedQualityLoadState(
    selectedStream,
    viewReviews,
    viewReviewErrors,
    viewQualityLoading,
  )
  const viewQualityError = selectedStream
    ? (viewReviewErrors[selectedStream.clip_asset_id] ?? '')
    : ''
  const receiving = group.receipt_state === 'RECEIVING'
  const qualityReadable = groupQualityVisibility === 'loaded' && Boolean(groupQuality)

  async function saveViewQuality() {
    if (!selectedStream || !selectedDetail || selectedDetail.status !== 'INGESTED' || saving) return
    setSaving('view')
    try {
      const review = await recordQualityReview(projectId, selectedStream.clip_asset_id, {
        decision: viewDecision,
        reason_code: viewReason,
        expected_version: selectedReview?.version ?? 0,
      })
      onReviewSaved(review)
      const refreshed = await onRefresh({ focusNextPendingView: true })
      void (refreshed
        ? message.success(`机位 ${selectedStream.camera_number} 的质量已保存。`)
        : message.warning('机位质量已保存，但最新事实暂未刷新；请手动刷新确认。'))
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '机位质量暂未保存，请稍后重试。')
    } finally {
      setSaving(null)
    }
  }

  async function saveGroupQuality() {
    if (!qualityReadable || !groupQuality || !groupReason.trim() || saving) return
    if (receiving && groupDecision !== 'NEEDS_REVIEW') return
    setSaving('group')
    try {
      await recordCaptureGroupQualityReview(
        projectId,
        captureGroupId,
        groupDecision,
        groupReason,
        groupQuality.revision,
      )
      const refreshed = await onRefresh()
      void (refreshed
        ? message.success('整体质量已记录，现在可以按需开始标注。')
        : message.warning('整体质量已记录，但最新事实暂未刷新；请手动刷新确认。'))
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '整体质量暂未保存，请稍后重试。')
    } finally {
      setSaving(null)
    }
  }

  async function assignHumanLabelTask() {
    const assignee = assigneeActorId.trim()
    if (!canAssignHumanLabel || !assignee || saving) return
    setSaving('task')
    try {
      await assignCaptureGroupHumanLabelTask(projectId, captureGroupId, assignee)
      const refreshed = await onRefresh()
      void (refreshed
        ? message.success('人工动作标注任务已创建，下面将显示标注工作台。')
        : message.warning('任务已创建，但最新事实暂未刷新；请手动刷新确认。'))
    } catch (error) {
      void message.error(
        error instanceof Error ? error.message : '人工动作标注任务暂未创建，请稍后重试。',
      )
    } finally {
      setSaving(null)
    }
  }

  return (
    <Section
      className="capture-group-human-label-setup"
      id="capture-group-human-label-setup"
      title="人工标注"
      titleId="capture-group-human-label-setup-title"
    >
      <p className="form-hint">需要动作标签时，先检查视频是否适合标注。手部关键点可独立导出。</p>
      <details className="capture-group-progressive-detail">
        <summary>标注前检查</summary>
        <div className="capture-group-progressive-detail-content">
          {selectedStream && selectedDetail ? (
            <QualityReviewCard
              assetStatus={selectedDetail.status}
              canReview={selectedDetail.status === 'INGESTED'}
              errorCode={viewQualityState === 'error' ? 'VIEW_QUALITY_REQUEST_FAILED' : ''}
              errorMessage={viewQualityError || '当前机位质量暂时无法读取。'}
              isSaving={saving === 'view'}
              loadState={viewQualityState}
              review={selectedReview}
              selectedDecision={viewDecision}
              selectedReason={viewReason}
              onSave={() => void saveViewQuality()}
              onSelectionChange={(decision, reason) => {
                setViewDecision(decision)
                setViewReason(reason)
              }}
            />
          ) : (
            <p className="form-hint">选择一路已接收机位后记录机位质量。</p>
          )}

          <InspectorSection
            id="capture-group-group-quality"
            title="整体质量"
            titleId="capture-group-quality-inspector"
          >
            <div className="capture-group-inspector-status">
              <StatusIndicator tone={groupQualityDisplayTone(groupQualityVisibility, groupQuality)}>
                {groupQualityDisplayLabel(groupQualityVisibility, groupQuality)}
              </StatusIndicator>
              {groupQuality?.reviewer_actor_id ? (
                <span>记录人：{groupQuality.reviewer_actor_id}</span>
              ) : null}
            </div>
            {groupQuality?.reason ? <p className="form-hint">理由：{groupQuality.reason}</p> : null}
            {groupQualityVisibility === 'loading' ? (
              <p className="form-hint" role="status">
                正在读取整体质量…
              </p>
            ) : null}
            {groupQualityVisibility === 'forbidden' ? (
              <Alert
                type="warning"
                showIcon
                title="整体质量无权查看"
                description="当前账号不能读取或修改整体质量。"
              />
            ) : null}
            {groupQualityVisibility === 'unavailable' ? (
              <Alert
                type="warning"
                showIcon
                title="整体质量暂不可用"
                description="请刷新后重试。"
              />
            ) : null}
            {qualityReadable ? (
              <>
                {receiving ? (
                  <Alert
                    type="info"
                    showIcon
                    title="接收仍在进行中"
                    description="服务器当前只接受“需复核”；结束接收后才能提交最终整体质量结论。"
                  />
                ) : null}
                <label className="capture-group-decision-field">
                  <span>整体质量结论</span>
                  <Select
                    aria-label="整体质量结论"
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
                    onChange={(value) =>
                      setGroupDecision(value as CaptureGroupQualityCommandResult)
                    }
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
                  loading={saving === 'group'}
                  disabled={
                    !groupReason.trim() ||
                    Boolean(saving) ||
                    (receiving && groupDecision !== 'NEEDS_REVIEW')
                  }
                  onClick={() => void saveGroupQuality()}
                >
                  保存整体质量
                </Button>
              </>
            ) : null}
          </InspectorSection>
        </div>
      </details>
      {currentGroupQualityAdmitting(groupQuality) ? (
        <div className="capture-group-quality-boundary" role="status">
          <div>
            <strong>视频已满足标注条件</strong>
            <span>开始后在本页添加动作与时间段。</span>
          </div>
          {canAssignHumanLabel ? (
            <Button
              type="primary"
              loading={saving === 'task'}
              disabled={Boolean(saving) || !assigneeActorId.trim()}
              onClick={() => void assignHumanLabelTask()}
            >
              开始标注
            </Button>
          ) : (
            <span>请由项目管理员开始。</span>
          )}
        </div>
      ) : null}
    </Section>
  )
}
