import { screen, within } from '@testing-library/react'
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
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    expect(await screen.findByText(/当前事件质量为“需复核”/)).toBeInTheDocument()
    expect(screen.getByText(/任务和既有修订历史仍可审计/)).toBeInTheDocument()
    expect(screen.getAllByText('第 1 次正式修订').length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: '提交修订' })).toBeDisabled()
    expect(screen.getByLabelText('主视图')).toBeDisabled()
    expect(screen.getByRole('link', { name: '打开配药事件标注' })).toHaveAttribute(
      'href',
      paths.captureGroupFocus(projectId, groupId, 'annotation'),
    )
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
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    expect(await screen.findByText('唯一配药事件任务 · 已完成')).toBeInTheDocument()
    expect(screen.getByText('首个正式修订已存在；主视图已锁定。')).toBeInTheDocument()
    expect(screen.getByLabelText('主视图')).toBeDisabled()
    expect(screen.getAllByText('复核 · 已通过').length).toBeGreaterThan(0)
    expect(screen.getByText('训练资格检查 · 当前规则检查通过')).toBeInTheDocument()
    const compareStrip = screen.getByLabelText('参考视图机位切换')
    expect(within(compareStrip).getAllByRole('button', { name: /机位 / })).toHaveLength(2)
  })
})
