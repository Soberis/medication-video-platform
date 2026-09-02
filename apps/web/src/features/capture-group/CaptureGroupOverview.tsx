import type { CaptureGroup } from '@/api/capture-groups'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { receiptStateLabels, receiptTone } from '@/features/capture-group-presentation'
import { formatDateTime } from '@/features/catalog/catalog-presentation'

export function CaptureGroupHeaderStatus({ group }: { group: CaptureGroup }) {
  const captureTime = group.device_time_utc ?? group.created_at
  const viewCount =
    group.received_camera_count === group.expected_camera_count
      ? `${group.expected_camera_count} 个机位`
      : `已收到 ${group.received_camera_count}/${group.expected_camera_count} 个机位`

  return (
    <span className="capture-group-header-status">
      <StatusIndicator tone={receiptTone(group.receipt_state)}>
        {receiptStateLabels[group.receipt_state]}
      </StatusIndicator>
      <span>
        拍摄 {formatDateTime(captureTime)} · {viewCount}
      </span>
    </span>
  )
}
