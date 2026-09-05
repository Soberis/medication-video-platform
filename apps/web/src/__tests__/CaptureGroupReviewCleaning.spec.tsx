import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'

import { renderRoute } from '@/test/render'
import CaptureGroupDetailView from '@/views/CaptureGroupDetailView'

import {
  approvedGroupTask,
  captureGroupCleaningFixture,
  captureGroupFixture,
  groupId,
  projectId,
  qualityFixture,
  rejectedGroupTask,
  stubGroupDetail,
  submittedGroupTask,
} from './capture-group-fixtures'

afterEach(() => vi.unstubAllGlobals())

describe('CaptureGroup Review and event cleaning', () => {
  it('lets an authorized reviewer approve a submitted Revision and shows server cleaning', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2, 3],
      received: [1, 2, 3],
      receipt: 'COMPLETE',
    })
    const viewSet = qualityFixture(group, 'QUALIFIED').confirmed_view_set
    const submitted = submittedGroupTask(group, { confirmedViewSet: viewSet })
    const completed = approvedGroupTask(group, { confirmedViewSet: viewSet })
    const fetchMock = stubGroupDetail(group, {
      task: submitted,
      taskResponses: [submitted, completed],
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()
    renderCaptureGroup()

    await user.click(await screen.findByRole('button', { name: '通过复核' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '通过复核' }))

    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([input, init]) =>
            String(input).endsWith(
              `/annotation-revisions/${submitted.annotation_revision?.annotation_revision_id}/review`,
            ) && init?.method === 'POST',
        ),
      ).toBe(true)
    })
    const reviewCall = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith(
          `/annotation-revisions/${submitted.annotation_revision?.annotation_revision_id}/review`,
        ) && init?.method === 'POST',
    )
    expect(JSON.parse(String(reviewCall?.[1]?.body))).toEqual({ decision: 'APPROVED' })
    await user.click(await screen.findByRole('button', { name: '查看复核详情' }))
    expect(await screen.findByText('标注检查通过')).toBeInTheDocument()
    expect(screen.getByText('当前规则检查通过')).toBeInTheDocument()
    expect(screen.getByText('capture-group-event-eligibility')).toBeInTheDocument()
    expect(screen.getByText('REVIEW_APPROVED')).toBeInTheDocument()
  })

  it('does not expose Review actions when the server denies reviewer permission', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const submitted = submittedGroupTask(group, { canReview: false })
    stubGroupDetail(group, {
      task: submitted,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    renderCaptureGroup()

    expect(await screen.findByText('已有人工标签任务')).toBeInTheDocument()
    expect(screen.getByText('已提交人工修订，等待有权限的复核人员处理。')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '通过复核' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '退回修改' })).not.toBeInTheDocument()
  })

  it('returns the same task to rework while keeping the rejected Revision visible', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const viewSet = qualityFixture(group, 'QUALIFIED').confirmed_view_set
    const submitted = submittedGroupTask(group, { confirmedViewSet: viewSet })
    const reworked = rejectedGroupTask(group, {
      confirmedViewSet: viewSet,
      reviewNote: '请补充动作结束位置。',
    })
    const fetchMock = stubGroupDetail(group, {
      task: submitted,
      taskResponses: [submitted, reworked],
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()
    renderCaptureGroup()

    await user.click(await screen.findByRole('button', { name: '退回修改' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(
      within(dialog).getByRole('textbox', { name: '退回修改说明（可选）' }),
      '请补充动作结束位置。',
    )
    await user.click(within(dialog).getByRole('button', { name: '退回修改' }))

    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([input, init]) =>
            String(input).endsWith(
              `/annotation-revisions/${submitted.annotation_revision?.annotation_revision_id}/review`,
            ) && init?.method === 'POST',
        ),
      ).toBe(true)
    })
    const reviewCall = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith(
          `/annotation-revisions/${submitted.annotation_revision?.annotation_revision_id}/review`,
        ) && init?.method === 'POST',
    )
    expect(JSON.parse(String(reviewCall?.[1]?.body))).toEqual({
      decision: 'REJECTED',
      review_note: '请补充动作结束位置。',
    })
    expect((await screen.findAllByText('待修改')).length).toBeGreaterThan(0)
    expect(screen.getAllByText('第 1 次正式修订').length).toBeGreaterThan(0)
    expect(screen.getAllByText('复核 · 已退回').length).toBeGreaterThan(0)
    expect(screen.getAllByText('请补充动作结束位置。').length).toBeGreaterThan(0)
    expect(screen.queryByText('标注检查通过')).not.toBeInTheDocument()
  })

  it('surfaces a server rejection when Group Quality was downgraded before Review', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const submitted = submittedGroupTask(group)
    const fetchMock = stubGroupDetail(group, {
      task: submitted,
      quality: qualityFixture(group, 'NEEDS_REVIEW'),
      candidateStatus: 'NO_HAND_DETECTED',
      reviewError: {
        status: 409,
        body: { error_code: 'ANNOTATION_REVISION_NO_LONGER_QUALIFIED' },
      },
    })
    const user = userEvent.setup()
    renderCaptureGroup()

    await user.click(await screen.findByRole('button', { name: '通过复核' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '通过复核' }))

    expect(await screen.findByText('该采集记录当前不满足标注复核条件。')).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.filter(
        ([input, init]) =>
          String(input).endsWith(
            '/annotation-revisions/' +
              submitted.annotation_revision?.annotation_revision_id +
              '/review',
          ) && init?.method === 'POST',
      ),
    ).toHaveLength(1)
    expect(screen.queryByText('标注检查通过')).not.toBeInTheDocument()
  })

  it('keeps missing and rejected views visible and gives supporting views no action timestamps', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2, 3],
      received: [1, 3],
      receipt: 'COMPLETE',
    })
    const quality = qualityFixture(group, 'QUALIFIED_WITH_EXCLUSIONS', { rejectedCamera: 2 })
    const viewSet = quality.confirmed_view_set
    const completed = approvedGroupTask(group, { confirmedViewSet: viewSet })
    stubGroupDetail(group, {
      task: completed,
      quality,
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()
    renderCaptureGroup()

    await user.click(await screen.findByRole('button', { name: '查看复核详情' }))

    const rejectedViews = await screen.findByRole('list', { name: '拒绝 / 缺失机位' })
    expect(within(rejectedViews).getByText('机位 2')).toBeInTheDocument()
    expect(within(rejectedViews).getByText(/机位缺失/)).toBeInTheDocument()
    expect(
      within(rejectedViews).queryByText(/MISSING_VIEW|VIEW_NOT_QUALIFIED/),
    ).not.toBeInTheDocument()

    const eventEvidence = screen.getByRole('list', { name: '采集记录级参考视图证据' })
    const supportingItem = within(eventEvidence).getByText('机位 3').closest('li')
    expect(supportingItem).not.toBeNull()
    expect(
      within(supportingItem as HTMLElement).getByText('参考视图 · 仅供采集记录级参考'),
    ).toBeInTheDocument()
    expect(within(supportingItem as HTMLElement).queryByText(/100|900/)).not.toBeInTheDocument()
    expect(screen.getByText('主视图正式时间轴')).toBeInTheDocument()
    expect(screen.queryByText(/first_timestamp_ms|last_timestamp_ms/)).not.toBeInTheDocument()
  })

  it('offers only the authorized server-side event cleaning recheck', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const completed = approvedGroupTask(group, { canRecheck: true })
    const cleaning = captureGroupCleaningFixture(group)
    const fetchMock = stubGroupDetail(group, {
      task: completed,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
      cleaningRecheckResponse: cleaning,
    })
    const user = userEvent.setup()
    renderCaptureGroup()

    await user.click(await screen.findByRole('button', { name: '查看复核详情' }))
    await user.click(await screen.findByRole('button', { name: '重新执行标注检查' }))

    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([input, init]) =>
            String(input).endsWith('/capture-group-cleaning-rechecks') && init?.method === 'POST',
        ),
      ).toBe(true)
    })
    expect(screen.queryByRole('button', { name: '标注检查通过' })).not.toBeInTheDocument()
  })
})

function renderCaptureGroup() {
  renderRoute(<CaptureGroupDetailView />, {
    path: '/projects/:projectId/capture-groups/:captureGroupId',
    route: `/projects/${projectId}/capture-groups/${groupId}`,
  })
}
