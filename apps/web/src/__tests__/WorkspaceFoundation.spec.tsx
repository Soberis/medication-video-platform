import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { SummaryStrip } from '@/components/SummaryStrip'
import { PageHeader } from '@/components/PageHeader'
import { InspectorSection } from '@/components/workspace/InspectorSection'
import { Section } from '@/components/workspace/Section'

describe('workspace layout primitives', () => {
  it('generates a heading relationship for page headers when no id is supplied', () => {
    render(<PageHeader title="视频管理" />)

    const heading = screen.getByRole('heading', { level: 1, name: '视频管理' })
    expect(heading.id).not.toBe('')
    expect(heading.closest('header')).toHaveAttribute('aria-labelledby', heading.id)
  })

  it('renders a labelled section with optional actions', () => {
    render(
      <Section
        id="metadata-section"
        title="Metadata"
        titleId="metadata-title"
        actions={<button>刷新</button>}
      >
        <p>相机信息</p>
      </Section>,
    )

    const heading = screen.getByRole('heading', { level: 2, name: 'Metadata' })
    expect(heading.closest('section')).toHaveAttribute('aria-labelledby', 'metadata-title')
    expect(heading.closest('section')).toHaveAttribute('id', 'metadata-section')
    expect(screen.getByRole('button', { name: '刷新' })).toBeInTheDocument()
    expect(screen.getByText('相机信息')).toBeInTheDocument()
  })

  it('generates a heading relationship when no title id is supplied', () => {
    render(
      <Section title="自动命名">
        <p>内容</p>
      </Section>,
    )

    const heading = screen.getByRole('heading', { level: 2, name: '自动命名' })
    expect(heading.id).not.toBe('')
    expect(heading.closest('section')).toHaveAttribute('aria-labelledby', heading.id)
  })

  it('renders compact inspector content with an optional action', () => {
    render(
      <InspectorSection title="Status" titleId="status-title" actions={<button>查看</button>}>
        <span>已完成</span>
      </InspectorSection>,
    )

    const heading = screen.getByRole('heading', { level: 2, name: 'Status' })
    expect(heading.closest('section')).toHaveAttribute('aria-labelledby', 'status-title')
    expect(heading.closest('section')).toHaveClass('workspace-section', 'inspector-section')
    expect(screen.getByRole('button', { name: '查看' })).toBeInTheDocument()
    expect(screen.getByText('已完成')).toBeInTheDocument()
  })

  it('keeps summary values grouped as a compact semantic collection', () => {
    render(
      <SummaryStrip
        ariaLabel="项目视频摘要"
        items={[
          { label: '视频总数', value: 120 },
          { label: '待处理', value: 6, detail: '标注 3 · 复核 2 · 清洗 1' },
        ]}
        footer={<span>总时长 2时10分</span>}
      />,
    )

    const summary = screen.getByRole('region', { name: '项目视频摘要' })
    expect(summary.querySelector('dl')).toBeInTheDocument()
    expect(screen.getByText('120').closest('dd')).toBeInTheDocument()
    expect(screen.getByText('标注 3 · 复核 2 · 清洗 1').closest('dd')).toBeInTheDocument()
    expect(screen.getByText('总时长 2时10分')).toBeInTheDocument()
  })
})
