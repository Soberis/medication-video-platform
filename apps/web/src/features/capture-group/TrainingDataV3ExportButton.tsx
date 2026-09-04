import { App as AntdApp, Button, Modal } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'

import {
  downloadTrainingDataV3,
  getTrainingDataV3ExportPreview,
  TrainingDataV3ExportApiError,
  type TrainingDataV3ExportPreview,
} from '@/api/training-data'
import { StatusIndicator } from '@/components/status/StatusIndicator'

interface TrainingDataV3ExportButtonProps {
  projectId: string
}

export function TrainingDataV3ExportButton({ projectId }: TrainingDataV3ExportButtonProps) {
  const { message } = AntdApp.useApp()
  const [preview, setPreview] = useState<TrainingDataV3ExportPreview | null>(null)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [downloadLoading, setDownloadLoading] = useState(false)
  const requestRef = useRef<AbortController | null>(null)

  useEffect(() => () => requestRef.current?.abort(), [])

  const openPreview = useCallback(async () => {
    if (previewLoading || downloadLoading) return
    requestRef.current?.abort()
    const request = new AbortController()
    requestRef.current = request
    setPreviewLoading(true)
    try {
      const result = await getTrainingDataV3ExportPreview(projectId, request.signal)
      if (!request.signal.aborted) setPreview(result)
    } catch (error) {
      if (!request.signal.aborted) {
        void message.error(
          error instanceof TrainingDataV3ExportApiError
            ? error.message
            : '暂时无法读取 training-data.v3 导出资格，请稍后重试。',
        )
      }
    } finally {
      if (requestRef.current === request) {
        requestRef.current = null
        setPreviewLoading(false)
      }
    }
  }, [downloadLoading, message, previewLoading, projectId])

  const download = useCallback(async () => {
    if (!preview?.event_count || downloadLoading) return
    const request = new AbortController()
    requestRef.current = request
    setDownloadLoading(true)
    try {
      const blob = await downloadTrainingDataV3(projectId, request.signal)
      const objectUrl = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = objectUrl
      link.download = preview.file_name
      link.rel = 'noopener'
      link.hidden = true
      document.body.append(link)
      try {
        link.click()
      } finally {
        URL.revokeObjectURL(objectUrl)
        link.remove()
      }
      setPreview(null)
      void message.success(`已开始下载 ${preview.file_name}。`)
    } catch (error) {
      if (!request.signal.aborted) {
        void message.error(
          error instanceof TrainingDataV3ExportApiError
            ? error.message
            : 'training-data.v3 导出未完成，请稍后重试。',
        )
      }
    } finally {
      if (requestRef.current === request) requestRef.current = null
      setDownloadLoading(false)
    }
  }, [downloadLoading, message, preview, projectId])

  return (
    <>
      <Button
        size="small"
        loading={previewLoading}
        disabled={downloadLoading}
        onClick={() => void openPreview()}
      >
        导出训练资格数据
      </Button>
      <Modal
        open={Boolean(preview)}
        title="training-data.v3 导出预览"
        okText="导出 JSON"
        cancelText="关闭"
        confirmLoading={downloadLoading}
        okButtonProps={{ disabled: !preview?.event_count, 'aria-label': '导出 JSON' }}
        cancelButtonProps={{ disabled: downloadLoading, 'aria-label': '关闭训练资格数据导出预览' }}
        mask={{ closable: !downloadLoading }}
        keyboard={!downloadLoading}
        onCancel={() => !downloadLoading && setPreview(null)}
        onOk={() => void download()}
        destroyOnHidden
      >
        {preview ? <TrainingDataV3ExportPreviewContent preview={preview} /> : null}
      </Modal>
    </>
  )
}

function TrainingDataV3ExportPreviewContent({ preview }: { preview: TrainingDataV3ExportPreview }) {
  const hasEvents = preview.event_count > 0
  return (
    <div className="training-data-v3-export-content">
      <div className="training-data-v3-export-readiness" role="status" aria-live="polite">
        <StatusIndicator tone={hasEvents ? 'success' : 'warning'}>
          {hasEvents ? '可以导出' : '暂无可导出事件'}
        </StatusIndicator>
        <p>
          {hasEvents
            ? '仅导出通过事件级训练资格检查的 CaptureGroup；这是来源 manifest，不是手部关键点样本，也不包含 MP4。'
            : '当前没有通过事件级训练资格检查的 CaptureGroup。'}
        </p>
      </div>
      <section className="training-data-v3-export-facts" aria-label="训练资格数据导出摘要">
        <dl className="training-data-v3-export-facts-list">
          <Fact label="符合资格事件" value={formatCount(preview.eligible_group_count)} />
          <Fact label="事件行数" value={formatCount(preview.event_count)} />
          <Fact label="拆分单元" value={preview.split_unit} />
          <Fact label="包含 MP4" value={preview.includes_mp4 ? '是' : '否'} />
          <Fact label="文件名" value={<code>{preview.file_name}</code>} />
        </dl>
      </section>
    </div>
  )
}

function Fact({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

function formatCount(value: number) {
  return new Intl.NumberFormat('zh-CN').format(value)
}
