import { App as AntdApp } from 'antd'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import * as annotationApi from '@/api/annotation'

import {
  approvedGroupTask,
  captureGroupFixture,
  groupId,
  summaryFromGroup,
} from '@/__tests__/capture-group-fixtures'
import { ExportDataMenu } from '@/features/capture-group/ExportDataMenu'

const projectId = '30000000-0000-4000-8000-000000000001'

afterEach(() => vi.restoreAllMocks())

describe('HDF5 demonstration export', () => {
  it('only offers explicitly selected records inside the export dialog', async () => {
    const user = userEvent.setup()
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    render(
      <AntdApp>
        <ExportDataMenu
          projectId={projectId}
          rows={[summaryFromGroup(group)]}
          tasksByGroupId={new Map([[groupId, approvedGroupTask(group)]])}
          tasksReady
        />
      </AntdApp>,
    )

    await user.click(screen.getByRole('button', { name: '导出数据' }))
    const dialog = await screen.findByRole('dialog', { name: '导出数据' })
    expect(within(dialog).getByRole('button', { name: '下载 HDF5（0 条）' })).toBeDisabled()
    await user.click(within(dialog).getByRole('checkbox', { name: /选择导出/ }))
    expect(within(dialog).getByRole('button', { name: '下载 HDF5（1 条）' })).toBeEnabled()
    expect(dialog).toHaveTextContent('不包含 MP4、对象存储地址、患者身份或机器人控制动作')
    expect(screen.getAllByRole('dialog')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: '导出项目全部合格数据' })).not.toBeInTheDocument()
  })

  it('downloads only the selected identifiers and keeps selection available after failure', async () => {
    const user = userEvent.setup()
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const download = vi
      .spyOn(annotationApi, 'downloadHumanDemonstrationHdf5')
      .mockRejectedValueOnce(new Error('unavailable'))
    const createUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:synthetic-download')
    const revokeUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    render(
      <AntdApp>
        <ExportDataMenu
          projectId={projectId}
          rows={[summaryFromGroup(group)]}
          tasksByGroupId={new Map([[groupId, approvedGroupTask(group)]])}
          tasksReady
        />
      </AntdApp>,
    )
    await user.click(screen.getByRole('button', { name: '导出数据' }))
    await user.click(screen.getByRole('checkbox', { name: /选择导出/ }))
    await user.click(screen.getByRole('button', { name: '下载 HDF5（1 条）' }))
    expect(await screen.findByText('HDF5 演示数据暂时无法导出，请稍后重试。')).toBeInTheDocument()
    expect(download).toHaveBeenCalledWith(projectId, [groupId])
    expect(screen.getByRole('checkbox', { name: /选择导出/ })).toBeChecked()
    download.mockResolvedValueOnce({ blob: new Blob(['synthetic']), fileName: 'synthetic.hdf5' })
    await user.click(screen.getByRole('button', { name: '下载 HDF5（1 条）' }))
    expect(await screen.findByText('已开始下载 synthetic.hdf5。')).toBeInTheDocument()
    expect(createUrl).toHaveBeenCalledTimes(1)
    expect(click).toHaveBeenCalledTimes(1)
    expect(revokeUrl).toHaveBeenCalledWith('blob:synthetic-download')
  })
})
