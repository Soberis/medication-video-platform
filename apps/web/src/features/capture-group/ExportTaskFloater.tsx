import { Button, Modal, Progress } from 'antd'
import { AlertCircle, CheckCircle2, Loader2, X } from 'lucide-react'
import { useEffect } from 'react'

import type { Hdf5ExportTaskResponse } from '@/api/annotation'

interface ExportTaskFloaterProps {
  job: Hdf5ExportTaskResponse | null
  onDismiss: () => void
  onCancel?: () => void
  onManualDownload?: () => void
}

function formatBytes(bytes?: number): string {
  if (!bytes || bytes <= 0) return ''
  const mb = bytes / (1024 * 1024)
  if (mb >= 1024) return `${(mb / 1024).toFixed(2)} GB`
  return `${mb.toFixed(1)} MB`
}

export function ExportTaskFloater({
  job,
  onDismiss,
  onCancel,
  onManualDownload,
}: ExportTaskFloaterProps) {
  // 完成后 6 秒自动隐退，不打扰用户
  useEffect(() => {
    if (job?.status === 'COMPLETED') {
      const timer = window.setTimeout(() => {
        onDismiss()
      }, 6000)
      return () => window.clearTimeout(timer)
    }
  }, [job?.status, onDismiss])

  if (!job) return null

  const isCompleted = job.status === 'COMPLETED'
  const isFailed = job.status === 'FAILED'
  const isRunning = job.status === 'PROCESSING' || job.status === 'PENDING'
  const isSealing =
    isRunning && (job.percent >= 100 || (job.total > 0 && job.current >= job.total))

  const handleCancelClick = () => {
    Modal.confirm({
      title: '确认取消导出任务？',
      content: '取消后将中止正在生成的 HDF5 数据集打包流程。',
      okText: '确认取消',
      okType: 'danger',
      cancelText: '继续导出',
      onOk: () => {
        if (onCancel) onCancel()
      },
    })
  }

  return (
    <div
      role="region"
      aria-label="后台导出任务卡片"
      style={{
        position: 'fixed',
        bottom: 24,
        right: 24,
        zIndex: 1050,
        width: 340,
        background: '#ffffff',
        border: '1px solid #cbd5e1',
        borderRadius: 10,
        boxShadow: '0 8px 24px -2px rgba(0, 0, 0, 0.12)',
        padding: '12px 16px',
        fontFamily: 'inherit',
      }}
    >
      {/* 1. 运行中状态 */}
      {isRunning && (
        <>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              marginBottom: 8,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <Loader2
                style={{
                  color: '#1677ff',
                  animation: 'spin 1.2s linear infinite',
                }}
                size={16}
              />
              <span style={{ fontSize: 13, fontWeight: 600, color: '#0f172a' }}>
                正在后台导出 HDF5 数据集
              </span>
            </div>
            <Button
              type="text"
              size="small"
              icon={<X size={14} />}
              onClick={onDismiss}
              aria-label="收起浮窗"
              title="收起浮窗（后台继续运行，可点击顶部按钮重新打开）"
              style={{ color: '#64748b' }}
            />
          </div>

          <Progress
            percent={job.percent}
            status="active"
            strokeColor="#1677ff"
            size="small"
            showInfo={false}
          />

          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              fontSize: 12,
              color: '#64748b',
              marginTop: 6,
            }}
          >
            <span>
              已打包 {job.current} / {job.total || job.current} 条
            </span>
            <span style={{ fontWeight: 500, color: '#1e293b' }}>{job.percent}%</span>
          </div>

          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              fontSize: 12,
              color: '#94a3b8',
              marginTop: 8,
              paddingTop: 6,
              borderTop: '1px dashed #f1f5f9',
            }}
          >
            <span>
              {isSealing ? (
                <span style={{ color: '#1677ff' }}>封包写入磁盘中…</span>
              ) : (
                '后台运行中，可随时收起'
              )}
            </span>
            {onCancel && (
              <Button
                type="link"
                size="small"
                danger
                onClick={handleCancelClick}
                style={{ padding: 0, fontSize: 12, height: 'auto' }}
              >
                取消任务
              </Button>
            )}
          </div>
        </>
      )}

      {/* 2. 完成状态（大道至简：轻量通知，两行搞定） */}
      {isCompleted && (
        <div>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <CheckCircle2 size={16} style={{ color: '#16a34a', flexShrink: 0 }} />
              <span style={{ fontSize: 13, fontWeight: 600, color: '#15803d' }}>
                导出完成，已自动开始下载
              </span>
            </div>
            <Button
              type="text"
              size="small"
              icon={<X size={14} />}
              onClick={onDismiss}
              aria-label="关闭"
              style={{ color: '#64748b' }}
            />
          </div>

          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              fontSize: 12,
              color: '#64748b',
              marginTop: 8,
              paddingTop: 6,
              borderTop: '1px solid #f1f5f9',
            }}
          >
            <span
              style={{
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
                maxWidth: 220,
              }}
              title={job.file_name}
            >
              {job.file_name || 'human_demonstration.hdf5'}
              {job.file_size_bytes ? ` (${formatBytes(job.file_size_bytes)})` : ''}
            </span>
            {onManualDownload && (
              <Button
                type="link"
                size="small"
                onClick={onManualDownload}
                style={{ padding: 0, fontSize: 12, height: 'auto', marginLeft: 8 }}
              >
                重新下载
              </Button>
            )}
          </div>
        </div>
      )}

      {/* 3. 失败状态 */}
      {isFailed && (
        <div>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              marginBottom: 6,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <AlertCircle size={16} style={{ color: '#dc2626' }} />
              <span style={{ fontSize: 13, fontWeight: 600, color: '#b91c1c' }}>
                导出失败
              </span>
            </div>
            <Button
              type="text"
              size="small"
              icon={<X size={14} />}
              onClick={onDismiss}
              aria-label="关闭"
              style={{ color: '#64748b' }}
            />
          </div>
          <div style={{ fontSize: 12, color: '#dc2626' }}>
            {job.error_message || '数据导出遇到异常，请稍后重试。'}
          </div>
        </div>
      )}
    </div>
  )
}
