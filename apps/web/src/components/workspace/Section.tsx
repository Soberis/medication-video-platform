import type { ReactNode } from 'react'

export interface SectionProps {
  actions?: ReactNode
  children: ReactNode
  className?: string
  title: ReactNode
  titleId?: string
}

/** A lightweight semantic section for consistent workspace hierarchy. */
export function Section({ actions, children, className = '', title, titleId }: SectionProps) {
  return (
    <section className={`workspace-section ${className}`.trim()} aria-labelledby={titleId}>
      <header className="workspace-section-heading">
        <h2 id={titleId}>{title}</h2>
        {actions ? <div className="workspace-section-actions">{actions}</div> : null}
      </header>
      {children}
    </section>
  )
}
