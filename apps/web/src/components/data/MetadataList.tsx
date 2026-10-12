import type { ReactNode } from 'react'

export interface PropertyRowProps {
  className?: string
  label: ReactNode
  secondary?: ReactNode
  value: ReactNode
}

/** A semantic label/value row for compact workspace metadata collections. */
function PropertyRow({ className = '', label, secondary, value }: PropertyRowProps) {
  return (
    <div className={`property-row ${className}`.trim()}>
      <dt>{label}</dt>
      <dd>
        {value}
        {secondary ? <small className="property-row-secondary">{secondary}</small> : null}
      </dd>
    </div>
  )
}

export interface MetadataListItem extends PropertyRowProps {
  id: string
}

export interface MetadataListProps {
  ariaLabel?: string
  className?: string
  items: readonly MetadataListItem[]
}

/** Renders a small, semantic metadata collection without page-specific markup. */
export function MetadataList({ ariaLabel, className = '', items }: MetadataListProps) {
  return (
    <dl className={`metadata-list ${className}`.trim()} aria-label={ariaLabel}>
      {items.map(({ id, ...item }) => (
        <PropertyRow key={id} {...item} />
      ))}
    </dl>
  )
}
