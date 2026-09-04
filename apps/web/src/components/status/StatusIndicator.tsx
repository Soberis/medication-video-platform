import type { ReactNode } from 'react'

export type StatusIndicatorTone = 'destructive' | 'information' | 'neutral' | 'success' | 'warning'

export interface StatusIndicatorProps {
  ariaLive?: 'assertive' | 'off' | 'polite'
  children: ReactNode
  className?: string
  tone?: StatusIndicatorTone
}

/** A quiet dot-and-label status for dense lists and tables. */
export function StatusIndicator({
  ariaLive,
  children,
  className = '',
  tone = 'neutral',
}: StatusIndicatorProps) {
  return (
    <span aria-live={ariaLive} className={`status-indicator ${className}`.trim()} data-tone={tone}>
      <span className="status-indicator-dot" aria-hidden="true" />
      {children}
    </span>
  )
}
