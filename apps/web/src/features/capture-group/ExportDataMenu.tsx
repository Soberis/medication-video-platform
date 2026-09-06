import { Button, Popover } from 'antd'
import { Download } from 'lucide-react'
import { useState } from 'react'

import { HandLandmarkExportButton } from './HandLandmarkExportButton'
import { HumanAnnotationExportButton } from './HumanAnnotationExportButton'

export function ExportDataMenu({
  projectId,
  captureGroupId,
  humanReady,
}: {
  projectId: string
  captureGroupId?: string
  humanReady: boolean
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
          <p className="form-hint">
            {captureGroupId ? '范围：当前视频（含各机位）' : '范围：整个项目'}
          </p>
          <section aria-label="MediaPipe 导出">
            <strong>MediaPipe 手部关键点</strong>
            <p>各相机的逐帧检测结果（CSV）。</p>
            <HandLandmarkExportButton
              projectId={projectId}
              captureGroupId={captureGroupId}
              onSelect={() => setOpen(false)}
            />
          </section>
          <section aria-label="人工标注导出">
            <strong>人工标注</strong>
            <p>标注基准相机的动作、时间段与备注（CSV）。</p>
            {humanReady ? (
              <HumanAnnotationExportButton
                projectId={projectId}
                captureGroupId={captureGroupId}
                onSelect={() => setOpen(false)}
              />
            ) : (
              <p role="status">尚未确认有可导出的标注。完成标注并通过复核后可导出。</p>
            )}
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
