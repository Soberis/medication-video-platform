import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { renderRoute } from '@/test/render'
import { paths } from '@/app/paths'
import CaptureGroupDetailView from '@/views/CaptureGroupDetailView'
import CaptureGroupListView from '@/views/CaptureGroupListView'

import {
  captureGroupFixture,
  groupId,
  groupTask,
  projectId,
  qualityFixture,
  summaryFromGroup,
  stubGroupDetail,
  stubGroupList,
  stubPagedGroupList,
  jsonResponse,
} from './capture-group-fixtures'

afterEach(() => vi.unstubAllGlobals())

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

describe('CaptureGroup operational list and quality', () => {
  it('shows page loading feedback while the first CaptureGroup page is pending', async () => {
    const firstPage = deferred<Response>()
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/capture-groups?')) return firstPage.promise
      return Promise.reject(new Error(`Unexpected fetch: ${url}`))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    expect(screen.getByRole('status')).toHaveTextContent('正在读取配药事件…')
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument()

    firstPage.resolve(jsonResponse({ items: [], next_cursor: null }))
    expect(await screen.findByText('还没有配药事件')).toBeInTheDocument()
  })

  it('keeps existing rows visible and scopes refresh feedback to the refresh button', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'refresh-stable',
    })
    const fetchMock = stubGroupList([group], [], { nextCursor: 'cursor-more' })

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const row = await screen.findByRole('listitem', { name: /refresh-stable/ })
    const refreshRequest = deferred<Response>()
    fetchMock.mockImplementationOnce(() => refreshRequest.promise)

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '刷新' }))

    expect(row).toBeInTheDocument()
    expect(screen.queryByText('正在读取配药事件…')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /刷新/ })).toHaveClass('ant-btn-loading')
    expect(screen.getByRole('button', { name: /加载更多/ })).not.toHaveClass('ant-btn-loading')

    refreshRequest.resolve(
      jsonResponse({ items: [summaryFromGroup(group)], next_cursor: 'cursor-more' }),
    )
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /刷新/ })).not.toHaveClass('ant-btn-loading')
    })
  })

  it('keeps rows during load more, appends the page, and suppresses duplicate groups', async () => {
    const first = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'page-one',
    })
    const second = captureGroupFixture('60000000-0000-4000-8000-000000000002', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'page-two',
    })
    const fetchMock = stubGroupList([first], [], { nextCursor: 'cursor-page-2' })

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const firstRow = await screen.findByRole('listitem', { name: /page-one/ })
    const nextPage = deferred<Response>()
    fetchMock.mockImplementationOnce(() => nextPage.promise)

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '加载更多' }))

    expect(firstRow).toBeInTheDocument()
    expect(screen.queryByText('正在读取配药事件…')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /加载更多/ })).toHaveClass('ant-btn-loading')
    expect(screen.getByRole('button', { name: /刷新/ })).not.toHaveClass('ant-btn-loading')

    nextPage.resolve(
      jsonResponse({
        items: [summaryFromGroup(first), summaryFromGroup(second)],
        next_cursor: null,
      }),
    )

    expect(await screen.findByRole('listitem', { name: /page-two/ })).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: /加载更多/ })).not.toBeInTheDocument()
    })
  })

  it('preserves authoritative rows and shows a technical error when load more fails', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'page-one-error',
    })
    const fetchMock = stubGroupList([group], [], { nextCursor: 'cursor-page-2' })

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const row = await screen.findByRole('listitem', { name: /page-one-error/ })
    fetchMock.mockImplementationOnce(() =>
      Promise.resolve(jsonResponse({ error_code: 'CAPTURE_GROUP_LIST_FAILED' }, { status: 500 })),
    )

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '加载更多' }))

    expect(await screen.findByText('暂时无法读取配药事件，请稍后重试。')).toBeInTheDocument()
    expect(row).toBeInTheDocument()
    expect(screen.queryByText('还没有配药事件')).not.toBeInTheDocument()
    expect(screen.queryByText('正在读取配药事件…')).not.toBeInTheDocument()
  })

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

    expect(await screen.findByRole('heading', { level: 1, name: '配药事件' })).toBeInTheDocument()
    expect(
      await screen.findByText('事件列表', {
        selector: '.capture-group-list-heading .app-eyebrow',
      }),
    ).toBeInTheDocument()
    expect(screen.queryByText('操作队列')).not.toBeInTheDocument()
    expect(screen.getByRole('list', { name: '配药事件列表' })).toBeInTheDocument()
    expect(await screen.findAllByRole('listitem')).toHaveLength(5)
    const firstRow = screen.getByRole('listitem', { name: /capture-1/ })
    expect(firstRow).toHaveTextContent('应 5 · 已收 4 · 缺失 1')
    expect(firstRow).toHaveTextContent('缺失：机位 3')
    expect(firstRow).toHaveTextContent('接收完成 · 有缺失')
    expect(firstRow).toHaveTextContent('合格（排除部分机位）')
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
    expect(receivingRow).toHaveTextContent('接收状态 · 接收中')
    expect(receivingRow).toHaveTextContent('应 1 · 已收 1 · 缺失 0')
    expect(receivingRow).toHaveTextContent('当前状态：等待接收完成后进行事件质量判断')
    expect(receivingRow).not.toHaveTextContent('下一步：等待接收完成后进行事件质量判断')
    expect(receivingRow).toHaveTextContent('等待接收完成后进行事件质量判断')

    const completeRow = screen.getByRole('listitem', { name: /complete-unreviewed/ })
    expect(completeRow).toHaveTextContent('应 5 · 已收 5 · 缺失 0')
    expect(completeRow).toHaveTextContent('待判断')
    expect(completeRow).toHaveTextContent('下一步：进行事件质量判断')
    expect(completeRow).toHaveTextContent('进行事件质量判断')

    const incompleteRow = screen.getByRole('listitem', { name: /incomplete-gap/ })
    expect(incompleteRow).toHaveTextContent('接收状态 · 接收完成 · 有缺失')
    expect(incompleteRow).toHaveTextContent('应 5 · 已收 3 · 缺失 2')
    expect(incompleteRow).toHaveTextContent('缺失：机位 2、机位 4')

    const rejectedRow = screen.getByRole('listitem', { name: /rejected-no-task/ })
    expect(rejectedRow).toHaveTextContent('不合格')
    expect(rejectedRow).toHaveTextContent('补充机位事实并重新判断事件质量')

    const qualifiedRow = screen.getByRole('listitem', { name: /qualified-task/ })
    expect(qualifiedRow).toHaveTextContent('合格')
    expect(qualifiedRow).toHaveTextContent('任务 70000000…0001')
    expect(
      fetchMock.mock.calls.filter(([input]) =>
        String(input).includes('/capture-group-quality-reviews'),
      ),
    ).toHaveLength(1)
    expect(
      fetchMock.mock.calls.filter(
        ([input]) =>
          String(input).includes('/capture-groups/') && String(input).endsWith('/quality-review'),
      ),
    ).toHaveLength(0)
  })

  it('shows a concise fallback identity without rendering the same short ID twice', async () => {
    const group = captureGroupFixture('60000000-0000-4000-8000-000000000006', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    group.capture_group_key = null
    stubGroupList([group], [], {
      qualityByGroup: { [group.capture_group_id]: qualityFixture(group, 'UNREVIEWED') },
    })

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const row = await screen.findByRole('listitem', {
      name: '配药事件 · 60000000…0006',
    })
    expect(within(row).getByRole('link', { name: '配药事件 · 60000000…0006' })).toBeInTheDocument()
    expect(within(row).queryByText('60000000…0006', { exact: true })).not.toBeInTheDocument()
  })

  it('labels actionable and terminal rows separately', async () => {
    const group = captureGroupFixture('60000000-0000-4000-8000-000000000007', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'completed-training',
    })
    const task = groupTask(group.capture_group_id, true, { revision: true })
    stubGroupList([group], [task], {
      qualityByGroup: { [group.capture_group_id]: qualityFixture(group, 'QUALIFIED') },
    })

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const row = await screen.findByRole('listitem', { name: /completed-training/ })
    expect(row).toHaveTextContent('当前状态：训练资格检查已通过')
    expect(row).not.toHaveTextContent('下一步：训练资格检查已通过')
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
    expect(screen.getByRole('listitem', { name: /capture-page-1/ })).toHaveTextContent('合格')
    expect(screen.queryByRole('listitem', { name: /capture-page-2/ })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '加载更多' }))

    const firstRow = screen.getByRole('listitem', { name: /capture-page-1/ })
    expect(await screen.findByRole('listitem', { name: /capture-page-2/ })).toBeInTheDocument()
    expect(firstRow).toHaveTextContent('合格')
    expect(screen.getByRole('listitem', { name: /capture-page-2/ })).toHaveTextContent('合格')
    expect(screen.queryByRole('button', { name: '加载更多' })).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/capture-groups?limit=20&cursor=cursor-page-2`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
    expect(
      fetchMock.mock.calls.filter(([input]) =>
        String(input).includes('/capture-group-quality-reviews'),
      ).length,
    ).toBe(2)
  })

  it('keeps first-page quality loaded when a later page quality request fails', async () => {
    const first = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'quality-page-1-loaded',
    })
    const second = captureGroupFixture('60000000-0000-4000-8000-000000000002', {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
      key: 'quality-page-2-unavailable',
    })
    stubPagedGroupList(
      [
        { group: first, nextCursor: 'cursor-page-2' },
        { group: second, nextCursor: null },
      ],
      [],
      { qualityListErrors: [null, { status: 500 }] },
    )
    const user = userEvent.setup()

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const firstRow = await screen.findByRole('listitem', { name: /quality-page-1-loaded/ })
    expect(firstRow).toHaveTextContent('事件质量合格')
    await user.click(screen.getByRole('button', { name: '加载更多' }))

    const secondRow = await screen.findByRole('listitem', { name: /quality-page-2-unavailable/ })
    expect(firstRow).toHaveTextContent('事件质量合格')
    expect(firstRow).not.toHaveTextContent('事件质量暂不可用')
    expect(secondRow).toHaveTextContent('事件质量暂不可用，请刷新后重试')
    expect(await screen.findByText('事件质量 · 暂不可用')).toBeInTheDocument()
  })

  it('keeps first-page quality unavailable when a later page loads successfully', async () => {
    const first = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'quality-page-1-unavailable',
    })
    const second = captureGroupFixture('60000000-0000-4000-8000-000000000002', {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
      key: 'quality-page-2-loaded',
    })
    stubPagedGroupList(
      [
        { group: first, nextCursor: 'cursor-page-2' },
        { group: second, nextCursor: null },
      ],
      [],
      { qualityListErrors: [{ status: 500 }, null] },
    )
    const user = userEvent.setup()

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const firstRow = await screen.findByRole('listitem', { name: /quality-page-1-unavailable/ })
    expect(firstRow).toHaveTextContent('事件质量暂不可用，请刷新后重试')
    await user.click(screen.getByRole('button', { name: '加载更多' }))

    const secondRow = await screen.findByRole('listitem', { name: /quality-page-2-loaded/ })
    expect(firstRow).toHaveTextContent('事件质量暂不可用，请刷新后重试')
    expect(secondRow).toHaveTextContent('事件质量合格')
    expect(secondRow).not.toHaveTextContent('事件质量暂不可用')
  })

  it('keeps forbidden and loaded quality visibility isolated between pages', async () => {
    const first = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'quality-page-1-forbidden',
    })
    const second = captureGroupFixture('60000000-0000-4000-8000-000000000002', {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
      key: 'quality-page-2-loaded-after-forbidden',
    })
    stubPagedGroupList(
      [
        { group: first, nextCursor: 'cursor-page-2' },
        { group: second, nextCursor: null },
      ],
      [],
      {
        qualityListErrors: [
          { status: 403, errorCode: 'CAPTURE_GROUP_QUALITY_ACCESS_DENIED' },
          null,
        ],
      },
    )
    const user = userEvent.setup()

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const firstRow = await screen.findByRole('listitem', { name: /quality-page-1-forbidden/ })
    expect(firstRow).toHaveTextContent('事件质量无权查看')
    await user.click(screen.getByRole('button', { name: '加载更多' }))

    const secondRow = await screen.findByRole('listitem', {
      name: /quality-page-2-loaded-after-forbidden/,
    })
    expect(firstRow).toHaveTextContent('事件质量无权查看')
    expect(secondRow).toHaveTextContent('事件质量合格')
    expect(secondRow).not.toHaveTextContent('事件质量无权查看')
    expect(await screen.findByText('事件质量 · 无权查看')).toBeInTheDocument()
  })

  it('keeps existing task facts when a later page task request fails', async () => {
    const first = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'task-page-1-loaded',
    })
    const second = captureGroupFixture('60000000-0000-4000-8000-000000000002', {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
      key: 'task-page-2-unavailable',
    })
    stubPagedGroupList(
      [
        { group: first, nextCursor: 'cursor-page-2' },
        { group: second, nextCursor: null },
      ],
      [groupTask(first.capture_group_id, true)],
      { taskListErrors: [false, true] },
    )
    const user = userEvent.setup()

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const firstRow = await screen.findByRole('listitem', { name: /task-page-1-loaded/ })
    expect(firstRow).toHaveTextContent('任务 70000000…0001')
    expect(firstRow).not.toHaveTextContent('任务状态暂不可用，请刷新后重试')
    await user.click(screen.getByRole('button', { name: '加载更多' }))

    const secondRow = await screen.findByRole('listitem', { name: /task-page-2-unavailable/ })
    expect(firstRow).toHaveTextContent('任务 70000000…0001')
    expect(firstRow).not.toHaveTextContent('任务状态暂不可用，请刷新后重试')
    expect(secondRow).toHaveTextContent('任务状态暂不可用，请刷新后重试')
    expect(await screen.findByText('部分任务状态不可直接确认')).toBeInTheDocument()
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
      session: { username: 'platform-admin', role: 'ADMIN' },
    })

    expect(await screen.findByText('还没有配药事件')).toBeInTheDocument()
    expect(screen.getByText(/完成明确归组的接收记录后，配药事件会显示在这里/)).toBeInTheDocument()
    const breadcrumb = screen.getByRole('navigation', { name: '面包屑导航' })
    expect(breadcrumb).toHaveTextContent('视频管理')
    expect(breadcrumb).toHaveTextContent('配药事件')
    expect(screen.getByRole('link', { name: '上传视频' })).toHaveAttribute(
      'href',
      paths.localUpload,
    )
    expect(screen.queryByRole('link', { name: '查看视频管理' })).not.toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(([input]) => String(input).endsWith('/annotation-tasks')),
    ).toBe(false)
  })

  it('does not show an upload CTA for a member without the authorized upload entry', async () => {
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
      session: { username: 'project-member', role: 'MEMBER' },
    })

    expect(await screen.findByText('还没有配药事件')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '上传视频' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '查看视频管理' })).not.toBeInTheDocument()
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
    expect(row).not.toHaveTextContent('等待系统创建唯一配药事件任务')
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

  it('keeps a legitimate UNREVIEWED batch result distinct from unavailable quality', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2, 3, 4, 5],
      received: [1, 2, 3, 4, 5],
      receipt: 'COMPLETE',
      key: 'unreviewed-quality',
    })
    stubGroupList([group], [], {
      qualityByGroup: { [group.capture_group_id]: qualityFixture(group, 'UNREVIEWED') },
    })

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const row = await screen.findByRole('listitem', { name: /unreviewed-quality/ })
    expect(row).toHaveTextContent('事件质量')
    expect(row).toHaveTextContent('待判断')
    expect(row).toHaveTextContent('机位质量待判断')
    expect(screen.queryByText('事件质量 · 暂不可用')).not.toBeInTheDocument()
  })

  it('shows quality as unavailable after a batch 5xx while keeping event rows visible', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
      key: 'quality-5xx',
    })
    stubGroupList([group], [], { qualityListError: { status: 500 } })

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const row = await screen.findByRole('listitem', { name: /quality-5xx/ })
    expect(row).toHaveTextContent('机位质量暂不可用')
    expect(row).toHaveTextContent('事件质量暂不可用，请刷新后重试')
    expect(row).not.toHaveTextContent('待判断')
    expect(await screen.findByText('事件质量 · 暂不可用')).toBeInTheDocument()
  })

  it('shows forbidden quality only when the batch contract identifies a 403', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'quality-forbidden',
    })
    stubGroupList([group], [], {
      qualityListError: {
        status: 403,
        errorCode: 'CAPTURE_GROUP_QUALITY_ACCESS_DENIED',
      },
    })

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const row = await screen.findByRole('listitem', { name: /quality-forbidden/ })
    expect(row).toHaveTextContent('机位质量无权查看')
    expect(row).toHaveTextContent('事件质量无权查看')
    expect(row).not.toHaveTextContent('待判断')
    expect(await screen.findByText('事件质量 · 无权查看')).toBeInTheDocument()
  })

  it('treats a missing item in an otherwise successful quality batch as unavailable', async () => {
    const first = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'quality-present',
    })
    const missing = captureGroupFixture('60000000-0000-4000-8000-000000000002', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'quality-missing',
    })
    stubGroupList([first, missing], [], {
      omitQualityFor: [missing.capture_group_id],
    })

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const row = await screen.findByRole('listitem', { name: /quality-missing/ })
    expect(row).toHaveTextContent('事件质量暂不可用，请刷新后重试')
    expect(row).not.toHaveTextContent('待判断')
  })

  it('shows the product error state for a top-level group-list failure', async () => {
    stubGroupList([], [], { groupListError: { status: 500 } })

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    expect(await screen.findByText('暂时无法读取配药事件，请稍后重试。')).toBeInTheDocument()
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

    expect(await screen.findByText('接收仍在进行中')).toBeInTheDocument()
    expect(screen.getByText(/最终“合格”或“合格（排除部分机位）”操作已禁用/)).toBeInTheDocument()
    const qualitySelect = screen.getByRole('combobox', { name: '事件质量结论' })
    expect(qualitySelect).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存事件质量' })).toBeDisabled()
    expect(fetchMock).not.toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/capture-groups/${groupId}/quality-review`,
      expect.objectContaining({ method: 'PUT' }),
    )
  })
})
