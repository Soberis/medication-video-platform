import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { InspectorSection } from '@/components/workspace/InspectorSection'
import { Section } from '@/components/workspace/Section'

describe('workspace layout primitives', () => {
  it('renders a labelled section with optional actions', () => {
    render(
      <Section title="Metadata" titleId="metadata-title" actions={<button>刷新</button>}>
        <p>相机信息</p>
      </Section>,
    )

    const heading = screen.getByRole('heading', { level: 2, name: 'Metadata' })
    expect(heading.closest('section')).toHaveAttribute('aria-labelledby', 'metadata-title')
    expect(screen.getByRole('button', { name: '刷新' })).toBeInTheDocument()
    expect(screen.getByText('相机信息')).toBeInTheDocument()
  })

  it('renders compact inspector content with an optional action', () => {
    render(
      <InspectorSection title="Status" titleId="status-title" actions={<button>查看</button>}>
        <span>已完成</span>
      </InspectorSection>,
    )

    const heading = screen.getByRole('heading', { level: 2, name: 'Status' })
    expect(heading.closest('section')).toHaveAttribute('aria-labelledby', 'status-title')
    expect(screen.getByRole('button', { name: '查看' })).toBeInTheDocument()
    expect(screen.getByText('已完成')).toBeInTheDocument()
  })
})
