import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { EmptyState, ErrorState, LoadingState, NoticeState } from '@/components/WorkspaceFeedback'

describe('workspace feedback primitives', () => {
  it('exposes loading and retryable error states with accessible roles', () => {
    const onRetry = vi.fn()
    render(
      <>
        <LoadingState>正在加载视频…</LoadingState>
        <ErrorState code="VIDEO_REQUEST_FAILED" onRetry={onRetry}>
          暂时无法读取视频。
        </ErrorState>
      </>,
    )

    expect(screen.getByRole('status')).toHaveTextContent('正在加载视频…')
    expect(screen.getByRole('status')).toHaveClass('workspace-loading-state')
    expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite')
    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('alert')).toHaveTextContent('暂时无法读取视频。')
    expect(screen.getByRole('alert')).toHaveClass('workspace-error-state')
    // 错误码不渲染到界面，仅做内部追踪
    expect(screen.getByRole('alert').querySelector('code')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }))
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('keeps informational notices separate from loading and error states', () => {
    render(<NoticeState>视频摘要暂不可用，视频列表仍可使用。</NoticeState>)

    expect(screen.getByRole('status')).toHaveTextContent('视频摘要暂不可用，视频列表仍可使用。')
    expect(screen.getByRole('status')).not.toHaveAttribute('aria-busy')
  })

  it('uses the shared empty-state presentation for collection views', () => {
    render(<EmptyState description="当前没有可处理的视频。" />)

    expect(screen.getByText('当前没有可处理的视频。')).toBeInTheDocument()
    expect(
      screen.getByText('当前没有可处理的视频。').closest('.workspace-empty-state'),
    ).toBeInTheDocument()
  })

  it('keeps rich error content in a valid message container', () => {
    render(
      <ErrorState>
        <p>请稍后重试。</p>
      </ErrorState>,
    )

    expect(screen.getByRole('alert').querySelector('.workspace-error-message')).toContainElement(
      screen.getByText('请稍后重试。'),
    )
  })
})
