import React, { useState } from 'react'
import { Button, Progress } from 'antd'
import {
  AlertCircle,
  CheckCircle2,
  Download,
  Loader2,
  Maximize2,
  Minimize2,
  X,
} from 'lucide-react'

import type { Hdf5ExportTaskResponse } from '@/api/annotation'

interface ExportTaskFloaterProps {
  job: Hdf5ExportTaskResponse | null
  onDismiss: () => void
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
  onManualDownload,
}: ExportTaskFloaterProps) {
  const [minimized, setMinimized] = useState(false)

  if (!job) return null

  const isCompleted = job.status === 'COMPLETED'
  const isFailed = job.status === 'FAILED'
  const isRunning = job.status === 'PROCESSING' || job.status === 'PENDING'

  if (minimized) {
    return (
      <div
        role="region"
        aria-label="后台导出任务小部件"
        style={{
          position: 'fixed',
          bottom: 24,
          right: 24,
          zIndex: 1050,
          background: '#ffffff',
          border: '1px solid #e2e8f0',
          borderRadius: 24,
          boxShadow: '0 8px 20px rgba(0, 0, 0, 0.12)',
          padding: '8px 16px',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          cursor: 'pointer',
        }}
        onClick={() => setMinimized(false)}
      >
        {isRunning ? (
          <Loader2
            className="animate-spin text-blue-600"
            size={18}
            style={{ color: '#1677ff' }}
          />
        ) : isCompleted ? (
          <CheckCircle2 size={18} style={{ color: '#16a34a' }} />
        ) : (
          <AlertCircle size={18} style={{ color: '#dc2626' }} />
        )}
        <span style={{ fontSize: 13, fontWeight: 500, color: '#1e293b' }}>
          {isRunning
            ? `HDF5 导出中 (${job.percent}%)`
            : isCompleted
              ? 'HDF5 导出完成'
              : 'HDF5 导出失败'}
        </span>
        <Maximize2 size={14} style={{ color: '#64748b' }} />
      </div>
    )
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
        width: 360,
        background: '#ffffff',
        border: '1px solid #cbd5e1',
        borderRadius: 12,
        boxShadow: '0 12px 28px -4px rgba(0, 0, 0, 0.15)',
        overflow: 'hidden',
        fontFamily: 'inherit',
      }}
    >
      {/* Header */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '12px 16px',
          background: isCompleted ? '#f0fdf4' : isFailed ? '#fef2f2' : '#f8fafc',
          borderBottom: '1px solid #e2e8f0',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {isRunning ? (
            <Loader2
              style={{
                color: '#1677ff',
                animation: 'spin 1.2s linear infinite',
              }}
              size={18}
            />
          ) : isCompleted ? (
            <CheckCircle2 size={18} style={{ color: '#16a34a' }} />
          ) : (
            <AlertCircle size={18} style={{ color: '#dc2626' }} />
          )}
          <span
            style={{
              fontSize: 14,
              fontWeight: 600,
              color: isCompleted ? '#15803d' : isFailed ? '#b91c1c' : '#0f172a',
            }}
          >
            {isCompleted
              ? 'HDF5 数据集导出完成'
              : isFailed
                ? 'HDF5 数据集导出失败'
                : '正在后台导出 HDF5 数据集'}
          </span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <Button
            type="text"
            size="small"
            icon={<Minimize2 size={14} />}
            onClick={() => setMinimized(true)}
            aria-label="最小化导出进度"
          />
          <Button
            type="text"
            size="small"
            icon={<X size={14} />}
            onClick={onDismiss}
            aria-label="关闭导出卡片"
          />
        </div>
      </div>

      {/* Body */}
      <div style={{ padding: '16px' }}>
        {isRunning && (
          <>
            <Progress
              percent={job.percent}
              status="active"
              strokeColor="#1677ff"
              showInfo
            />
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                fontSize: 12,
                color: '#64748b',
                marginTop: 8,
              }}
            >
              <span>
                已打包 {job.current} / {job.total || job.current} 条
              </span>
              <span>{job.percent}%</span>
            </div>
            {job.current_label && (
              <div
                style={{
                  fontSize: 12,
                  color: '#475569',
                  marginTop: 6,
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                }}
                title={job.current_label}
              >
                当前: {job.current_label}
              </div>
            )}
            <div
              style={{
                fontSize: 12,
                color: '#94a3b8',
                marginTop: 10,
                lineHeight: 1.4,
              }}
            >
              提示：后台处理中，您可以继续正常浏览视频或执行其他操作。
            </div>
          </>
        )}

        {isCompleted && (
          <div>
            <div style={{ fontSize: 13, color: '#334155', marginBottom: 12 }}>
              已就绪：
              <span style={{ fontWeight: 500 }}>
                {job.file_name || 'human_demonstration_hdf5_v2.hdf5'}
              </span>
              {job.file_size_bytes ? ` (${formatBytes(job.file_size_bytes)})` : ''}
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              {onManualDownload && (
                <Button
                  type="primary"
                  icon={<Download size={14} />}
                  onClick={onManualDownload}
                  style={{ flex: 1 }}
                >
                  再次下载
                </Button>
              )}
              <Button onClick={onDismiss} style={{ flex: 1 }}>
                关闭
              </Button>
            </div>
          </div>
        )}

        {isFailed && (
          <div>
            <div style={{ fontSize: 13, color: '#dc2626', marginBottom: 12 }}>
              {job.error_message || '数据导出遇到异常，请稍后重试。'}
            </div>
            <Button onClick={onDismiss} block>
              关闭
            </Button>
          </div>
        )}
      </div>
    </div>
  )
}
