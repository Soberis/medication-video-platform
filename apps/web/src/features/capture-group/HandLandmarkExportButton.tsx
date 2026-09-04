import { App as AntdApp, Button, Modal } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'

import {
  downloadHandLandmarkExport,
  getHandLandmarkExportPreview,
  HandLandmarkExportApiError,
  type HandLandmarkExportPreview,
} from '@/api/hand-landmarks'
import { StatusIndicator } from '@/components/status/StatusIndicator'

interface HandLandmarkExportButtonProps {
  projectId: string
  captureGroupId?: string
}

export function HandLandmarkExportButton({
  projectId,
  captureGroupId,
}: HandLandmarkExportButtonProps) {
  const { message } = AntdApp.useApp()
  const [preview, setPreview] = useState<HandLandmarkExportPreview | null>(null)
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
      const result = await getHandLandmarkExportPreview(projectId, captureGroupId, request.signal)
      if (!request.signal.aborted) setPreview(result)
    } catch (error) {
      if (!request.signal.aborted) {
        void message.error(
          error instanceof HandLandmarkExportApiError
            ? error.message
            : '暂时无法读取 MediaPipe 手部检测关键点，请稍后重试。',
        )
      }
    } finally {
      if (requestRef.current === request) {
        requestRef.current = null
        setPreviewLoading(false)
      }
    }
  }, [captureGroupId, downloadLoading, message, previewLoading, projectId])

  const download = useCallback(async () => {
    if (!preview?.sample_count || downloadLoading) return
    const request = new AbortController()
    requestRef.current = request
    setDownloadLoading(true)
    try {
      const blob = await downloadHandLandmarkExport(
        projectId,
        preview.generated_at,
        captureGroupId,
        request.signal,
      )
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
          error instanceof HandLandmarkExportApiError
            ? error.message
            : 'MediaPipe 手部检测关键点暂时无法导出，请稍后重试。',
        )
      }
    } finally {
      if (requestRef.current === request) requestRef.current = null
      setDownloadLoading(false)
    }
  }, [captureGroupId, downloadLoading, message, preview, projectId])

  return (
    <>
      <Button
        size="small"
        loading={previewLoading}
        disabled={downloadLoading}
        onClick={() => void openPreview()}
      >
        {captureGroupId ? '导出手部检测证据' : '导出项目手部关键点'}
      </Button>
      <Modal
        open={Boolean(preview)}
        title="手部关键点导出预览"
        okText="导出 CSV"
        cancelText="关闭"
        confirmLoading={downloadLoading}
        okButtonProps={{ disabled: !preview?.sample_count, 'aria-label': '导出 CSV' }}
        cancelButtonProps={{ disabled: downloadLoading, 'aria-label': '关闭手部关键点导出预览' }}
        mask={{ closable: !downloadLoading }}
        keyboard={!downloadLoading}
        onCancel={() => !downloadLoading && setPreview(null)}
        onOk={() => void download()}
        destroyOnHidden
      >
        {preview ? (
          <HandLandmarkExportPreviewContent
            preview={preview}
            eventScope={Boolean(captureGroupId)}
          />
        ) : null}
      </Modal>
    </>
  )
}

function HandLandmarkExportPreviewContent({
  preview,
  eventScope,
}: {
  preview: HandLandmarkExportPreview
  eventScope: boolean
}) {
  const isPartial = hasNonExportableViews(preview)
  return (
    <div className="hand-landmark-export-content">
      <div className="hand-landmark-export-readiness" role="status" aria-live="polite">
        <StatusIndicator tone={preview.sample_count && !isPartial ? 'success' : 'warning'}>
          {preview.sample_count ? (isPartial ? '部分可导出' : '可以导出') : '暂不可导出'}
        </StatusIndicator>
        <p>
          {preview.sample_count
            ? isPartial
              ? `当前已有可导出的 MediaPipe 手部关键点样本；${partialExportMessage(preview)}`
              : '当前已有可导出的 MediaPipe 手部关键点样本。'
            : emptyMessage(preview)}
        </p>
      </div>
      <section className="hand-landmark-export-facts" aria-label="手部检测关键点导出摘要">
        <dl className="hand-landmark-export-facts-list">
          <Fact label="导出范围" value={eventScope ? '当前配药事件' : '整个项目'} />
          <Fact label="检测视图" value={`${formatCount(preview.hand_detection_view_count)} 个`} />
          <Fact label="完成视图" value={`${formatCount(preview.completed_view_count)} 个`} />
          <Fact label="检测到手部的视图" value={`${formatCount(preview.views_with_hand)} 个`} />
          <Fact label="可导出样本" value={formatCount(preview.sample_count)} />
          <Fact label="文件名" value={<code>{preview.file_name}</code>} />
        </dl>
      </section>
    </div>
  )
}

function hasNonExportableViews(preview: HandLandmarkExportPreview) {
  return (
    preview.completed_view_count < preview.hand_detection_view_count ||
    preview.failed_view_count > 0 ||
    preview.no_hand_view_count > 0
  )
}

function partialExportMessage(preview: HandLandmarkExportPreview) {
  const reasons: string[] = []
  if (preview.no_job_view_count)
    reasons.push(`${formatCount(preview.no_job_view_count)} 个视图尚未开始检测`)
  if (preview.queued_view_count)
    reasons.push(`${formatCount(preview.queued_view_count)} 个视图已排队`)
  if (preview.running_view_count)
    reasons.push(`${formatCount(preview.running_view_count)} 个视图仍在检测`)
  if (preview.failed_view_count)
    reasons.push(`${formatCount(preview.failed_view_count)} 个视图检测失败`)
  if (preview.no_hand_view_count)
    reasons.push(`${formatCount(preview.no_hand_view_count)} 个视图未检测到手部`)
  const detail = reasons.join('；') || '仍有视图未完成检测'
  return `${detail}；本次仅导出已验证样本。`
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

function emptyMessage(preview: HandLandmarkExportPreview) {
  if (preview.no_job_view_count)
    return `${formatCount(preview.no_job_view_count)} 个视图尚未开始手部检测。`
  if (preview.queued_view_count)
    return `${formatCount(preview.queued_view_count)} 个视图的手部检测已排队。`
  if (preview.running_view_count)
    return `${formatCount(preview.running_view_count)} 个视图的手部检测仍在运行。`
  if (preview.failed_view_count)
    return `${formatCount(preview.failed_view_count)} 个视图的手部检测失败。`
  if (preview.no_hand_view_count)
    return `${formatCount(preview.no_hand_view_count)} 个已完成视图未检测到手部关键点。`
  return '当前没有可导出的手部关键点样本。'
}
