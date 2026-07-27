import type {
  CameraStreamStatus,
  CaptureQualityStatus,
  CaptureSessionAssemblyStatus,
  ClipAssetStatus,
  DeclaredSyncStatus,
} from '@/api/catalog'

export const assemblyStatusLabels: Record<CaptureSessionAssemblyStatus, string> = {
  OPEN: '待组装',
  ASSEMBLING: '组装中',
  COMPLETE: '流已齐全',
  INCOMPLETE: '流不完整',
  CLOSED: '已关闭',
}

export const qualityStatusLabels: Record<CaptureQualityStatus, string> = {
  PENDING: '待检测',
  PASS: '通过',
  WARNING: '需关注',
  REJECT: '不通过',
}

export const streamStatusLabels: Record<CameraStreamStatus, string> = {
  BOUND: '已绑定，待入库',
  INGESTED: '已入库',
  INGEST_FAILED: '入库失败',
  UNAVAILABLE: '视频已删除，不可用',
}

export const declaredSyncStatusLabels: Record<DeclaredSyncStatus, string> = {
  UNKNOWN: '边端未判定',
  PASS: '边端声明正常',
  WARNING: '边端声明需关注',
  REJECT: '边端声明异常',
}

export const assetStatusLabels: Record<ClipAssetStatus, string> = {
  UPLOADED: '上传完成，待检测',
  INGESTING: '媒体检测中',
  INGESTED: '媒体检测完成',
  INGEST_FAILED: '媒体检测失败',
}

export function statusTone(status: string): 'success' | 'warning' | 'error' | 'default' | 'processing' {
  if (status === 'PASS' || status === 'COMPLETE' || status === 'INGESTED') {
    return 'success'
  }
  if (status === 'WARNING' || status === 'ASSEMBLING' || status === 'INGESTING') {
    return 'warning'
  }
  if (status === 'REJECT' || status === 'INCOMPLETE' || status === 'INGEST_FAILED' || status === 'UNAVAILABLE') {
    return 'error'
  }
  return status === 'BOUND' || status === 'UPLOADED' ? 'processing' : 'default'
}

export function statusLabel(labels: Record<string, string>, status: string) {
  return labels[status] ?? status
}

export function formatDateTime(value: string | null | undefined) {
  if (!value) return '未提供'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'medium',
  }).format(date)
}

export function formatBytes(value: number) {
  if (value < 1024) return `${value} B`
  const units = ['KiB', 'MiB', 'GiB', 'TiB']
  let amount = value / 1024
  let unit = units[0]
  for (let index = 1; index < units.length && amount >= 1024; index += 1) {
    amount /= 1024
    unit = units[index]
  }
  return `${new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(amount)} ${unit}`
}

export function shortId(value: string) {
  return `${value.slice(0, 8)}…${value.slice(-4)}`
}
