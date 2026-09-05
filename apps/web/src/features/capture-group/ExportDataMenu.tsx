import { Button, Modal } from 'antd'
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
    <>
      <Button
        onClick={() => setOpen(true)}
        icon={<Download aria-hidden="true" size={16} strokeWidth={1.8} />}
      >
        导出数据
      </Button>
      <Modal
        open={open}
        onCancel={() => setOpen(false)}
        title="导出数据"
        footer={null}
        destroyOnHidden
      >
        <div className="capture-group-export-options">
          <p className="form-hint">{captureGroupId ? '范围：当前采集记录' : '范围：当前项目'}</p>
          <section aria-label="MediaPipe 导出">
            <strong>MediaPipe 手部关键点</strong>
            <p>各相机的逐帧检测结果（CSV）。</p>
            <HandLandmarkExportButton projectId={projectId} captureGroupId={captureGroupId} />
          </section>
          <section aria-label="人工标注导出">
            <strong>人工标注</strong>
            <p>标注基准相机的动作、时间段与备注（CSV）。</p>
            {humanReady ? (
              <HumanAnnotationExportButton projectId={projectId} captureGroupId={captureGroupId} />
            ) : (
              <p role="status">尚未确认有可导出的标注。完成标注并通过复核后可导出。</p>
            )}
          </section>
        </div>
      </Modal>
    </>
  )
}
