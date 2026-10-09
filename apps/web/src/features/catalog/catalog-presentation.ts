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

export interface FormattedAssetIdentity {
  platform: string | null
  shortCode: string
  fullKey: string
}

export function parseAssetIdentity(
  key: string | null | undefined,
  fallbackId?: string,
): FormattedAssetIdentity {
  const raw = key || fallbackId || ''
  if (!raw) return { platform: null, shortCode: '—', fullKey: '' }

  let platform: string | null = null
  let body = raw
  if (raw.startsWith('xhs-')) {
    platform = '小红书'
    body = raw.slice(4)
  } else if (raw.startsWith('douyin-')) {
    platform = '抖音'
    body = raw.slice(7)
  } else if (raw.startsWith('youtube-')) {
    platform = 'YouTube'
    body = raw.slice(8)
  }

  let shortCode = body
  const lastDashIdx = body.lastIndexOf('-')
  if (lastDashIdx > 0 && lastDashIdx < body.length - 1) {
    const prefixId = body.slice(0, lastDashIdx)
    const hashPart = body.slice(lastDashIdx + 1)
    if (hashPart.length > 10) {
      const shortHash = `${hashPart.slice(0, 4)}…${hashPart.slice(-4)}`
      shortCode = `${prefixId} (${shortHash})`
    }
  } else if (body.length > 20) {
    shortCode = `${body.slice(0, 8)}…${body.slice(-4)}`
  }

  return {
    platform,
    shortCode,
    fullKey: raw,
  }
}

