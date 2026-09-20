import { Tag } from 'antd'
import type { ReactNode } from 'react'

export type StatusBadgeTone = 'destructive' | 'information' | 'neutral' | 'success' | 'warning'
export type CanonicalStatusBadgeTone = StatusBadgeTone

export interface StatusBadgeProps {
  children: ReactNode
  className?: string
  tone?: StatusBadgeTone
}

/** A quiet, text-first badge for discrete business facts. */
export function StatusBadge({ children, className = '', tone = 'neutral' }: StatusBadgeProps) {
  return (
    <Tag
      variant="filled"
      className={`status-badge status-badge-${tone} ${className}`.trim()}
      data-tone={tone}
    >
      {children}
    </Tag>
  )
}
