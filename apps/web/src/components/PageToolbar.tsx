import type { ReactNode } from 'react'

interface PageToolbarProps {
  children: ReactNode
  className?: string
}

/** Groups page-level actions and controls without owning page state. */
export function PageToolbar({ children, className = '' }: PageToolbarProps) {
  return <div className={`page-toolbar ${className}`.trim()}>{children}</div>
}
