import { App as AntdApp } from 'antd'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { CopyableText } from '@/components/data/CopyableText'

afterEach(() => vi.restoreAllMocks())

describe('copyable identifiers', () => {
  it('copies the full identifier', async () => {
    const user = userEvent.setup()
    render(
      <AntdApp>
        <CopyableText value="synthetic-identifier" />
      </AntdApp>,
    )
    await user.click(screen.getByRole('button', { name: '复制 synthetic-identifier' }))
    expect(await navigator.clipboard.readText()).toBe('synthetic-identifier')
    expect(await screen.findByText('已复制到剪贴板。')).toBeInTheDocument()
  })

  it('keeps the identifier selectable when the clipboard is unavailable', async () => {
    const user = userEvent.setup()
    vi.spyOn(navigator, 'clipboard', 'get').mockReturnValue(undefined as unknown as Clipboard)
    render(
      <AntdApp>
        <CopyableText value="synthetic-identifier" />
      </AntdApp>,
    )
    await user.click(screen.getByRole('button', { name: '复制 synthetic-identifier' }))
    expect(await screen.findByText('复制未完成，请手动选择文本。')).toBeInTheDocument()
    expect(screen.getByText('synthetic-identifier')).toBeInTheDocument()
  })

  it('shows a compact version while copying the full value', async () => {
    const user = userEvent.setup()
    const fullValue = 'capture-group-view-set.v1:' + '4b30d8ed'.padEnd(64, 'a')
    render(
      <AntdApp>
        <CopyableText value={fullValue} displayValue="v1:4b30d8ed…" label="复制版本" />
      </AntdApp>,
    )

    expect(screen.getByText('v1:4b30d8ed…')).toBeInTheDocument()
    expect(screen.queryByText(fullValue)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: `复制版本 ${fullValue}` }))
    expect(await navigator.clipboard.readText()).toBe(fullValue)
  })
})
