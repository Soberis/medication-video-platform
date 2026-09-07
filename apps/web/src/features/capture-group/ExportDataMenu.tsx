import { Button, Popover } from 'antd'
import { Download } from 'lucide-react'
import { useState } from 'react'

import { HumanDemonstrationHdf5ExportButton } from './HumanDemonstrationHdf5ExportButton'

export function ExportDataMenu({
  projectId,
  selectedCaptureGroupIds,
}: {
  projectId: string
  selectedCaptureGroupIds: string[]
}) {
  const [open, setOpen] = useState(false)
  return (
    <Popover
      open={open}
      onOpenChange={setOpen}
      title="导出数据"
      trigger="click"
      placement="bottomRight"
      content={
        <div className="capture-group-export-options">
          <section aria-label="HDF5 演示数据导出">
            <strong>HDF5 演示数据</strong>
            <p>审核动作区间、标签来源与各机位当前可验证的 MediaPipe 手部关键点。</p>
            {selectedCaptureGroupIds.length ? (
              <HumanDemonstrationHdf5ExportButton
                projectId={projectId}
                captureGroupIds={selectedCaptureGroupIds}
                buttonText={`导出已选择的 ${selectedCaptureGroupIds.length} 条记录`}
                onSelect={() => setOpen(false)}
              />
            ) : (
              <p role="status">可在列表中勾选已完成标注并通过复核的记录。</p>
            )}
            <p>仅导出勾选记录；下载时仍会检查当前媒体与标注来源是否有效。</p>
          </section>
        </div>
      }
    >
      <Button
        icon={<Download aria-hidden="true" size={16} strokeWidth={1.8} />}
        aria-expanded={open}
      >
        导出数据
      </Button>
    </Popover>
  )
}
