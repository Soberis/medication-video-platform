import { Tag } from 'antd'
import type { ReactNode } from 'react'

export type CanonicalStatusBadgeTone =
  | 'destructive'
  | 'information'
  | 'neutral'
  | 'success'
  | 'warning'
export type LegacyStatusBadgeTone = 'default' | 'error' | 'info' | 'processing'
export type StatusBadgeTone = CanonicalStatusBadgeTone | LegacyStatusBadgeTone

function normalizeStatusBadgeTone(tone: StatusBadgeTone): CanonicalStatusBadgeTone {
  switch (tone) {
    case 'error':
      return 'destructive'
    case 'info':
    case 'processing':
      return 'information'
    case 'default':
      return 'neutral'
    default:
      return tone
  }
}

export interface StatusBadgeProps {
  children: ReactNode
  className?: string
  tone?: StatusBadgeTone
}

/** A quiet, text-first badge for discrete business facts. */
export function StatusBadge({ children, className = '', tone = 'neutral' }: StatusBadgeProps) {
  const canonicalTone = normalizeStatusBadgeTone(tone)
  const legacyToneClass = tone !== canonicalTone ? `status-badge-${tone}` : ''

  return (
    <Tag
      variant="filled"
      className={`status-badge status-badge-${canonicalTone} ${legacyToneClass} ${className}`.trim()}
      data-tone={canonicalTone}
    >
      {children}
    </Tag>
  )
}
