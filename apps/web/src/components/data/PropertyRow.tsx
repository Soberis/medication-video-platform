import type { ReactNode } from 'react'

export interface PropertyRowProps {
  className?: string
  label: ReactNode
  secondary?: ReactNode
  value: ReactNode
}

/** A semantic label/value row for compact workspace metadata collections. */
export function PropertyRow({ className = '', label, secondary, value }: PropertyRowProps) {
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
