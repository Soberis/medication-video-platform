import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  projectId,
  groupId,
  captureGroupFixture,
  groupTask,
  qualityFixture,
  stubGroupDetail,
} from './capture-group-fixtures'
import { renderRoute } from '@/test/render'
import CaptureGroupDetailView from '@/views/CaptureGroupDetailView'

afterEach(() => vi.unstubAllGlobals())

describe('CaptureGroup detail views', () => {
  it('keeps the default detail path focused on receipt, media and machine evidence', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const fetchMock = stubGroupDetail(group, {
      task: null,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    expect(await screen.findByRole('heading', { level: 1, name: 'capture-1' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '数据组' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: '机位 1' })).toBeInTheDocument()
    await user.click(await screen.findByText('查看机位技术与辅助证据'))
    expect(await screen.findByText('手部检测：已完成 · 未检测到手部')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '人工标签任务' })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith('/quality-review'))).toBe(
      false,
    )
  })

  it('renders an existing human-label task without making it the default machine path', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    stubGroupDetail(group, {
      task: groupTask(groupId, true),
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'HAND_DETECTED',
    })

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
    })

    expect(
      await screen.findByRole('heading', { level: 2, name: '配药动作标注' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: '机位 1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '提交修订' })).toBeInTheDocument()
  })

  it('keeps a missing machine result explicit instead of presenting human quality', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1],
      receipt: 'INCOMPLETE',
    })
    stubGroupDetail(group, {
      task: null,
      quality: qualityFixture(group, 'UNREVIEWED'),
      candidateStatus: 'FAILED',
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    const selector = await screen.findByRole('group', { name: '数据组机位选择' })
    expect(within(selector).getByRole('button', { name: /机位 2/ })).toBeDisabled()
    await user.click(await screen.findByText('查看机位技术与辅助证据'))
    expect(await screen.findByText('手部检测：失败')).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: '事件质量结论' })).not.toBeInTheDocument()
  })

  it('requires explicit confirmation before finalizing a receiving group', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1],
      receipt: 'RECEIVING',
    })
    const updatedGroup = { ...group, receipt_state: 'INCOMPLETE' as const }
    const fetchMock = stubGroupDetail(group, {
      task: null,
      groupResponses: [group, updatedGroup],
      quality: qualityFixture(group, 'UNREVIEWED'),
      candidateStatus: 'UNKNOWN',
      receiptFinalizeResponse: updatedGroup,
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    await user.click(await screen.findByRole('button', { name: '结束接收' }))
    expect(screen.getByRole('dialog')).toHaveTextContent('结束本次接收？')
    expect(screen.getByRole('dialog')).toHaveTextContent('媒体检查与 MediaPipe 检测会独立继续。')
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '结束接收' }))
    expect((await screen.findAllByText('接收完成 · 有缺失')).length).toBeGreaterThan(0)
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) => String(input).endsWith('/receipt/finalize') && init?.method === 'POST',
      ),
    ).toBe(true)
  })
})
