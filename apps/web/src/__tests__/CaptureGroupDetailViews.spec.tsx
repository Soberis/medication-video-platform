import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { renderRoute } from '@/test/render'
import CaptureGroupDetailView from '@/views/CaptureGroupDetailView'

import {
  captureGroupFixture,
  clipIds,
  groupId,
  groupTask,
  projectId,
  qualityFixture,
  stubGroupDetail,
} from './capture-group-fixtures'

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
    expect(screen.getAllByText('事件质量').length).toBeGreaterThan(0)
    expect(screen.getAllByText('拍摄时间').length).toBeGreaterThan(0)
    expect(screen.getByText('机位质量')).toBeInTheDocument()
    expect(screen.getAllByText('手部检测').length).toBeGreaterThan(0)
    expect(await screen.findByText('未检测到手部（NO_HAND_DETECTED）')).toBeInTheDocument()
    expect(screen.getByText(/这是检测事实/)).toBeInTheDocument()
    expect(screen.getByText(/服务端结论/)).toBeInTheDocument()
    expect(screen.queryByText('CaptureGroup 多机位')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('视频预览')).not.toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${clipIds[0]}/playback-grants`,
      expect.anything(),
    )
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
    expect(screen.getByText('缺失机位：机位 3')).toBeInTheDocument()
    expect(screen.getAllByText('合格 · 排除部分机位').length).toBeGreaterThan(0)
    expect(await screen.findByText('检测失败')).toBeInTheDocument()
    expect(await screen.findByText(/手部检测失败/)).toBeInTheDocument()
    expect(screen.getAllByText(/同步需关注 · 仅供参考/).length).toBeGreaterThan(0)
    const rejectedRow = screen.getByRole('listitem', { name: /机位 5/ })
    expect(rejectedRow).toHaveTextContent('机位质量 · 不合格')
    expect(rejectedRow).toHaveTextContent('不合格')
    expect(screen.getAllByText(/REJECTED|不合格/).length).toBeGreaterThan(0)

    const cameraTwoRow = screen.getByRole('listitem', { name: /机位 2/ })
    await user.click(within(cameraTwoRow).getByRole('button', { name: /机位 2/ }))
    expect(await screen.findByRole('heading', { level: 2, name: /机位 2/ })).toBeInTheDocument()
    expect(await screen.findByText(/未检测到手部；这是检测事实/)).toBeInTheDocument()
    expect(screen.getAllByText(/参考视图/).length).toBeGreaterThan(0)
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${clipIds[1]}/media-report`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })
})
