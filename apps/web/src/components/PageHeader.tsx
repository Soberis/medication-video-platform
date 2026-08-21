import type { ReactNode } from 'react'

import { PageToolbar } from '@/components/PageToolbar'

interface PageHeaderProps {
  actions?: ReactNode
  className?: string
  description?: ReactNode
  eyebrow?: ReactNode
  title: ReactNode
  titleId: string
}

/**
 * A small presentation pattern for the repeated desktop page header. It owns
 * no navigation, state, or permissions; pages still supply their real action.
 */
export function PageHeader({
  actions,
  className = '',
  description,
  eyebrow,
  title,
  titleId,
}: PageHeaderProps) {
  return (
    <section className={`page-heading ${className}`.trim()} aria-labelledby={titleId}>
      <div className="page-heading-copy">
        {eyebrow ? <p className="app-eyebrow">{eyebrow}</p> : null}
        <h1 id={titleId}>{title}</h1>
        {description ? <p className="page-heading-description">{description}</p> : null}
      </div>
      {actions ? <PageToolbar className="page-heading-actions">{actions}</PageToolbar> : null}
    </section>
  )
}
