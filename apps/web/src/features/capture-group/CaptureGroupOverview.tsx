import type { CaptureGroup } from '@/api/capture-groups'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { receiptStateLabels, receiptTone } from '@/features/capture-group-presentation'
import { formatDateTime } from '@/features/catalog/catalog-presentation'

interface CaptureGroupOverviewProps {
  group: CaptureGroup
  currentStageLabel: string
}

export function CaptureGroupOverview({ group, currentStageLabel }: CaptureGroupOverviewProps) {
  return (
    <section className="capture-group-overview" aria-label="配药事件关键事实">
      <div className="capture-group-overview-card">
        <span className="app-eyebrow">拍摄时间</span>
        <strong>{formatDateTime(group.device_time_utc ?? group.created_at)}</strong>
        <span>{group.device_timezone ?? '时区未提供'}</span>
      </div>
      <div className="capture-group-overview-card">
        <span className="app-eyebrow">机位数量</span>
        <strong>{group.expected_camera_count} 个机位</strong>
        <span>
          应 {group.expected_camera_count} 个机位 · 已收 {group.received_camera_numbers.length}{' '}
          个机位 · 缺失 {group.missing_camera_numbers.length} 个机位
        </span>
      </div>
      <div className="capture-group-overview-card">
        <span className="app-eyebrow">当前阶段</span>
        <StatusIndicator tone="information">{currentStageLabel}</StatusIndicator>
        <span>阶段与可执行动作以当前服务端事实为准。</span>
      </div>
    </section>
  )
}

export function CaptureGroupHeaderStatus({ group }: { group: CaptureGroup }) {
  return (
    <span className="capture-group-header-status">
      <StatusIndicator tone={receiptTone(group.receipt_state)}>
        <span>接收状态</span> · <span>{receiptStateLabels[group.receipt_state]}</span>
      </StatusIndicator>
      <span>
        {formatDateTime(group.device_time_utc ?? group.created_at)} · {group.expected_camera_count}{' '}
        个机位
      </span>
    </span>
  )
}
