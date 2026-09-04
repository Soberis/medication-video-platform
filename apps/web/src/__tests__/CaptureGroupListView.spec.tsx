import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  approvedGroupTask,
  captureGroupFixture,
  groupId,
  groupTask,
  projectId,
  qualityFixture,
  stubGroupList,
  stubPagedGroupList,
  submittedGroupTask,
} from '@/__tests__/capture-group-fixtures'
import { paths } from '@/app/paths'
import { renderRouter } from '@/test/render'
import CaptureGroupListView from '@/views/CaptureGroupListView'

afterEach(() => vi.unstubAllGlobals())

describe('capture group list view', () => {
  it('keeps the event hierarchy and uses generic navigation CTAs', async () => {
    const activeGroup = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'active-event',
    })
    const completedGroup = captureGroupFixture('60000000-0000-4000-8000-000000000002', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'completed-event',
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

    const list = await screen.findByRole('list', { name: '配药事件列表' })
    const activeRow = within(list).getByRole('listitem', { name: /active-event/ })
    const completedRow = within(list).getByRole('listitem', { name: /completed-event/ })

    expect(activeRow.querySelector('.capture-group-list-eyebrow')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '事件记录' })).not.toBeInTheDocument()
    expect(screen.getByText('共 2 个配药事件')).toBeInTheDocument()
    expect(activeRow).toHaveTextContent('下一步')
    expect(activeRow).toHaveTextContent('提交配药动作修订')
    expect(activeRow).toHaveTextContent('1/1 机位已收')
    expect(activeRow).toHaveTextContent('事件质量 合格')
    expect(activeRow).toHaveTextContent('机位质量 1 合格')
    expect(activeRow.querySelectorAll('.status-indicator[data-tone="success"]')).toHaveLength(0)

    const processLink = within(activeRow).getByRole('link', { name: /处理事件/ })
    expect(processLink).toHaveTextContent('处理事件')
    expect(processLink).not.toHaveTextContent('提交配药动作修订')
    expect(completedRow).toHaveTextContent('当前状态')
    expect(completedRow).toHaveTextContent('事件清洗已通过')
    expect(within(completedRow).getByRole('link', { name: /查看事件/ })).toHaveTextContent(
      '查看事件',
    )

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
      key: 'receiving-event',
    })
    const incompleteGroup = captureGroupFixture('60000000-0000-4000-8000-000000000004', {
      expected: [1, 2, 4],
      received: [1, 4],
      receipt: 'INCOMPLETE',
      key: 'incomplete-event',
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

    const list = await screen.findByRole('list', { name: '配药事件列表' })
    const receivingRow = within(list).getByRole('listitem', { name: /receiving-event/ })
    const incompleteRow = within(list).getByRole('listitem', { name: /incomplete-event/ })

    expect(receivingRow).toHaveTextContent('等待接收完成后进行事件质量判断')
    expect(receivingRow).toHaveTextContent('1/2 机位已收 · 接收中')
    expect(receivingRow).toHaveTextContent('缺失：机位 2')
    expect(receivingRow).toHaveTextContent('事件质量 待判断')
    expect(receivingRow).toHaveTextContent('机位质量 待判断')
    expect(within(receivingRow).getByRole('link', { name: /查看事件/ })).toBeInTheDocument()

    expect(incompleteRow).toHaveTextContent('任务状态按当前权限不可见')
    expect(incompleteRow).toHaveTextContent('2/3 机位已收 · 接收完成 · 有缺失')
    expect(incompleteRow).toHaveTextContent('缺失：机位 2')
    expect(incompleteRow).toHaveTextContent('事件质量 合格（排除部分机位）')
    expect(incompleteRow).toHaveTextContent('机位质量 2 合格 · 1 待判断')
    expect(within(incompleteRow).getByRole('link', { name: /查看事件/ })).toBeInTheDocument()
    expect(screen.getByText('部分任务状态不可直接确认')).toBeInTheDocument()
  })

  it.each([
    {
      label: 'forbidden',
      error: { status: 403, errorCode: 'CAPTURE_GROUP_QUALITY_ACCESS_DENIED' },
      current: '事件质量按当前权限不可见',
      fact: '事件质量 无权查看',
    },
    {
      label: 'unavailable',
      error: { status: 503, errorCode: 'CAPTURE_GROUP_QUALITY_REQUEST_FAILED' },
      current: '事件质量暂不可用，请刷新后重试',
      fact: '事件质量 暂不可用',
    },
  ])('does not turn $label quality into a success state', async ({ error, current, fact }) => {
    const group = captureGroupFixture('60000000-0000-4000-8000-000000000005', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: `${error.status}-quality-event`,
    })
    stubGroupList([group], [], { qualityListError: error })
    renderView()

    const row = await screen.findByRole('listitem', { name: /quality-event/ })
    expect(row).toHaveTextContent(current)
    expect(row).toHaveTextContent(fact)
    expect(row).toHaveTextContent('机位质量 ' + (error.status === 403 ? '无权查看' : '暂不可用'))
    expect(row).not.toHaveTextContent('事件质量 合格')
    expect(within(row).getByRole('link', { name: /查看事件/ })).toBeInTheDocument()
  })

  it('does not invent task actions when task state is unavailable', async () => {
    const group = captureGroupFixture('60000000-0000-4000-8000-000000000006', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'task-unavailable-event',
    })
    stubGroupList([group], [], {
      taskListError: true,
      qualityByGroup: { [group.capture_group_id]: qualityFixture(group, 'QUALIFIED') },
    })
    renderView()

    const row = await screen.findByRole('listitem', { name: /task-unavailable-event/ })
    expect(row).toHaveTextContent('任务状态暂不可用，请刷新后重试')
    expect(within(row).getByRole('link', { name: /查看事件/ })).toBeInTheDocument()
    expect(screen.getByText('部分任务状态不可直接确认')).toBeInTheDocument()
  })

  it('appends the next cursor page without changing the event navigation contract', async () => {
    const firstGroup = captureGroupFixture('60000000-0000-4000-8000-000000000007', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'first-page-event',
    })
    const secondGroup = captureGroupFixture('60000000-0000-4000-8000-000000000008', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'second-page-event',
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

    expect(await screen.findByText('已显示 1 个配药事件')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '加载更多' }))

    await waitFor(() => expect(screen.getByText('共 2 个配药事件')).toBeInTheDocument())
    expect(screen.getByRole('listitem', { name: /second-page-event/ })).toHaveTextContent(
      '等待人工复核',
    )
    expect(screen.queryByRole('button', { name: '加载更多' })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('cursor=cursor-2'))).toBe(
      true,
    )
  })
})

function renderView() {
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
    [paths.captureGroups(projectId)],
  )
}
