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

    expect(await screen.findByText(/当前 Group Quality 为“需复核”/)).toBeInTheDocument()
    expect(screen.getByText(/任务和既有 Revision 历史仍可审计/)).toBeInTheDocument()
    expect(screen.getAllByText('第 1 次正式修订').length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: '提交 Revision' })).toBeDisabled()
    expect(screen.getByLabelText('Primary View')).toBeDisabled()
    expect(screen.getByRole('link', { name: '打开 CaptureGroup Annotation' })).toHaveAttribute(
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

    expect(await screen.findByText('唯一 CaptureGroup task · 已完成')).toBeInTheDocument()
    expect(screen.getByText('首个正式 Revision 已存在；Primary View 已锁定。')).toBeInTheDocument()
    expect(screen.getByLabelText('Primary View')).toBeDisabled()
    expect(screen.getAllByText('Review · 已通过').length).toBeGreaterThan(0)
    expect(screen.getByText('训练资格检查 · 已通过')).toBeInTheDocument()
    const compareStrip = screen.getByLabelText('Supporting View 相机切换')
    expect(within(compareStrip).getAllByRole('button', { name: /Cam / })).toHaveLength(2)
  })
})
