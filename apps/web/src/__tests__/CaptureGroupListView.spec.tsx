import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  approvedGroupTask,
  captureGroupFixture,
  groupId,
  groupTask,
  jsonResponse,
  projectId,
  qualityFixture,
  summaryFromGroup,
  stubGroupList,
  stubPagedGroupList,
  submittedGroupTask,
  taskList,
} from '@/__tests__/capture-group-fixtures'
import { paths } from '@/app/paths'
import { renderRouter } from '@/test/render'
import CaptureGroupListView from '@/views/CaptureGroupListView'

afterEach(() => vi.unstubAllGlobals())

describe('capture group list view', () => {
  it('keeps the data-group facts and optional human-label entry separate', async () => {
    const activeGroup = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'active-group',
    })
    const completedGroup = captureGroupFixture('60000000-0000-4000-8000-000000000002', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'completed-group',
    })
    const fetchMock = stubGroupList(
      [activeGroup, completedGroup],
      [groupTask(activeGroup.capture_group_id, true), approvedGroupTask(completedGroup)],
      {
        qualityByGroup: {
          [activeGroup.capture_group_id]: qualityFixture(activeGroup, 'QUALIFIED'),
          [completedGroup.capture_group_id]: qualityFixture(completedGroup, 'QUALIFIED'),
        },
      },
    )
    const { router } = renderView()
    const user = userEvent.setup()

    const list = await screen.findByRole('list', { name: '数据组列表' })
    const activeRow = within(list).getByRole('listitem', { name: /active-group/ })
    const completedRow = within(list).getByRole('listitem', { name: /completed-group/ })

    expect(activeRow.querySelector('.capture-group-list-eyebrow')).not.toBeInTheDocument()
    expect(activeRow.querySelector('.capture-group-list-identity svg')).not.toBeInTheDocument()
    expect(screen.getByText('共 2 个数据组')).toBeInTheDocument()
    expect(activeRow).toHaveTextContent('有人工标签任务')
    expect(activeRow).toHaveTextContent('Media Probe · hand-landmarks.v1')
    expect(activeRow).toHaveTextContent('1/1 机位已收')

    const processLink = within(activeRow).getByRole('link', { name: /进入配药动作标注/ })
    expect(completedRow).toHaveTextContent('有人工标签任务')
    expect(
      within(completedRow).getByRole('link', { name: /查看复核 \/ 事件清洗结果/ }),
    ).toHaveTextContent('查看复核 / 事件清洗结果')

    await user.click(processLink)
    await waitFor(() =>
      expect(router.state.location.pathname).toBe(
        paths.captureGroup(projectId, activeGroup.capture_group_id),
      ),
    )
    expect(fetchMock.mock.calls.some(([, init]) => init?.method && init.method !== 'GET')).toBe(
      false,
    )
  })

  it('keeps receiving, incomplete, missing-camera and permission-limited facts truthful', async () => {
    const receivingGroup = captureGroupFixture('60000000-0000-4000-8000-000000000003', {
      expected: [1, 2],
      received: [1],
      receipt: 'RECEIVING',
      key: 'receiving-group',
    })
    const incompleteGroup = captureGroupFixture('60000000-0000-4000-8000-000000000004', {
      expected: [1, 2, 4],
      received: [1, 4],
      receipt: 'INCOMPLETE',
      key: 'incomplete-group',
    })
    stubGroupList([receivingGroup, incompleteGroup], [], {
      taskScope: 'ASSIGNEE',
      qualityByGroup: {
        [receivingGroup.capture_group_id]: qualityFixture(receivingGroup, 'UNREVIEWED'),
        [incompleteGroup.capture_group_id]: qualityFixture(
          incompleteGroup,
          'QUALIFIED_WITH_EXCLUSIONS',
        ),
      },
    })
    renderView()

    const list = await screen.findByRole('list', { name: '数据组列表' })
    const receivingRow = within(list).getByRole('listitem', { name: /receiving-group/ })
    const incompleteRow = within(list).getByRole('listitem', { name: /incomplete-group/ })

    expect(receivingRow).toHaveTextContent('1/2 机位已收 · 接收中')
    expect(receivingRow).toHaveTextContent('缺失：机位 2')
    expect(receivingRow).toHaveTextContent('没有分配给我的人工标签任务')
    expect(receivingRow).toHaveTextContent('MediaPipe machine evidence')
    expect(within(receivingRow).getByRole('link', { name: /查看数据组/ })).toBeInTheDocument()

    expect(incompleteRow).toHaveTextContent('2/3 机位已收 · 接收完成 · 有缺失')
    expect(incompleteRow).toHaveTextContent('缺失：机位 2')
    expect(incompleteRow).toHaveTextContent('没有分配给我的人工标签任务')
    expect(incompleteRow).toHaveTextContent('hand-landmarks.v1')
    expect(within(incompleteRow).getByRole('link', { name: /查看数据组/ })).toBeInTheDocument()
  })

  it('keeps machine facts independent of the retired quality batch enrichment', async () => {
    const group = captureGroupFixture('60000000-0000-4000-8000-000000000005', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'quality-independent-group',
    })
    const fetchMock = stubGroupList([group], [], {
      qualityListError: { status: 503, errorCode: 'CAPTURE_GROUP_QUALITY_REQUEST_FAILED' },
    })
    renderView()

    const row = await screen.findByRole('listitem', { name: /quality-independent-group/ })
    expect(row).toHaveTextContent('MediaPipe machine evidence')
    expect(row).toHaveTextContent('hand-landmarks.v1')
    expect(
      fetchMock.mock.calls.some(([input]) =>
        String(input).includes('capture-group-quality-reviews'),
      ),
    ).toBe(false)
  })

  it('does not invent task actions when task state is unavailable', async () => {
    const group = captureGroupFixture('60000000-0000-4000-8000-000000000006', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'task-unavailable-group',
    })
    stubGroupList([group], [], {
      taskListError: true,
      qualityByGroup: { [group.capture_group_id]: qualityFixture(group, 'QUALIFIED') },
    })
    renderView()

    const row = await screen.findByRole('listitem', { name: /task-unavailable-group/ })
    expect(row).toHaveTextContent('人工任务状态暂不可用')
    expect(row).not.toHaveTextContent('暂无人工标签任务')
    expect(within(row).getByRole('button', { name: '重试读取人工任务' })).toBeInTheDocument()
    expect(row).toHaveTextContent('MediaPipe machine evidence')
    expect(within(row).getByRole('link', { name: /查看数据组/ })).toBeInTheDocument()
    expect(within(row).queryByRole('link', { name: /进入/ })).not.toBeInTheDocument()
  })

  it('appends the next cursor page without changing the data-group navigation contract', async () => {
    const firstGroup = captureGroupFixture('60000000-0000-4000-8000-000000000007', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'first-page-group',
    })
    const secondGroup = captureGroupFixture('60000000-0000-4000-8000-000000000008', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'second-page-group',
    })
    const fetchMock = stubPagedGroupList(
      [
        { group: firstGroup, nextCursor: 'cursor-2' },
        { group: secondGroup, nextCursor: null },
      ],
      [
        groupTask(firstGroup.capture_group_id, true),
        submittedGroupTask(secondGroup, { canReview: false }),
      ],
      {
        qualityByGroup: {
          [firstGroup.capture_group_id]: qualityFixture(firstGroup, 'QUALIFIED'),
          [secondGroup.capture_group_id]: qualityFixture(secondGroup, 'QUALIFIED'),
        },
      },
    )
    renderView()
    const user = userEvent.setup()

    expect(await screen.findByText('已显示 1 个数据组')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '加载更多' }))

    await waitFor(() => expect(screen.getByText('共 2 个数据组')).toBeInTheDocument())
    expect(screen.getByRole('listitem', { name: /second-page-group/ })).toHaveTextContent(
      '有人工标签任务',
    )
    expect(screen.queryByRole('button', { name: '加载更多' })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('cursor=cursor-2'))).toBe(
      true,
    )
  })

  it('shows the data-group list while the optional task read is pending', async () => {
    const group = captureGroupFixture('60000000-0000-4000-8000-000000000009', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'task-pending-group',
    })
    const taskResponse = deferredResponse()
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const path = new URL(String(input), 'http://localhost').pathname
      if (path.endsWith('/capture-groups')) {
        return Promise.resolve(
          jsonResponse({ items: [summaryFromGroup(group)], next_cursor: null }),
        )
      }
      if (path.endsWith('/annotation-tasks')) return taskResponse.promise
      return Promise.reject(new Error(`Unexpected fetch: ${String(input)}`))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderView()

    const row = await screen.findByRole('listitem', { name: /task-pending-group/ })
    expect(row).toHaveTextContent('人工任务读取中')
    expect(row).toHaveTextContent('MediaPipe machine evidence')
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) =>
          String(input).includes('/capture-groups') && init?.signal instanceof AbortSignal,
      ),
    ).toBe(true)
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) =>
          String(input).includes('/annotation-tasks') && init?.signal instanceof AbortSignal,
      ),
    ).toBe(true)

    taskResponse.resolve(taskList([]))
    await waitFor(() => expect(row).toHaveTextContent('暂无人工标签任务'))
  })

  it('uses the PROJECT scope wording when no human-label task exists', async () => {
    const group = captureGroupFixture('60000000-0000-4000-8000-000000000010', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'project-empty-group',
    })
    stubGroupList([group], [], { taskScope: 'PROJECT' })
    renderView()

    const row = await screen.findByRole('listitem', { name: /project-empty-group/ })
    await waitFor(() => expect(row).toHaveTextContent('暂无人工标签任务'))
    expect(row).not.toHaveTextContent('没有分配给我的人工标签任务')
  })

  it('shows an ASSIGNEE task with the capture-group deep link', async () => {
    const group = captureGroupFixture('60000000-0000-4000-8000-000000000011', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'assignee-task-group',
    })
    stubGroupList([group], [groupTask(group.capture_group_id, true)], {
      taskScope: 'ASSIGNEE',
    })
    renderView()

    const row = await screen.findByRole('listitem', { name: /assignee-task-group/ })
    await waitFor(() => expect(row).toHaveTextContent('有分配给我的人工标签任务'))
    expect(within(row).getByRole('link', { name: /进入配药动作标注/ })).toHaveAttribute(
      'href',
      paths.captureGroupFocus(projectId, group.capture_group_id, 'annotation'),
    )
  })

  it('ignores delayed Project A responses after navigating to Project B', async () => {
    const projectA = projectId
    const projectB = '30000000-0000-4000-8000-000000000002'
    const groupA = captureGroupFixture('60000000-0000-4000-8000-000000000012', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'project-a-group',
    })
    const groupB = captureGroupFixture('60000000-0000-4000-8000-000000000013', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'project-b-group',
    })
    const projectAGroupResponse = deferredResponse()
    const projectATaskResponse = deferredResponse()
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes(`/projects/${projectA}/capture-groups`)) {
        return projectAGroupResponse.promise
      }
      if (url.includes(`/projects/${projectA}/annotation-tasks`)) {
        return projectATaskResponse.promise
      }
      if (url.includes(`/projects/${projectB}/capture-groups`)) {
        return Promise.resolve(
          jsonResponse({ items: [summaryFromGroup(groupB)], next_cursor: null }),
        )
      }
      if (url.includes(`/projects/${projectB}/annotation-tasks`)) {
        return Promise.resolve(jsonResponse(taskList([])))
      }
      return Promise.reject(new Error(`Unexpected fetch: ${url}`))
    })
    vi.stubGlobal('fetch', fetchMock)

    const { router } = renderView(paths.captureGroups(projectA))
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.filter(([input]) => String(input).includes(`/projects/${projectA}/`)),
      ).toHaveLength(2),
    )

    await router.navigate(paths.captureGroups(projectB))

    const projectBRow = await screen.findByRole('listitem', { name: /project-b-group/ })
    expect(projectBRow).toBeInTheDocument()
    expect(screen.queryByRole('listitem', { name: /project-a-group/ })).not.toBeInTheDocument()

    projectAGroupResponse.resolve({
      items: [summaryFromGroup(groupA)],
      next_cursor: null,
    })
    projectATaskResponse.resolve(taskList([groupTask(groupB.capture_group_id, true)]))

    await waitFor(() => {
      expect(screen.getByRole('listitem', { name: /project-b-group/ })).toBeInTheDocument()
      expect(screen.queryByRole('listitem', { name: /project-a-group/ })).not.toBeInTheDocument()
      expect(screen.queryByRole('link', { name: /进入配药动作标注/ })).not.toBeInTheDocument()
    })
  })
})

function renderView(route = paths.captureGroups(projectId)) {
  return renderRouter(
    [
      {
        path: '/projects/:projectId/capture-groups',
        element: <CaptureGroupListView />,
      },
      {
        path: '/projects/:projectId/capture-groups/:captureGroupId',
        element: <div aria-label="CaptureGroup 工作台" />,
      },
    ],
    [route],
  )
}

function deferredResponse() {
  let resolveResponse!: (response: Response) => void
  const promise = new Promise<Response>((resolve) => {
    resolveResponse = resolve
  })
  return {
    promise,
    resolve: (body: object) => resolveResponse(jsonResponse(body)),
  }
}
