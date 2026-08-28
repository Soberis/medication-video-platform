import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { renderRoute } from '@/test/render'
import CaptureGroupDetailView from '@/views/CaptureGroupDetailView'
import CaptureGroupListView from '@/views/CaptureGroupListView'

import {
  captureGroupFixture,
  groupId,
  groupTask,
  projectId,
  qualityFixture,
  stubGroupDetail,
  stubGroupList,
  stubPagedGroupList,
  jsonResponse,
} from './capture-group-fixtures'

afterEach(() => vi.unstubAllGlobals())

describe('CaptureGroup operational list and quality', () => {
  it('renders an operational N=5 list with camera gaps and server-backed group facts', async () => {
    const groups = [1, 2, 3, 4, 5].map((index) =>
      captureGroupFixture(`60000000-0000-4000-8000-${String(index).padStart(12, '0')}`, {
        expected: [1, 2, 3, 4, 5],
        received: index === 1 ? [1, 2, 4, 5] : [1, 2, 3, 4, 5],
        receipt: index === 1 ? 'INCOMPLETE' : 'COMPLETE',
        key: `capture-${index}`,
      }),
    )
    const tasks = groups.map((group, index) => groupTask(group.capture_group_id, index === 0))
    const fetchMock = stubGroupList(groups, tasks)

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    expect(
      await screen.findByRole('heading', { level: 1, name: 'CaptureGroup 多机位' }),
    ).toBeInTheDocument()
    expect(await screen.findAllByRole('listitem')).toHaveLength(5)
    const firstRow = screen.getByRole('listitem', { name: /capture-1/ })
    expect(firstRow).toHaveTextContent('应 5 · 已收 4 · 缺失 1')
    expect(firstRow).toHaveTextContent('缺失：Cam 3')
    expect(firstRow).toHaveTextContent('接收完成 · 有缺失')
    expect(firstRow).toHaveTextContent('合格 · 排除部分视图')
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/capture-groups?limit=20`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })

  it('keeps receipt states, camera counts, and task enrichment visible for N=1 and N=5 summaries', async () => {
    const receiving = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'RECEIVING',
      key: 'receiving-no-task',
    })
    const completeUnreviewed = captureGroupFixture('60000000-0000-4000-8000-000000000002', {
      expected: [1, 2, 3, 4, 5],
      received: [1, 2, 3, 4, 5],
      receipt: 'COMPLETE',
      key: 'complete-unreviewed',
    })
    const incomplete = captureGroupFixture('60000000-0000-4000-8000-000000000003', {
      expected: [1, 2, 3, 4, 5],
      received: [1, 3, 5],
      receipt: 'INCOMPLETE',
      key: 'incomplete-gap',
    })
    const rejected = captureGroupFixture('60000000-0000-4000-8000-000000000004', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'rejected-no-task',
    })
    const qualified = captureGroupFixture('60000000-0000-4000-8000-000000000005', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'qualified-task',
    })
    const groups = [receiving, completeUnreviewed, incomplete, rejected, qualified]
    const tasks = [groupTask(qualified.capture_group_id, true)]
    const fetchMock = stubGroupList(groups, tasks, {
      qualityByGroup: {
        [receiving.capture_group_id]: qualityFixture(receiving, 'UNREVIEWED'),
        [completeUnreviewed.capture_group_id]: qualityFixture(completeUnreviewed, 'UNREVIEWED'),
        [incomplete.capture_group_id]: qualityFixture(incomplete, 'NEEDS_REVIEW'),
        [rejected.capture_group_id]: qualityFixture(rejected, 'REJECTED'),
        [qualified.capture_group_id]: qualityFixture(qualified, 'QUALIFIED'),
      },
    })

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const receivingRow = await screen.findByRole('listitem', { name: /receiving-no-task/ })
    expect(receivingRow).toHaveTextContent('回执 · 接收中')
    expect(receivingRow).toHaveTextContent('应 1 · 已收 1 · 缺失 0')
    expect(receivingRow).toHaveTextContent('等待接收完成后进行组质量判断')

    const completeRow = screen.getByRole('listitem', { name: /complete-unreviewed/ })
    expect(completeRow).toHaveTextContent('应 5 · 已收 5 · 缺失 0')
    expect(completeRow).toHaveTextContent('待判断')
    expect(completeRow).toHaveTextContent('进行 Group Quality 判断')

    const incompleteRow = screen.getByRole('listitem', { name: /incomplete-gap/ })
    expect(incompleteRow).toHaveTextContent('回执 · 接收完成 · 有缺失')
    expect(incompleteRow).toHaveTextContent('应 5 · 已收 3 · 缺失 2')
    expect(incompleteRow).toHaveTextContent('缺失：Cam 2、Cam 4')

    const rejectedRow = screen.getByRole('listitem', { name: /rejected-no-task/ })
    expect(rejectedRow).toHaveTextContent('不合格')
    expect(rejectedRow).toHaveTextContent('补充视图事实并重新判断组质量')

    const qualifiedRow = screen.getByRole('listitem', { name: /qualified-task/ })
    expect(qualifiedRow).toHaveTextContent('合格')
    expect(qualifiedRow).toHaveTextContent('任务 70000000…0001')
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).includes('/quality-review')),
    ).toHaveLength(5)
  })

  it('appends the next CaptureGroup cursor page and enriches only returned summaries', async () => {
    const first = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'capture-page-1',
    })
    const second = captureGroupFixture('60000000-0000-4000-8000-000000000002', {
      expected: [1, 2, 3, 4, 5],
      received: [1, 3, 5],
      receipt: 'INCOMPLETE',
      key: 'capture-page-2',
    })
    const fetchMock = stubPagedGroupList(
      [
        { group: first, nextCursor: 'cursor-page-2' },
        { group: second, nextCursor: null },
      ],
      [groupTask(first.capture_group_id, true)],
    )
    const user = userEvent.setup()

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    expect(await screen.findByRole('listitem', { name: /capture-page-1/ })).toBeInTheDocument()
    expect(screen.queryByRole('listitem', { name: /capture-page-2/ })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '加载更多' }))

    expect(await screen.findByRole('listitem', { name: /capture-page-2/ })).toBeInTheDocument()
    expect(screen.getByRole('listitem', { name: /capture-page-1/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '加载更多' })).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/capture-groups?limit=20&cursor=cursor-page-2`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).includes('/quality-review')).length,
    ).toBe(2)
  })

  it('renders an empty page without requesting task enrichment', async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/capture-groups?')) {
        return Promise.resolve(jsonResponse({ items: [], next_cursor: null }))
      }
      return Promise.reject(new Error(`Unexpected fetch: ${url}`))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    expect(await screen.findByText('当前项目暂无服务端 CaptureGroup。')).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(([input]) => String(input).endsWith('/annotation-tasks')),
    ).toBe(false)
  })

  it('does not present a missing assignee-scoped task as proof that no group task exists', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'permission-limited-task',
    })
    const fetchMock = stubGroupList([group], [], {
      taskScope: 'ASSIGNEE',
      qualityByGroup: { [group.capture_group_id]: qualityFixture(group, 'QUALIFIED') },
    })

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const row = await screen.findByRole('listitem', { name: /permission-limited-task/ })
    expect(row).toHaveTextContent('任务状态按当前权限不可见')
    expect(row).not.toHaveTextContent('等待系统创建唯一 CaptureGroup 任务')
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-tasks`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })

  it('keeps group summaries visible when task enrichment is temporarily unavailable', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'task-status-unavailable',
    })
    stubGroupList([group], [], {
      taskListError: true,
      qualityByGroup: { [group.capture_group_id]: qualityFixture(group, 'QUALIFIED') },
    })

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const row = await screen.findByRole('listitem', { name: /task-status-unavailable/ })
    expect(row).toHaveTextContent('任务状态暂不可用，请刷新后重试')
    expect(await screen.findByText('部分任务状态不可直接确认')).toBeInTheDocument()
  })

  it('blocks final group qualification while RECEIVING and explains the server guard', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1],
      receipt: 'RECEIVING',
    })
    const fetchMock = stubGroupDetail(group, {
      task: null,
      quality: qualityFixture(group, 'UNREVIEWED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    expect(await screen.findByText('回执仍在接收中')).toBeInTheDocument()
    expect(
      screen.getByText(/最终 QUALIFIED \/ QUALIFIED_WITH_EXCLUSIONS 操作已禁用/),
    ).toBeInTheDocument()
    const qualitySelect = screen.getByRole('combobox', { name: 'Group Quality 结论' })
    expect(qualitySelect).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存 Group Quality' })).toBeDisabled()
    expect(fetchMock).not.toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/capture-groups/${groupId}/quality-review`,
      expect.objectContaining({ method: 'PUT' }),
    )
  })
})
