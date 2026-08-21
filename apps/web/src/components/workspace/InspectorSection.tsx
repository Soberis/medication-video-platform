import type { ReactNode } from 'react'

export interface InspectorSectionProps {
  actions?: ReactNode
  children: ReactNode
  className?: string
  title: ReactNode
  titleId?: string
}

/** A compact semantic section for future workspace inspector content. */
export function InspectorSection({
  actions,
  children,
  className = '',
  title,
  titleId,
}: InspectorSectionProps) {
  return (
    <section className={`inspector-section ${className}`.trim()} aria-labelledby={titleId}>
      <header className="inspector-section-heading">
        <h2 id={titleId}>{title}</h2>
        {actions ? <div className="inspector-section-actions">{actions}</div> : null}
      </header>
      <div className="inspector-section-content">{children}</div>
    </section>
  )
}
