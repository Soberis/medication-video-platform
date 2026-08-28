import type { CaptureGroup, CaptureGroupQualityReview } from '@/api/capture-groups'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import {
  groupQualityLabels,
  groupQualityTone,
  receiptStateLabels,
  receiptTone,
} from '@/features/capture-group-presentation'
import { formatDateTime } from '@/features/catalog/catalog-presentation'

interface CaptureGroupOverviewProps {
  group: CaptureGroup
  groupQuality: CaptureGroupQualityReview | null
}

export function CaptureGroupOverview({ group, groupQuality }: CaptureGroupOverviewProps) {
  const result = groupQuality?.result ?? 'UNREVIEWED'

  return (
    <section className="capture-group-overview" aria-label="配药事件接收摘要">
      <div className="capture-group-overview-card">
        <span className="app-eyebrow">接收状态</span>
        <strong>{receiptStateLabels[group.receipt_state]}</strong>
        <span>
          应 {group.expected_camera_count} 个机位 · 已收 {group.received_camera_numbers.length}{' '}
          个机位 · 缺失 {group.missing_camera_numbers.length} 个机位
        </span>
      </div>
      <div className="capture-group-overview-card">
        <span className="app-eyebrow">事件质量</span>
        <StatusIndicator tone={groupQualityTone(result)}>
          {groupQualityLabels[result]}
        </StatusIndicator>
        <span>服务端结论；页面不计算事件质量。</span>
      </div>
      <div className="capture-group-overview-card">
        <span className="app-eyebrow">拍摄时间</span>
        <strong>{formatDateTime(group.device_time_utc ?? group.created_at)}</strong>
        <span>{group.device_timezone ?? '时区未提供'}</span>
      </div>
    </section>
  )
}

export function CaptureGroupHeaderStatus({ group }: { group: CaptureGroup }) {
  return (
    <span className="capture-group-header-status">
      <StatusIndicator tone={receiptTone(group.receipt_state)}>
        接收状态 · {receiptStateLabels[group.receipt_state]}
      </StatusIndicator>
      <span>{group.capture_group_id}</span>
    </span>
  )
}
