import type { ReactNode } from 'react'

export interface SummaryStripItem {
  detail?: ReactNode
  label: ReactNode
  value: ReactNode
}

export interface SummaryStripProps {
  ariaLabel: string
  footer?: ReactNode
  items: readonly SummaryStripItem[]
}

/**
 * Renders a compact, server-backed summary only when the calling page already
 * has real aggregate facts to show.
 */
export function SummaryStrip({ ariaLabel, footer, items }: SummaryStripProps) {
  return (
    <section className="summary-strip" aria-label={ariaLabel}>
      <dl className="summary-strip-values">
        {items.map((item, index) => (
          <div key={`${String(item.label)}-${index}`}>
            <dt>{item.label}</dt>
            <dd>
              {item.value}
              {item.detail ? <small>{item.detail}</small> : null}
            </dd>
          </div>
        ))}
      </dl>
      {footer ? <div className="summary-strip-footer">{footer}</div> : null}
    </section>
  )
}
