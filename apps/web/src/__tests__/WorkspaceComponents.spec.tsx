import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { MetadataList } from '@/components/data/MetadataList'
import { RefreshButton } from '@/components/RefreshButton'
import { StatusIndicator } from '@/components/status/StatusIndicator'

describe('workspace data and status primitives', () => {
  it('renders metadata as a semantic label/value collection', () => {
    render(
      <MetadataList
        ariaLabel="视频元数据"
        items={[
          { id: 'camera', label: '相机', value: 'CAM-001', secondary: '固定机位' },
          { id: 'duration', label: '时长', value: '120s' },
        ]}
      />,
    )

    expect(screen.getByText('相机').closest('dl')).toHaveAttribute('aria-label', '视频元数据')
    expect(screen.getByText('相机')).toBeInTheDocument()
    expect(screen.getByText('CAM-001')).toBeInTheDocument()
    expect(screen.getByText('固定机位')).toBeInTheDocument()
    expect(screen.getByText('时长')).toBeInTheDocument()
    expect(screen.getByText('120s')).toBeInTheDocument()
  })

  it('renders a quiet dot-and-label status with its semantic tone', () => {
    render(<StatusIndicator tone="information">处理中</StatusIndicator>)

    const indicator = screen.getByText('处理中').closest('.status-indicator')
    expect(indicator).toHaveAttribute('data-tone', 'information')
    expect(indicator?.querySelector('.status-indicator-dot')).toBeInTheDocument()
  })

  it('uses the shared refresh action label and icon', () => {
    render(<RefreshButton label="刷新" />)

    const button = screen.getByRole('button', { name: '刷新' })
    expect(button).toBeInTheDocument()
    expect(button.querySelector('svg')).toBeInTheDocument()
  })
})
