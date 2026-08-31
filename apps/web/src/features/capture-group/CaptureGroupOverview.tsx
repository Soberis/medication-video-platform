import type { CaptureGroup } from '@/api/capture-groups'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { receiptStateLabels, receiptTone } from '@/features/capture-group-presentation'
import { formatDateTime } from '@/features/catalog/catalog-presentation'

export function CaptureGroupHeaderStatus({ group }: { group: CaptureGroup }) {
  return (
    <span className="capture-group-header-status">
      <StatusIndicator tone={receiptTone(group.receipt_state)}>
        {receiptStateLabels[group.receipt_state]}
      </StatusIndicator>
      <span>
        {formatDateTime(group.device_time_utc ?? group.created_at)} · {group.expected_camera_count}{' '}
        个机位
      </span>
    </span>
  )
}
