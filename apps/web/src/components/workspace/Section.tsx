import { useId, type ReactNode } from 'react'

export interface SectionProps {
  actions?: ReactNode
  children: ReactNode
  className?: string
  id?: string
  title: ReactNode
  titleId?: string
}

/** A lightweight semantic section for consistent workspace hierarchy. */
export function Section({ actions, children, className = '', id, title, titleId }: SectionProps) {
  const generatedTitleId = useId()
  const resolvedTitleId = titleId ?? generatedTitleId

  return (
    <section
      id={id}
      className={`workspace-section ${className}`.trim()}
      aria-labelledby={resolvedTitleId}
    >
      <header className="workspace-section-heading">
        <h2 id={resolvedTitleId}>{title}</h2>
        {actions ? <div className="workspace-section-actions">{actions}</div> : null}
      </header>
      {children}
    </section>
  )
}
