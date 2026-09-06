import { App as AntdApp, Button } from 'antd'
import { useState } from 'react'

import { AnnotationTaskApiError, downloadHumanAnnotationExport } from '@/api/annotation'

interface HumanAnnotationExportButtonProps {
  projectId: string
  captureGroupId?: string
  onSelect?: () => void
}

export function HumanAnnotationExportButton({
  projectId,
  captureGroupId,
  onSelect,
}: HumanAnnotationExportButtonProps) {
  const { message } = AntdApp.useApp()
  const [loading, setLoading] = useState(false)

  const download = async () => {
    if (loading) return
    setLoading(true)
    try {
      const { blob, fileName } = await downloadHumanAnnotationExport(projectId, captureGroupId)
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
      void message.success(`已开始下载 ${fileName}。`)
    } catch (error) {
      void message.error(
        error instanceof AnnotationTaskApiError
          ? error.message
          : '人工标注暂时无法导出，请稍后重试。',
      )
    } finally {
      setLoading(false)
    }
  }

  return (
    <Button
      size="small"
      loading={loading}
      disabled={loading}
      onClick={() => {
        onSelect?.()
        void download()
      }}
    >
      导出人工标注
    </Button>
  )
}
