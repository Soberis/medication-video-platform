import { Tag } from 'antd'
import type { ReactNode } from 'react'

export type StatusBadgeTone = 'default' | 'error' | 'info' | 'processing' | 'success' | 'warning'

export interface StatusBadgeProps {
  children: ReactNode
  className?: string
  tone?: StatusBadgeTone
}

/** A quiet, text-first badge for discrete business facts. */
export function StatusBadge({ children, className = '', tone = 'default' }: StatusBadgeProps) {
  return (
    <Tag variant="filled" className={`status-badge status-badge-${tone} ${className}`.trim()}>
      {children}
    </Tag>
  )
}
