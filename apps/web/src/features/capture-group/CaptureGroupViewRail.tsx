import { Alert } from 'antd'
import { ShieldAlert } from 'lucide-react'

import type { CaptureGroup } from '@/api/capture-groups'
import type { HandPoseCandidateResponse } from '@/api/catalog'
import type { QualityReview } from '@/api/quality'
import { StatusIndicator, type StatusIndicatorTone } from '@/components/status/StatusIndicator'
import { Section } from '@/components/workspace/Section'
import type { CandidateLoadState } from '@/features/catalog/hand-pose-candidate-state'
import {
  candidateStatusLabel,
  groupViewDecision,
  type GroupViewRow,
} from '@/features/capture-group/capture-group-detail-model'
import {
  mediaStatusLabels,
  streamTone,
  technicalQualityLabels,
  viewQualityLabels,
  viewQualityTone,
} from '@/features/capture-group-presentation'

interface CaptureGroupViewRailProps {
  group: CaptureGroup
  viewRows: GroupViewRow[]
  selectedClipAssetId: string | null
  viewReviews: Record<string, QualityReview>
  candidateResponse: HandPoseCandidateResponse | null
  candidateState: CandidateLoadState
  candidateTone: StatusIndicatorTone
  onSelectView: (clipAssetId: string) => void
}

export function CaptureGroupViewRail({
  group,
  viewRows,
  selectedClipAssetId,
  viewReviews,
  candidateResponse,
  candidateState,
  candidateTone,
  onSelectView,
}: CaptureGroupViewRailProps) {
  return (
    <Section
      className="capture-group-views-section"
      title="Camera / View"
      titleId="capture-group-views-title"
      actions={
        <span className="capture-group-section-note">相机编号来自冻结 CameraSetSnapshot</span>
      }
    >
      <div className="capture-group-view-list" role="list" aria-label="CaptureGroup 相机视图列表">
        {viewRows.map((view) => {
          const stream = view.stream
          const selected = Boolean(stream && stream.clip_asset_id === selectedClipAssetId)
          const decision = groupViewDecision(view, viewReviews)
          const isRejected = decision === 'REJECTED'
          return (
            <article
              className={`capture-group-view-row${selected ? ' is-selected' : ''}${isRejected ? ' is-rejected' : ''}`}
              key={view.cameraNumber}
              role="listitem"
              aria-label={`相机 ${view.cameraNumber} · ${stream ? (mediaStatusLabels[stream.stream_status] ?? stream.stream_status) : '缺失'}`}
            >
              <button
                className="capture-group-view-select"
                type="button"
                disabled={!stream}
                aria-pressed={selected}
                onClick={() => stream && onSelectView(stream.clip_asset_id)}
              >
                <span className="capture-group-camera-number">Cam {view.cameraNumber}</span>
                <span className="capture-group-view-copy">
                  <strong>{view.member?.camera_role ?? '相机成员未提供'}</strong>
                  <span>{view.member?.mount_position ?? '安装位置未提供'}</span>
                </span>
              </button>
              <div className="capture-group-view-facts">
                <StatusIndicator tone={streamTone(stream)}>
                  媒体 ·{' '}
                  {stream
                    ? (mediaStatusLabels[stream.stream_status] ?? stream.stream_status)
                    : '缺失'}
                </StatusIndicator>
                <StatusIndicator tone={viewQualityTone(decision)}>
                  人工质量 · {viewQualityLabels[decision]}
                </StatusIndicator>
                {selected && stream ? (
                  <StatusIndicator tone={candidateTone}>
                    AI Candidate · {candidateStatusLabel(candidateState, candidateResponse)}
                  </StatusIndicator>
                ) : null}
                {stream?.quality_status ? (
                  <span className="capture-group-view-secondary-fact">
                    技术质量 ·{' '}
                    {technicalQualityLabels[stream.quality_status] ?? stream.quality_status}
                  </span>
                ) : null}
              </div>
              <div className="capture-group-view-actions">
                {isRejected ? <ShieldAlert aria-hidden="true" size={16} strokeWidth={1.8} /> : null}
                <span>{selected ? '已聚焦' : stream ? '查看' : '缺失'}</span>
              </div>
            </article>
          )
        })}
      </div>
      {group.missing_camera_numbers.length ? (
        <Alert
          className="capture-group-missing-note"
          type="warning"
          showIcon
          title={`缺失相机：${group.missing_camera_numbers.map((number) => `Cam ${number}`).join('、')}`}
          description="缺失编号保留在原位置；不会被重新编号，也不会被浏览器当作质量结论。"
        />
      ) : null}
    </Section>
  )
}
