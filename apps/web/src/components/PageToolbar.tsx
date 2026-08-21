import type { ReactNode } from 'react'

export interface PageToolbarProps {
  ariaLabel?: string
  children: ReactNode
  className?: string
}

/** Groups page-level actions and controls without owning page state. */
export function PageToolbar({ ariaLabel, children, className = '' }: PageToolbarProps) {
  return (
    <div
      className={`page-toolbar ${className}`.trim()}
      role={ariaLabel ? 'toolbar' : undefined}
      aria-label={ariaLabel}
    >
      {children}
    </div>
  )
}
