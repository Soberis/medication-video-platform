import { act, screen, waitFor, within } from '@testing-library/react'
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
  it('filters the authorized task query, including tasks outside the loaded video page', async () => {
    const loaded = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const outsideId = '60000000-0000-4000-8000-000000000099'
    stubGroupList([loaded], [groupTask(outsideId, true)], { nextCursor: 'more' })
    renderView(`${paths.captureGroups(projectId)}?view=assigned`)
    const taskRow = await screen.findByRole('listitem', { name: /待标注/ })
    expect(taskRow).toHaveTextContent('60000000')
    expect(within(taskRow).getByRole('link', { name: /标注/ })).toHaveAttribute(
      'href',
      paths.captureGroupFocus(projectId, outsideId, 'annotation'),
    )
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(taskRow.querySelector('.annotation-task-row-identity')).not.toBeNull()
  })

  it('allows one or multiple selections and hides records without approved annotation', async () => {
    const first = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const second = captureGroupFixture('60000000-0000-4000-8000-000000000099', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const pending = captureGroupFixture('60000000-0000-4000-8000-000000000088', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    stubGroupList([first, second, pending], [approvedGroupTask(first), approvedGroupTask(second)])
    renderRouter(
      [{ path: '/projects/:projectId/capture-groups', element: <CaptureGroupListView /> }],
      [paths.captureGroups(projectId)],
      { username: 'admin', role: 'ADMIN' },
    )
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '导出数据' }))
    const dialog = await screen.findByRole('dialog', { name: '导出数据' })
    const selectable = await within(dialog).findAllByRole('checkbox', { name: /选择导出/ })
    await user.click(selectable[0]!)
    await user.click(selectable[1]!)
    expect(selectable).toHaveLength(2)
    expect(within(dialog).queryByText('尚未标注')).not.toBeInTheDocument()
    expect(within(dialog).getByText('已选择 2 条')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: '下载 HDF5' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: /项目全部|选择全部/ })).not.toBeInTheDocument()
  })
  it('offers project export to a platform admin without project membership', async () => {
    stubGroupList([], [], { projectRole: 'MEMBER', taskScope: 'ASSIGNEE' })
    renderRouter(
      [{ path: '/projects/:projectId/capture-groups', element: <CaptureGroupListView /> }],
      [paths.captureGroups(projectId)],
      { username: 'admin', role: 'ADMIN' },
    )
    await screen.findByText('合成数据项目')
    expect(screen.getByRole('button', { name: '导出数据' })).toBeInTheDocument()
  })
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

    const list = await screen.findByRole('list', { name: '采集记录列表' })
    const activeRow = within(list).getByRole('listitem', { name: /active-group/ })
    const completedRow = within(list).getByRole('listitem', { name: /completed-group/ })

    expect(activeRow.querySelector('.capture-group-list-eyebrow')).not.toBeInTheDocument()
    expect(activeRow.querySelector('.capture-group-list-identity svg')).toHaveAttribute(
      'aria-hidden',
      'true',
    )
    expect(screen.getByText('共 2 条采集记录')).toBeInTheDocument()
    const toolbar = screen
      .getByRole('combobox', { name: '视频视图' })
      .closest('.capture-group-list-toolbar')
    if (!toolbar) throw new Error('Capture group list toolbar was not mounted.')
    const toolbarFilters = toolbar.querySelector<HTMLElement>('.capture-group-list-toolbar-filters')
    const toolbarActions = toolbar.querySelector<HTMLElement>('.capture-group-list-toolbar-actions')
    if (!toolbarFilters || !toolbarActions) {
      throw new Error('Capture group list toolbar groups were not mounted.')
    }
    expect(within(toolbarFilters).getByText('共 2 条采集记录')).toBeInTheDocument()
    expect(within(toolbarActions).getByRole('textbox')).toBeInTheDocument()
    expect(within(toolbarActions).queryByText('共 2 条采集记录')).not.toBeInTheDocument()
    expect(activeRow).toHaveTextContent('待提交')
    expect(activeRow).toHaveTextContent('1/1 机位已收')

    const processLink = within(activeRow).getByRole('link', { name: /查看视频/ })
    expect(within(activeRow).getAllByRole('link')).toHaveLength(1)
    expect(completedRow).toHaveTextContent('已完成')
    expect(within(completedRow).getAllByRole('link')).toHaveLength(1)

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

    const list = await screen.findByRole('list', { name: '采集记录列表' })
    const receivingRow = within(list).getByRole('listitem', { name: /receiving-group/ })
    const incompleteRow = within(list).getByRole('listitem', { name: /incomplete-group/ })

    expect(receivingRow).toHaveTextContent('1/2 机位已收 · 接收中')
    expect(receivingRow).toHaveTextContent('缺失：机位 2')
    expect(receivingRow).not.toHaveTextContent('没有分配给我的人工标签任务')
    expect(receivingRow).not.toHaveTextContent('MediaPipe machine evidence')
    expect(within(receivingRow).getByRole('link', { name: /查看视频/ })).toBeInTheDocument()

    expect(incompleteRow).toHaveTextContent('2/3 机位已收 · 接收完成 · 有缺失')
    expect(incompleteRow).toHaveTextContent('缺失：机位 2')
    expect(incompleteRow).not.toHaveTextContent('没有分配给我的人工标签任务')
    expect(within(incompleteRow).getByRole('link', { name: /查看视频/ })).toBeInTheDocument()
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
    expect(row).not.toHaveTextContent('MediaPipe machine evidence')
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
    expect(row).not.toHaveTextContent('MediaPipe machine evidence')
    expect(within(row).getByRole('link', { name: /查看视频/ })).toBeInTheDocument()
    expect(within(row).queryByRole('link', { name: /进入/ })).not.toBeInTheDocument()
  })

  it('loads all cursor pages automatically without requiring a load more button', async () => {
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
    const { router } = renderView()
    const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
    const user = userEvent.setup()

    expect(await screen.findByText('共 2 条采集记录')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '加载更多' })).not.toBeInTheDocument()
    expect(screen.getByRole('listitem', { name: /first-page-group/ })).toBeInTheDocument()
    expect(screen.getByRole('listitem', { name: /second-page-group/ })).toHaveTextContent('待复核')
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('cursor=cursor-2'))).toBe(
      true,
    )
    const listReads = fetchMock.mock.calls.filter(([input]) =>
      String(input).includes('/capture-groups'),
    ).length
    await user.click(
      within(screen.getByRole('listitem', { name: /second-page-group/ })).getByRole('link', {
        name: /查看视频/,
      }),
    )
    await waitFor(() =>
      expect(router.state.location.pathname).toContain(secondGroup.capture_group_id),
    )
    await router.navigate(-1)
    expect(await screen.findByText('共 2 条采集记录')).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).includes('/capture-groups')),
    ).toHaveLength(listReads)
    await waitFor(() => expect(scrollTo).toHaveBeenCalledWith(0, 0))
    scrollTo.mockRestore()
  })

  it('renders standard pagination directly when records exceed page size', async () => {
    const groups = Array.from({ length: 15 }, (_, i) =>
      captureGroupFixture(`60000000-0000-4000-8000-${String(i + 10).padStart(12, '0')}`, {
        expected: [1],
        received: [1],
        receipt: 'COMPLETE',
        key: `page-test-group-${i + 1}`,
      }),
    )
    stubGroupList(groups, [], { nextCursor: 'cursor-more' })
    renderView()
    const user = userEvent.setup()

    expect(await screen.findByText('共 15 条采集记录')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '加载更多' })).not.toBeInTheDocument()
    expect(screen.getByRole('listitem', { name: /page-test-group-1$/ })).toBeInTheDocument()
    expect(screen.queryByRole('listitem', { name: /page-test-group-11/ })).not.toBeInTheDocument()

    // Pagination numbers are directly accessible, not suppressed by cursor
    const page2Button = screen.getByTitle('2')
    expect(page2Button).toBeInTheDocument()

    await user.click(page2Button)
    expect(await screen.findByRole('listitem', { name: /page-test-group-11/ })).toBeInTheDocument()
    expect(screen.queryByRole('listitem', { name: /page-test-group-1$/ })).not.toBeInTheDocument()
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
    expect(row).not.toHaveTextContent('MediaPipe machine evidence')
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
    await waitFor(() => expect(row).not.toHaveTextContent('人工任务读取中'))
    expect(row).not.toHaveTextContent('暂无人工标签任务')
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
    await waitFor(() => expect(row).not.toHaveTextContent('人工任务读取中'))
    expect(row).not.toHaveTextContent('暂无人工标签任务')
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
    await waitFor(() => expect(row).toHaveTextContent('待提交'))
    expect(within(row).getByRole('link', { name: /查看视频/ })).toHaveAttribute(
      'href',
      paths.captureGroup(projectId, group.capture_group_id),
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
      expect(screen.queryByRole('link', { name: /继续标注/ })).not.toBeInTheDocument()
    })
  })

  it('renders breadcrumb and allows project switching through the project switcher', async () => {
    const loaded = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    stubGroupList([loaded], [])
    const user = userEvent.setup()
    renderView()

    const breadcrumb = await screen.findByRole('navigation', { name: '面包屑导航' })
    expect(breadcrumb).toBeInTheDocument()
    const projectLink = within(breadcrumb).getByRole('link', { name: '项目' })
    expect(projectLink).toHaveAttribute('href', paths.projects)

    const switcherButton = within(breadcrumb).getByRole('button', {
      name: /当前项目：合成数据项目/,
    })
    expect(switcherButton).toBeInTheDocument()

    await user.click(switcherButton)
    expect(await screen.findByText('全部项目')).toBeInTheDocument()
  })

  it('silently polls capture groups on page 1 without disrupting the user', async () => {
    vi.useFakeTimers()
    const group1 = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'group-one',
    })
    const group2 = captureGroupFixture('60000000-0000-4000-8000-000000000002', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'group-two',
    })

    let fetchCount = 0
    stubGroupList([group1], [])
    const baseFetch = globalThis.fetch
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        const path = new URL(url, 'http://localhost').pathname
        if (path === `/api/v1/projects/${projectId}/capture-groups`) {
          fetchCount += 1
          return Promise.resolve(
            jsonResponse({
              items:
                fetchCount >= 2
                  ? [summaryFromGroup(group2), summaryFromGroup(group1)]
                  : [summaryFromGroup(group1)],
              next_cursor: null,
            }),
          )
        }
        return baseFetch(input, init)
      }),
    )

    renderView()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10)
    })
    expect(screen.getByText('group-one')).toBeInTheDocument()
    expect(fetchCount).toBe(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(20_000)
    })
    expect(fetchCount).toBe(2)
    expect(screen.getByText('group-two')).toBeInTheDocument()

    vi.useRealTimers()
  })

  it('displays AI pipeline status indicators, filters by invalid view, and triggers batch clean', async () => {
    const readyGroup = captureGroupFixture('60000000-0000-4000-8000-000000000011', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'ready-video',
    })
    const invalidGroup = captureGroupFixture('60000000-0000-4000-8000-000000000012', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'invalid-video',
    })

    stubGroupList([readyGroup, invalidGroup], [])
    const baseFetch = globalThis.fetch
    const cleanMock = vi.fn().mockResolvedValue(jsonResponse({ ok: true, deletedCount: 1 }))
    const retryMock = vi.fn().mockResolvedValue(jsonResponse({ ok: true, retriedCount: 1 }))

    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        if (url.includes('/api/dev/pipeline-status')) {
          return Promise.resolve(
            jsonResponse({
              ok: true,
              stats: {
                total: 2,
                ready: 1,
                processing: 0,
                invalid: 1,
                noHand: 1,
                failed: 0,
                pending: 0,
              },
              items: {
                [readyGroup.capture_group_id]: {
                  pipeline_status: 'READY',
                  mp_status: 'SUCCEEDED',
                  mp_hand_result: 'HAND_DETECTED',
                  qw_status: 'SUCCEEDED',
                  action_candidate_count: 3,
                },
                [invalidGroup.capture_group_id]: {
                  pipeline_status: 'NO_HAND',
                  mp_status: 'SUCCEEDED',
                  mp_hand_result: 'NO_HAND_DETECTED',
                  qw_status: 'SUCCEEDED',
                  action_candidate_count: 0,
                },
              },
            }),
          )
        }
        if (url.includes('/api/dev/clean-invalid-videos')) {
          return cleanMock()
        }
        if (url.includes('/api/dev/retry-failed-hand-poses')) {
          return retryMock()
        }
        return baseFetch(input, init)
      }),
    )

    renderView()
    const user = userEvent.setup()

    expect(await screen.findByText(/AI 就绪 · 3段动作/)).toBeInTheDocument()
    expect(await screen.findByText('未检出手部')).toBeInTheDocument()

    // View select dropdown allows filtering by ready view
    const viewSelect = await screen.findByRole('combobox', { name: '视频视图' })
    await user.click(viewSelect)
    await user.click(await screen.findByText(/AI 已就绪/))

    // Ready view filters correctly
    expect(screen.getByRole('listitem', { name: /ready-video/ })).toBeInTheDocument()
    expect(screen.queryByRole('listitem', { name: /invalid-video/ })).not.toBeInTheDocument()

    // Switch to invalid/attention view
    await user.click(viewSelect)
    await user.click(await screen.findByText(/未检出手部 \/ 需关注/))

    // The invalid group is visible, ready group is hidden
    expect(screen.getByRole('listitem', { name: /invalid-video/ })).toBeInTheDocument()
    expect(screen.queryByRole('listitem', { name: /ready-video/ })).not.toBeInTheDocument()

    // Retry button is visible and clickable in invalid view
    const retryButton = screen.getByRole('button', { name: /重新检测手部/ })
    expect(retryButton).toBeInTheDocument()
    await user.click(retryButton)
    await waitFor(() => expect(retryMock).toHaveBeenCalled())

    // Clean button is visible in invalid view
    const cleanButton = screen.getByRole('button', { name: /清理未检出视频/ })
    expect(cleanButton).toBeInTheDocument()
    await user.click(cleanButton)

    // Confirm dialog
    const confirmButton = await screen.findByRole('button', { name: '确认清理' })
    await user.click(confirmButton)

    await waitFor(() => expect(cleanMock).toHaveBeenCalled())
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
