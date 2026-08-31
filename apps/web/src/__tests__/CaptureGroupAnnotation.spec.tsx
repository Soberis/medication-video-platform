import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { paths } from '@/app/paths'
import { renderRoute } from '@/test/render'
import CaptureGroupDetailView from '@/views/CaptureGroupDetailView'

import {
  captureGroupFixture,
  groupId,
  groupTask,
  projectId,
  qualityFixture,
  stubGroupDetail,
} from './capture-group-fixtures'

afterEach(() => vi.unstubAllGlobals())

describe('CaptureGroup annotation workflow', () => {
  it('keeps one downgraded task and disables new Revision submission while preserving history and navigation', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true, { revision: true })
    const fetchMock = stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'NEEDS_REVIEW'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    expect(screen.queryByRole('button', { name: '重新确认事件质量' })).not.toBeInTheDocument()
    expect(await screen.findByRole('combobox', { name: '事件质量结论' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存事件质量' })).toBeInTheDocument()
    expect(screen.getAllByText('需复核').length).toBeGreaterThan(0)
    const auditDetails = screen.getByText('Revision、复核与训练资格').closest('details')
    if (!auditDetails) throw new Error('Annotation audit details were not mounted.')
    await user.click(screen.getByText('Revision、复核与训练资格'))
    expect(within(auditDetails).getByText('任务 ID')).toBeInTheDocument()
    expect(within(auditDetails).getByText('第 1 次正式修订')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '提交修订' })).not.toBeInTheDocument()
    expect(
      within(auditDetails).getByRole('link', { name: '打开配药事件任务上下文' }),
    ).toHaveAttribute('href', paths.captureGroupFocus(projectId, groupId, 'annotation'))
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).endsWith('/revisions')),
    ).toHaveLength(0)
  })

  it('shows the first Revision lock and only one group task before supporting-view switching', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true, { revision: true })
    stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    expect(await screen.findByRole('button', { name: '查看结果' })).toBeInTheDocument()
    const auditDetails = screen.getByText('Revision、复核与训练资格').closest('details')
    if (!auditDetails) throw new Error('Annotation audit details were not mounted.')
    await user.click(screen.getByText('Revision、复核与训练资格'))
    expect(within(auditDetails).getByText('已完成')).toBeInTheDocument()
    expect(within(auditDetails).getByText('第 1 次正式修订')).toBeInTheDocument()
    expect(within(auditDetails).getByText('主视图')).toBeInTheDocument()
    expect(within(auditDetails).getByText('机位 1')).toBeInTheDocument()
    expect(screen.getAllByText('人工复核 · 已通过').length).toBeGreaterThan(0)
    expect(screen.getByText('训练资格检查 · 当前规则检查通过')).toBeInTheDocument()
    const compareStrip = screen.getByLabelText('配药事件机位选择')
    expect(within(compareStrip).getAllByRole('button', { name: /机位 / })).toHaveLength(2)
  })

  it('renders the colocated annotation form without a duplicate entry CTA', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true)
    stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'FAILED',
    })
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    expect(await screen.findByRole('button', { name: '提交修订' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '进入配药动作标注' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '继续配药动作标注' })).not.toBeInTheDocument()
    expect(screen.getByText('进入配药动作标注')).toBeInTheDocument()
  })
})
