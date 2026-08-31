import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { renderRoute } from '@/test/render'
import CaptureGroupDetailView from '@/views/CaptureGroupDetailView'
import type { QualityReview } from '@/api/quality'

import {
  captureGroupFixture,
  clipIds,
  groupId,
  groupTask,
  projectId,
  qualityFixture,
  stubGroupDetail,
} from './capture-group-fixtures'

function viewQualityRecord(
  clipAssetId: string,
  decision: QualityReview['decision'],
  version = 0,
): QualityReview {
  return {
    quality_review_id: version ? `quality-${clipAssetId}` : null,
    project_id: projectId,
    clip_asset_id: clipAssetId,
    decision,
    reason_code: version ? 'MEETS_REQUIREMENTS' : null,
    reviewed_by: version ? 'reviewer' : null,
    reviewed_at: version ? '2026-08-20T01:02:00Z' : null,
    version,
  }
}

afterEach(() => vi.unstubAllGlobals())

describe('CaptureGroup detail views', () => {
  it('focuses the requested Review section from a Group workflow deep link', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const completedTask = groupTask(groupId, true, { revision: true })
    const reviewableTask = {
      ...completedTask,
      status: 'SUBMITTED' as const,
      can_submit: false,
      can_review: true,
      can_recheck_post_cleaning: false,
      annotation_revision: {
        ...completedTask.annotation_revision!,
        review: null,
        post_cleaning_result: null,
      },
    }
    stubGroupDetail(group, {
      task: reviewableTask,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const scrollIntoView = vi.fn()
    const originalScrollIntoView = HTMLElement.prototype.scrollIntoView
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: scrollIntoView,
    })

    try {
      const { unmount } = renderRoute(<CaptureGroupDetailView />, {
        path: '/projects/:projectId/capture-groups/:captureGroupId',
        route: `/projects/${projectId}/capture-groups/${groupId}?focus=review`,
      })
      await waitFor(() =>
        expect(document.activeElement).toHaveClass('capture-group-workflow-focus-target'),
      )
      const section = document.activeElement as HTMLElement

      expect(['capture-group-review-cleaning', 'capture-group-review-cleaning-context']).toContain(
        section.id,
      )
      expect(section).toHaveAttribute('tabindex', '-1')
      expect(scrollIntoView).toHaveBeenCalledWith({ block: 'start', behavior: 'auto' })
      unmount()
    } finally {
      Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
        configurable: true,
        value: originalScrollIntoView,
      })
    }
  })

  it('explains permission-limited task context in the group detail view', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'detail-permission-limited-task',
    })
    stubGroupDetail(group, {
      task: null,
      taskScope: 'ASSIGNEE',
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    expect(await screen.findByText(/唯一配药事件任务状态按权限不可见/)).toBeInTheDocument()
    expect(
      screen.queryByText('事件质量通过后，服务端会自动创建唯一配药事件任务。'),
    ).not.toBeInTheDocument()
  })

  it('keeps a single N=1 group and distinguishes receipt from media and AI facts', async () => {
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

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    expect(await screen.findByRole('heading', { level: 1, name: 'capture-1' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '配药事件' })).toBeInTheDocument()
    expect(screen.getByText('应 1 个机位 · 已收 1 个机位 · 缺失 0 个机位')).toBeInTheDocument()
    expect(screen.getByText('接收状态')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: '机位 1' })).toBeInTheDocument()
    expect(screen.queryByLabelText('配药事件机位选择')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: '当前动作' })).toBeInTheDocument()
    expect(screen.getByRole('list', { name: '配药事件处理流程' })).toBeInTheDocument()
    expect(document.querySelectorAll('h1')).toHaveLength(1)
    expect(screen.getAllByText('事件质量').length).toBeGreaterThan(0)
    expect(screen.getAllByText('拍摄时间').length).toBeGreaterThan(0)
    expect(screen.getByText('机位质量')).toBeInTheDocument()
    expect(screen.getAllByText('手部检测').length).toBeGreaterThan(0)
    expect(await screen.findByText('未检测到手部')).toBeInTheDocument()
    expect(screen.getByText(/这是检测事实/)).toBeInTheDocument()
    expect(screen.getByText(/人工记录的事件质量结论/)).toBeInTheDocument()
    expect(screen.queryByText(/NO_HAND_DETECTED/)).not.toBeInTheDocument()
    expect(screen.queryByText('CaptureGroup 多机位')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('视频预览')).not.toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${clipIds[0]}/playback-grants`,
      expect.anything(),
    )
  })

  it('keeps a completed workflow compact and leaves full event identifiers in progressive details', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    stubGroupDetail(group, {
      task: groupTask(groupId, true, { revision: true }),
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    const user = userEvent.setup()
    await screen.findByRole('button', { name: '查看结果' })
    expect(screen.queryByRole('list', { name: '配药事件处理流程' })).not.toBeInTheDocument()
    const detailToggle = screen.getByRole('button', { name: '查看复核与训练资格详情' })
    expect(detailToggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('已确认输入与机位集合')).not.toBeInTheDocument()
    await user.click(detailToggle)
    expect(screen.getByRole('button', { name: '收起复核与训练资格详情' })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    expect(screen.getByText('已确认输入与机位集合')).toBeInTheDocument()

    const inspector = screen.getByRole('complementary', { name: '事实、历史与审计' })
    const receiptDetails = within(inspector).getByText('接收与采集事实').closest('details')
    if (!receiptDetails) throw new Error('Receipt progressive detail was not mounted.')
    await user.click(within(receiptDetails).getByText('接收与采集事实'))
    expect(within(receiptDetails).getByText(group.capture_group_id)).toBeInTheDocument()
  })

  it('uses a compact camera selector for N=2 and supports keyboard switching', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    stubGroupDetail(group, {
      task: null,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    const selector = await screen.findByLabelText('配药事件机位选择')
    expect(screen.getAllByLabelText('配药事件机位选择')).toHaveLength(1)
    expect(within(selector).getAllByRole('button')).toHaveLength(2)
    const cameraTwoButton = within(selector).getByRole('button', { name: /机位 2/ })
    cameraTwoButton.focus()
    await user.keyboard('{Enter}')

    expect(await screen.findByRole('heading', { level: 2, name: '机位 2' })).toBeInTheDocument()
    expect(cameraTwoButton).toHaveAttribute('aria-pressed', 'true')
    expect(cameraTwoButton).toHaveAttribute('aria-current', 'true')
  })

  it('focuses the first unresolved view and advances only after a successful quality refresh', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const viewQualityByCamera: Record<number, QualityReview> = {
      1: viewQualityRecord(clipIds[0]!, 'UNREVIEWED'),
      2: viewQualityRecord(clipIds[1]!, 'UNREVIEWED'),
    }
    stubGroupDetail(group, {
      task: null,
      quality: qualityFixture(group, 'UNREVIEWED'),
      candidateStatus: 'NO_HAND_DETECTED',
      viewQualityByCamera,
      viewQualityAfterSaveByCamera: {
        1: viewQualityRecord(clipIds[0]!, 'QUALIFIED', 1),
      },
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    expect(await screen.findByRole('heading', { level: 2, name: '机位 1' })).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: '保存质量结论' }))

    expect(await screen.findByRole('heading', { level: 2, name: '机位 2' })).toBeInTheDocument()
  })

  it('keeps technical and provenance facts behind progressive details', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    stubGroupDetail(group, {
      task: null,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'FAILED',
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    const inspector = await screen.findByRole('complementary', {
      name: '事实、历史与审计',
    })
    const receiptSummary = within(inspector).getByText('接收与采集事实')
    const receiptDetails = receiptSummary.closest('details')
    if (!receiptDetails) throw new Error('Receipt progressive detail was not mounted.')
    expect(receiptDetails).not.toHaveAttribute('open')
    await user.click(receiptSummary)
    expect(receiptDetails).toHaveAttribute('open')
    expect(within(receiptDetails).getByText('配药事件 ID')).toBeInTheDocument()
    expect(within(receiptDetails).getByText('服务端接收时间')).toBeInTheDocument()

    const focusedDetails = screen.getByText('查看机位技术与辅助证据').closest('details')
    if (!focusedDetails) throw new Error('Focused progressive detail was not mounted.')
    expect(focusedDetails).not.toHaveAttribute('open')
    await user.click(screen.getByText('查看机位技术与辅助证据'))
    expect(focusedDetails).toHaveAttribute('open')
    expect(
      within(focusedDetails).getByRole('heading', { level: 2, name: '手部检测' }),
    ).toBeInTheDocument()
    expect(
      within(focusedDetails).getByRole('heading', { level: 3, name: '媒体检测报告' }),
    ).toBeInTheDocument()
  })

  it('requires explicit confirmation before ending receipt', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'RECEIVING',
    })
    const fetchMock = stubGroupDetail(group, {
      task: null,
      quality: qualityFixture(group, 'UNREVIEWED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    const finalizeButton = await screen.findByRole('button', { name: '结束接收' })
    await user.click(finalizeButton)

    const confirmationTitle = await screen.findByText('结束本次接收？')
    const dialog = confirmationTitle.closest('[role="dialog"]') as HTMLElement | null
    if (!dialog) throw new Error('Receipt confirmation dialog was not mounted.')
    expect(within(dialog).getByText('结束本次接收？')).toBeInTheDocument()
    expect(dialog).toHaveTextContent(
      '系统将根据当前已收到的机位形成完整或有缺失的接收结果，并进入后续事件质量判断。',
    )
    expect(screen.getByText('接收中')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/capture-groups/${groupId}/receipt/finalize`,
      expect.objectContaining({ method: 'POST' }),
    )

    await user.click(within(dialog).getByRole('button', { name: '取消' }))
    expect(fetchMock).not.toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/capture-groups/${groupId}/receipt/finalize`,
      expect.objectContaining({ method: 'POST' }),
    )
    await waitFor(() => expect(document.activeElement).toBe(finalizeButton))
  })

  it('confirms receipt once, disables duplicates, and refreshes authoritative facts', async () => {
    const receivingGroup = captureGroupFixture(groupId, {
      expected: [1, 2, 3, 4, 5],
      received: [1, 2, 3, 4, 5],
      receipt: 'RECEIVING',
    })
    const completedGroup = captureGroupFixture(groupId, {
      expected: [1, 2, 3, 4, 5],
      received: [1, 2, 3, 4, 5],
      receipt: 'COMPLETE',
    })
    let releaseFinalize: () => void = () => undefined
    const finalizeGate = new Promise<void>((resolve) => {
      releaseFinalize = () => resolve()
    })
    const fetchMock = stubGroupDetail(receivingGroup, {
      task: null,
      groupResponses: [receivingGroup, completedGroup],
      receiptFinalizeResponse: completedGroup,
      receiptFinalizeGate: finalizeGate,
      quality: qualityFixture(receivingGroup, 'UNREVIEWED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    await user.click(await screen.findByRole('button', { name: '结束接收' }))
    const confirmationTitle = await screen.findByText('结束本次接收？')
    const dialog = confirmationTitle.closest('[role="dialog"]') as HTMLElement | null
    if (!dialog) throw new Error('Receipt confirmation dialog was not mounted.')
    const confirmButton = within(dialog).getByRole('button', { name: '结束接收' })
    await user.click(confirmButton)

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/capture-groups/${groupId}/receipt/finalize`,
        expect.objectContaining({ method: 'POST' }),
      ),
    )
    expect(confirmButton).toBeDisabled()
    await user.click(confirmButton)
    expect(
      fetchMock.mock.calls.filter(
        ([input, init]) =>
          String(input).endsWith('/receipt/finalize') && (init as RequestInit)?.method === 'POST',
      ),
    ).toHaveLength(1)

    releaseFinalize()
    await waitFor(() => expect(screen.getByText('已完整接收')).toBeInTheDocument())
    expect(
      within(screen.getByRole('toolbar', { name: '页面操作' })).queryByRole('button', {
        name: '结束接收',
      }),
    ).not.toBeInTheDocument()
    expect(
      fetchMock.mock.calls.filter(
        ([input, init]) =>
          String(input).endsWith('/receipt/finalize') && (init as RequestInit)?.method === 'POST',
      ),
    ).toHaveLength(1)
  })

  it('keeps receipt confirmation open when finalization fails', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'RECEIVING',
    })
    const fetchMock = stubGroupDetail(group, {
      task: null,
      receiptFinalizeError: { status: 500 },
      quality: qualityFixture(group, 'UNREVIEWED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    await user.click(await screen.findByRole('button', { name: '结束接收' }))
    const confirmationTitle = await screen.findByText('结束本次接收？')
    const dialog = confirmationTitle.closest('[role="dialog"]') as HTMLElement | null
    if (!dialog) throw new Error('Receipt confirmation dialog was not mounted.')

    await user.click(within(dialog).getByRole('button', { name: '结束接收' }))

    await waitFor(() =>
      expect(screen.getByText('配药事件操作暂未完成，请稍后重试。')).toBeInTheDocument(),
    )
    expect(screen.getByText('结束本次接收？')).toBeInTheDocument()
    expect(screen.getByText('接收中')).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.filter(
        ([input, init]) =>
          String(input).endsWith('/receipt/finalize') && (init as RequestInit)?.method === 'POST',
      ),
    ).toHaveLength(1)
  })

  it('keeps a group-quality request failure unavailable instead of inventing UNREVIEWED', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'detail-quality-unavailable',
    })
    stubGroupDetail(group, {
      task: null,
      quality: qualityFixture(group, 'QUALIFIED'),
      qualityError: { status: 500 },
      candidateStatus: 'NO_HAND_DETECTED',
    })

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    expect(await screen.findAllByText('暂不可用')).not.toHaveLength(0)
    expect(screen.getAllByText('事件质量暂不可用').length).toBeGreaterThan(0)
    expect(screen.getByText('事件质量暂不可用，请刷新后重试')).toBeInTheDocument()
    expect(screen.queryByText('待判断')).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: '事件质量结论' })).not.toBeInTheDocument()
  })

  it('shows detail-quality permission denial only when the contract identifies 403', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'detail-quality-forbidden',
    })
    stubGroupDetail(group, {
      task: null,
      quality: qualityFixture(group, 'QUALIFIED'),
      qualityError: { status: 403, errorCode: 'CAPTURE_GROUP_QUALITY_ACCESS_DENIED' },
      candidateStatus: 'NO_HAND_DETECTED',
    })

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    expect(await screen.findAllByText('无权查看')).not.toHaveLength(0)
    expect(screen.getAllByText('事件质量无权查看').length).toBeGreaterThan(0)
    expect(screen.getByText('事件质量按当前权限不可见')).toBeInTheDocument()
    expect(screen.queryByText('待判断')).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: '事件质量结论' })).not.toBeInTheDocument()
  })

  it('shows missing and rejected views, warns on sync, switches one main player context, and keeps AI failure non-blocking', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2, 3, 4, 5],
      received: [1, 2, 4, 5],
      receipt: 'INCOMPLETE',
    })
    const mediaFailedStream = group.streams.find((stream) => stream.camera_number === 4)
    if (!mediaFailedStream) throw new Error('Expected camera 4 stream fixture.')
    mediaFailedStream.stream_status = 'INGEST_FAILED'
    const fetchMock = stubGroupDetail(group, {
      task: groupTask(groupId, true),
      quality: qualityFixture(group, 'QUALIFIED_WITH_EXCLUSIONS', { rejectedCamera: 5 }),
      candidateStatus: 'FAILED',
      candidateStatusByCamera: { 2: 'NO_HAND_DETECTED' },
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    expect((await screen.findAllByText('机位 3')).length).toBeGreaterThan(0)
    expect(screen.getAllByText('合格（排除部分机位）').length).toBeGreaterThan(0)
    expect(await screen.findByText('检测失败')).toBeInTheDocument()
    expect(await screen.findByText(/手部检测失败/)).toBeInTheDocument()
    expect(screen.getAllByText(/同步需关注 · 仅供参考/).length).toBeGreaterThan(0)
    const selector = screen.getByLabelText('配药事件机位选择')
    expect(screen.getAllByLabelText('配药事件机位选择')).toHaveLength(1)
    expect(within(selector).getAllByRole('button')).toHaveLength(5)
    expect(
      within(selector)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual([
      expect.stringContaining('机位 1'),
      expect.stringContaining('机位 2'),
      expect.stringContaining('机位 3'),
      expect.stringContaining('机位 4'),
      expect.stringContaining('机位 5'),
    ])
    expect(within(selector).getByRole('button', { name: /机位 3.*缺失/ })).toBeDisabled()
    const rejectedButton = within(selector).getByRole('button', {
      name: /机位 5.*机位质量 · 不合格/,
    })
    expect(rejectedButton).not.toBeDisabled()
    expect(rejectedButton).toHaveTextContent('机位质量 · 不合格')
    const mediaFailedButton = within(selector).getByRole('button', {
      name: /机位 4.*媒体不可用/,
    })
    expect(mediaFailedButton).not.toBeDisabled()
    expect(screen.getAllByText(/不合格/).length).toBeGreaterThan(0)

    await user.click(within(selector).getByRole('button', { name: /机位 2/ }))
    expect(await screen.findByRole('heading', { level: 2, name: /机位 2/ })).toBeInTheDocument()
    expect(await screen.findByText(/未检测到手部；这是检测事实/)).toBeInTheDocument()
    expect(screen.getAllByText(/参考视图/).length).toBeGreaterThan(0)
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${clipIds[1]}/media-report`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })
})
