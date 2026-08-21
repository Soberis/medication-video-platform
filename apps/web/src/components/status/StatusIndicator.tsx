import type { ReactNode } from 'react'

export type StatusIndicatorTone = 'destructive' | 'information' | 'neutral' | 'success' | 'warning'

interface StatusIndicatorProps {
  children: ReactNode
  className?: string
  tone?: StatusIndicatorTone
}

/** A quiet dot-and-label status for dense lists and tables. */
export function StatusIndicator({
  children,
  className = '',
  tone = 'neutral',
}: StatusIndicatorProps) {
  return (
    <span className={`status-indicator ${className}`.trim()} data-tone={tone}>
      <span className="status-indicator-dot" aria-hidden="true" />
      {children}
    </span>
  )
}
