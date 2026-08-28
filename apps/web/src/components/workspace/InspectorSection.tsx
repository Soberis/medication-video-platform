import type { ReactNode } from 'react'

import { Section } from '@/components/workspace/Section'

export interface InspectorSectionProps {
  actions?: ReactNode
  children: ReactNode
  className?: string
  id?: string
  tabIndex?: number
  title: ReactNode
  titleId?: string
}

/** A compact semantic section for future workspace inspector content. */
export function InspectorSection({
  actions,
  children,
  className = '',
  id,
  tabIndex,
  title,
  titleId,
}: InspectorSectionProps) {
  return (
    <Section
      actions={actions}
      className={`inspector-section ${className}`.trim()}
      id={id}
      tabIndex={tabIndex}
      title={title}
      titleId={titleId}
    >
      <div className="inspector-section-content">{children}</div>
    </Section>
  )
}
