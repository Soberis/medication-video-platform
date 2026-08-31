import { Alert } from 'antd'

import type { CaptureGroup } from '@/api/capture-groups'
import type { QualityReview } from '@/api/quality'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { Section } from '@/components/workspace/Section'
import {
  groupViewDecision,
  type GroupViewRow,
} from '@/features/capture-group/capture-group-detail-model'
import {
  mediaStatusLabels,
  streamTone,
  viewQualityLabels,
  viewQualityTone,
} from '@/features/capture-group-presentation'

interface CaptureGroupViewRailProps {
  group: CaptureGroup
  viewRows: GroupViewRow[]
  selectedClipAssetId: string | null
  viewReviews: Record<string, QualityReview>
  onSelectView: (clipAssetId: string) => void
}

export function CaptureGroupViewRail({
  group,
  viewRows,
  selectedClipAssetId,
  viewReviews,
  onSelectView,
}: CaptureGroupViewRailProps) {
  if (viewRows.length <= 1) return null

  return (
    <Section
      className="capture-group-views-section"
      title="机位"
      titleId="capture-group-views-title"
      actions={<span className="capture-group-section-note">选择一路机位查看当前视频</span>}
    >
      <div className="capture-group-view-list" role="list" aria-label="配药事件机位列表">
        {viewRows.map((view) => {
          const stream = view.stream
          const selected = Boolean(stream && stream.clip_asset_id === selectedClipAssetId)
          const decision = groupViewDecision(view, viewReviews)
          return (
            <article
              className={`capture-group-view-row${selected ? ' is-selected' : ''}`}
              key={view.cameraNumber}
              role="listitem"
              aria-label={`机位 ${view.cameraNumber} · ${stream ? (mediaStatusLabels[stream.stream_status] ?? stream.stream_status) : '缺失'}`}
            >
              <button
                className="capture-group-view-select"
                type="button"
                disabled={!stream}
                aria-pressed={selected}
                onClick={() => stream && onSelectView(stream.clip_asset_id)}
              >
                <span className="capture-group-camera-number">机位 {view.cameraNumber}</span>
                <span className="capture-group-view-copy">
                  <strong>{stream ? '已接收' : '缺失'}</strong>
                  <span>{selected ? '当前聚焦' : '切换查看'}</span>
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
                  机位质量 · {viewQualityLabels[decision]}
                </StatusIndicator>
              </div>
              <div className="capture-group-view-actions">
                <span>{selected ? '当前' : stream ? '查看' : '缺失'}</span>
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
          title={`缺失机位：${group.missing_camera_numbers.map((number) => `机位 ${number}`).join('、')}`}
          description="缺失编号保留在原位置；不会被重新编号，也不会被浏览器当作质量结论。"
        />
      ) : null}
    </Section>
  )
}
