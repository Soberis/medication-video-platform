import { PropertyRow, type PropertyRowProps } from '@/components/data/PropertyRow'

export interface MetadataListItem extends PropertyRowProps {
  id: string
}

interface MetadataListProps {
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
