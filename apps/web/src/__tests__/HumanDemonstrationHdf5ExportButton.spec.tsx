import { App as AntdApp } from 'antd'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { ExportDataMenu } from '@/features/capture-group/ExportDataMenu'

const projectId = '30000000-0000-4000-8000-000000000001'
const captureGroupId = '49de2828-0000-4000-8000-000000000001'

describe('HDF5 demonstration export', () => {
  it('only offers explicitly selected records and confirms the scope', async () => {
    const user = userEvent.setup()
    render(
      <AntdApp>
        <ExportDataMenu projectId={projectId} selectedCaptureGroupIds={[captureGroupId]} />
      </AntdApp>,
    )

    await user.click(screen.getByRole('button', { name: '导出数据' }))
    expect(screen.getByRole('button', { name: '导出已选择的 1 条记录' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '导出项目全部合格数据' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '导出已选择的 1 条记录' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('范围：当前采集记录', { exact: false })).toBeInTheDocument()
    expect(dialog).toHaveTextContent('不包含 MP4、对象存储地址、患者身份或机器人控制动作')
  })
})
