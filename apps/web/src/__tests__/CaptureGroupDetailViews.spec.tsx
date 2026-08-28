import { screen, within } from '@testing-library/react'
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

    expect(await screen.findByText(/唯一 CaptureGroup 任务状态按权限不可见/)).toBeInTheDocument()
    expect(
      screen.queryByText('Group Quality 通过后，服务端会自动创建唯一 CaptureGroup 任务。'),
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
    expect(screen.getByText('应 1 路 · 已收 1 路 · 缺失 0 路')).toBeInTheDocument()
    expect(screen.getByText('NO_HAND_DETECTED')).toBeInTheDocument()
    expect(screen.getByText(/这是正常候选事实/)).toBeInTheDocument()
    expect(screen.getByText(/服务器不计算组质量|服务端结论/)).toBeInTheDocument()
    expect(screen.queryByLabelText('视频预览')).not.toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${clipIds[0]}/playback-grants`,
      expect.anything(),
    )
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

    expect((await screen.findAllByText('Cam 3')).length).toBeGreaterThan(0)
    expect(screen.getByText('缺失相机：Cam 3')).toBeInTheDocument()
    expect(screen.getAllByText('合格 · 排除部分视图').length).toBeGreaterThan(0)
    expect(screen.getByText('AI FAILED')).toBeInTheDocument()
    expect(screen.getByText(/MediaPipe Candidate 失败/)).toBeInTheDocument()
    expect(screen.getAllByText(/同步需关注 · reference-only/).length).toBeGreaterThan(0)
    const rejectedRow = screen.getByRole('listitem', { name: /相机 5/ })
    expect(rejectedRow).toHaveTextContent('人工质量 · 不合格')
    expect(rejectedRow).toHaveTextContent('不合格')
    expect(screen.getAllByText(/REJECTED|不合格/).length).toBeGreaterThan(0)

    const cameraTwoRow = screen.getByRole('listitem', { name: /相机 2/ })
    await user.click(within(cameraTwoRow).getByRole('button', { name: /Cam 2/ }))
    expect(await screen.findByRole('heading', { level: 2, name: /Cam 2/ })).toBeInTheDocument()
    expect(await screen.findByText(/NO_HAND_DETECTED：未检测到手部/)).toBeInTheDocument()
    expect(screen.getAllByText(/Supporting View/).length).toBeGreaterThan(0)
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${clipIds[1]}/media-report`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })
})
