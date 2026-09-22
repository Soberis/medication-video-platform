import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { AppShell, Sidebar } from '@/components/layout/AppShell'

describe('layout primitives', () => {
  it('composes sidebar, workspace content, and an optional inspector', () => {
    render(
      <AppShell
        sidebar={
          <Sidebar brand={<span>产品</span>} collapsed={false} onCollapsedChange={vi.fn()}>
            <nav aria-label="测试导航">导航</nav>
          </Sidebar>
        }
        inspector={<aside aria-label="详情检查器">检查内容</aside>}
      >
        <h1>工作内容</h1>
      </AppShell>,
    )

    expect(screen.getByRole('complementary', { name: '应用侧栏' })).toBeInTheDocument()
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content')
    expect(screen.getByRole('heading', { name: '工作内容' })).toBeInTheDocument()
    expect(screen.getByRole('complementary', { name: '详情检查器' })).toHaveTextContent('检查内容')
  })

  it('exposes the sidebar expansion state through the toggle control', () => {
    render(
      <Sidebar brand={<span>产品</span>} collapsed={false} onCollapsedChange={vi.fn()}>
        <nav aria-label="测试导航">导航</nav>
      </Sidebar>,
    )

    const toggle = screen.getByRole('button', { name: '收起侧栏' })
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(toggle).not.toHaveAttribute('aria-pressed')
  })
})
