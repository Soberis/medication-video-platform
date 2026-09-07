import { App as AntdApp, Button, Modal } from 'antd'
import { useState } from 'react'

import { AnnotationTaskApiError, downloadHumanDemonstrationHdf5 } from '@/api/annotation'

interface HumanDemonstrationHdf5ExportButtonProps {
  projectId: string
  captureGroupIds?: string[]
  buttonText: string
  onSelect?: () => void
}

export function HumanDemonstrationHdf5ExportButton({
  projectId,
  captureGroupIds,
  buttonText,
  onSelect,
}: HumanDemonstrationHdf5ExportButtonProps) {
  const { message } = AntdApp.useApp()
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)

  async function download() {
    if (loading) return
    setLoading(true)
    try {
      const { blob, fileName } = await downloadHumanDemonstrationHdf5(projectId, captureGroupIds)
      const objectUrl = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = objectUrl
      link.download = fileName
      link.rel = 'noopener'
      link.hidden = true
      document.body.append(link)
      try {
        link.click()
      } finally {
        URL.revokeObjectURL(objectUrl)
        link.remove()
      }
      setOpen(false)
      void message.success(`已开始下载 ${fileName}。`)
    } catch (error) {
      void message.error(
        error instanceof AnnotationTaskApiError
          ? error.message
          : 'HDF5 演示数据暂时无法导出，请稍后重试。',
      )
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <Button
        size="small"
        onClick={() => {
          onSelect?.()
          setOpen(true)
        }}
      >
        {buttonText}
      </Button>
      <Modal
        open={open}
        title="导出 HDF5 演示数据"
        okText="下载 HDF5"
        cancelText="取消"
        confirmLoading={loading}
        okButtonProps={{ 'aria-label': '下载 HDF5 演示数据' }}
        cancelButtonProps={{ disabled: loading }}
        mask={{ closable: !loading }}
        keyboard={!loading}
        onCancel={() => !loading && setOpen(false)}
        onOk={() => void download()}
        destroyOnHidden
      >
        <p>
          范围：{scopeLabel(captureGroupIds)}
          。文件包含已审核动作区间及其标签来源，以及各机位当前可验证的 MediaPipe
          手部关键点和来源信息。
        </p>
        <p>文件不包含 MP4、对象存储地址、患者身份或机器人控制动作；仅限合成数据配置导出。</p>
      </Modal>
    </>
  )
}

function scopeLabel(captureGroupIds?: string[]) {
  if (!captureGroupIds?.length) return '项目内全部已完成标注的采集记录'
  return captureGroupIds.length === 1
    ? '当前采集记录'
    : `已选择的 ${captureGroupIds.length} 条采集记录`
}
