import { Button } from 'antd'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'

import { usePromptDialog } from '@/components/PromptDialog'
import { renderRoute } from '@/test/render'

describe('PromptDialog', () => {
  it('settles an earlier prompt before replacing it with a newer request', async () => {
    const user = userEvent.setup()
    renderRoute(<PromptHarness />)

    await user.click(screen.getByRole('button', { name: '启动两个输入请求' }))

    const dialog = await screen.findByRole('dialog', { name: '第二个请求' })
    await user.type(within(dialog).getByRole('textbox', { name: '项目名称' }), 'confirmed')
    await user.click(within(dialog).getByRole('button', { name: '确认第二个请求' }))

    expect(await screen.findByText('first:null · second:confirmed')).toBeInTheDocument()
  })
})

function PromptHarness() {
  const prompt = usePromptDialog()
  const [result, setResult] = useState('')

  async function startPrompts() {
    const first = prompt({
      title: '第一个请求',
      description: '会被后续请求替换。',
      confirmText: '确认第一个请求',
    })
    const second = prompt({
      title: '第二个请求',
      description: '这是当前活动请求。',
      confirmText: '确认第二个请求',
      inputLabel: '项目名称',
    })
    const [firstResult, secondResult] = await Promise.all([first, second])
    setResult(`first:${firstResult ?? 'null'} · second:${secondResult ?? 'null'}`)
  }

  return (
    <>
      <Button onClick={() => void startPrompts()}>启动两个输入请求</Button>
      <span>{result}</span>
    </>
  )
}
