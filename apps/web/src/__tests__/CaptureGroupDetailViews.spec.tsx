import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  projectId,
  groupId,
  captureGroupFixture,
  groupTask,
  qualityFixture,
  stubGroupDetail,
} from './capture-group-fixtures'
import { paths } from '@/app/paths'
import { renderRoute, renderRouter } from '@/test/render'
import CaptureGroupDetailView from '@/views/CaptureGroupDetailView'

afterEach(() => vi.unstubAllGlobals())

describe('CaptureGroup detail views', () => {
  it('discovers the new primary video job after the previous job reached a terminal state', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const options = {
      task: groupTask(groupId, true),
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'UNKNOWN' as const,
      actionCandidateJob: { status: 'SUCCEEDED', clip_asset_id: group.streams[0]!.clip_asset_id },
    }
    stubGroupDetail(group, options)
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })
    await screen.findByText('AI 已完成 · 可直接标注', {}, { timeout: 4000 })
    options.task = {
      ...options.task,
      primary_clip_asset_id: group.streams[1]!.clip_asset_id,
      primary_camera_number: 2,
    }
    options.actionCandidateJob = {
      status: 'RUNNING',
      clip_asset_id: group.streams[1]!.clip_asset_id,
    }
    await userEvent.setup().click(screen.getByRole('button', { name: /^刷新$/ }))
    expect(await screen.findByText('AI 分析中')).toBeInTheDocument()
  })

  it('waits for background analysis without assigning a task or writing quality', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const fetchMock = stubGroupDetail(group, {
      task: null,
      quality: qualityFixture(group, 'UNREVIEWED'),
      candidateStatus: 'UNKNOWN',
    })
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })
    expect(await screen.findByText('等待 AI 自动分析')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '启动自动标注' })).not.toBeInTheDocument()
    expect(
      fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST' || init?.method === 'PUT'),
    ).toHaveLength(0)
  })

  it('retries a failed action job without creating a human task', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const fetchMock = stubGroupDetail(group, {
      task: null,
      quality: qualityFixture(group, 'UNREVIEWED'),
      candidateStatus: 'NO_HAND_DETECTED',
      actionCandidateJob: { job_id: 'synthetic-action-job', status: 'FAILED' },
    })
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })
    await userEvent
      .setup()
      .click(await screen.findByRole('button', { name: '重试动作分析' }, { timeout: 5000 }))
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          ([url, init]) =>
            String(url).endsWith('/action-candidate-jobs') && init?.method === 'POST',
        ),
      ).toBe(true),
    )
    const writes = fetchMock.mock.calls.filter(
      ([, init]) => init?.method === 'POST' || init?.method === 'PUT',
    )
    expect(writes.every(([url]) => String(url).endsWith('/action-candidate-jobs'))).toBe(true)
  })

  it('keeps the default detail path focused on receipt, media and machine evidence', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
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

    expect(await screen.findByRole('heading', { level: 1, name: 'capture-1' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '视频' })).toBeInTheDocument()
    expect(
      await screen.findByRole('heading', { level: 2, name: '视频', hidden: true }),
    ).toBeInTheDocument()
    expect((await screen.findByText('查看检测与媒体信息')).closest('details')).not.toHaveAttribute(
      'open',
    )
    expect(await screen.findByText('手部检测：已完成 · 未检测到手部')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: '动作标注' })).toBeVisible()
    await user.click(await screen.findByText('更多信息'))
    expect(await screen.findByText('接收与采集事实')).toBeVisible()
  })

  it('condenses the normal single-camera detail facts', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    group.device_id = 'PLATFORM_LOCAL'
    group.streams[0]!.camera_role = 'PLATFORM_LOCAL'
    group.streams[0]!.mount_position = 'PLATFORM_LOCAL'
    stubGroupDetail(group, {
      task: null,
      quality: qualityFixture(group, 'UNREVIEWED'),
      candidateStatus: 'UNKNOWN',
    })

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    await userEvent.setup().click(await screen.findByText('更多信息'))
    const inspector = screen.getByRole('complementary', { name: '记录详情' })
    expect(within(inspector).getByText('单机位（机位 1）')).toBeInTheDocument()
    expect(within(inspector).getAllByText('本地导入').length).toBeGreaterThan(0)
    expect(within(inspector).queryByText('应收机位')).not.toBeInTheDocument()
    expect(within(inspector).queryByText('缺失')).not.toBeInTheDocument()
    expect(within(inspector).queryByText('PLATFORM_LOCAL')).not.toBeInTheDocument()
  })

  it('shortens long input versions without changing the copied value', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const fullVersion = 'capture-group-view-set.v1:' + '4b30d8ed'.padEnd(64, 'a')
    const task = groupTask(groupId, true)
    task.group_input_version = fullVersion
    const quality = qualityFixture(group, 'QUALIFIED')
    quality.input_version = fullVersion
    stubGroupDetail(group, { task, quality, candidateStatus: 'UNKNOWN' })

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    await userEvent.setup().click(await screen.findByText('更多信息'))
    const compactVersion = 'v1:4b30d8ed…'
    expect(screen.getAllByText(compactVersion).length).toBeGreaterThan(0)
    expect(screen.queryByText(fullVersion)).not.toBeInTheDocument()
    await userEvent
      .setup()
      .click(screen.getAllByRole('button', { name: `复制版本 ${fullVersion}` })[0]!)
    expect(await navigator.clipboard.readText()).toBe(fullVersion)
  })

  it('lets a project administrator opt into the human annotation task after quality admission', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const assignedTask = groupTask(groupId, true)
    stubGroupDetail(group, {
      task: null,
      taskResponses: [null, assignedTask],
      taskAfterAssignment: assignedTask,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'HAND_DETECTED',
    })

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
      session: { username: 'reviewer', role: 'ADMIN' },
    })

    expect(screen.queryByRole('button', { name: '导出人工标注' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '导出数据' })).not.toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: '动作标注' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /提交(标注|修订)/ })).toBeInTheDocument()
  })

  it('renders an existing human-label task without making it the default machine path', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    stubGroupDetail(group, {
      task: groupTask(groupId, true),
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'HAND_DETECTED',
    })

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
    })

    expect(await screen.findByRole('heading', { level: 2, name: '动作标注' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /机位 1.*标注基准/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(screen.getByRole('button', { name: /提交(标注|修订)/ })).toBeInTheDocument()
  })

  it('keeps a missing machine result explicit while exposing the manual quality gate', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1],
      receipt: 'INCOMPLETE',
    })
    stubGroupDetail(group, {
      task: null,
      quality: qualityFixture(group, 'UNREVIEWED'),
      candidateStatus: 'FAILED',
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    const selector = await screen.findByRole('group', { name: '采集记录机位选择' })
    expect(within(selector).getByRole('button', { name: /机位 2/ })).toBeDisabled()
    const details = (await screen.findByText('查看检测与媒体信息')).closest('details')
    expect(details).not.toHaveAttribute('open')
    await user.click(await screen.findByText('查看检测与媒体信息'))
    expect(details).toHaveAttribute('open')
    expect(await screen.findByText('手部检测：检测任务失败')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: '动作标注' })).toBeInTheDocument()
    expect(await screen.findByText('等待 AI 自动分析')).toBeInTheDocument()
  })

  it('requires explicit confirmation before finalizing a receiving group', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1],
      receipt: 'RECEIVING',
    })
    const updatedGroup = { ...group, receipt_state: 'INCOMPLETE' as const }
    const fetchMock = stubGroupDetail(group, {
      task: null,
      groupResponses: [group, updatedGroup],
      quality: qualityFixture(group, 'UNREVIEWED'),
      candidateStatus: 'UNKNOWN',
      receiptFinalizeResponse: updatedGroup,
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    await user.click(await screen.findByRole('button', { name: '结束接收' }))
    expect(screen.getByRole('dialog')).toHaveTextContent('结束本次接收？')
    expect(screen.getByRole('dialog')).toHaveTextContent('媒体检查与 MediaPipe 检测会独立继续。')
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '结束接收' }))
    expect((await screen.findAllByText('接收完成 · 有缺失')).length).toBeGreaterThan(0)
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) => String(input).endsWith('/receipt/finalize') && init?.method === 'POST',
      ),
    ).toBe(true)
  })

  it('renders a return to video list button that navigates to the project capture groups', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    stubGroupDetail(group, {
      task: null,
      quality: qualityFixture(group, 'UNREVIEWED'),
      candidateStatus: 'UNKNOWN',
    })
    const { router } = renderRouter(
      [
        {
          path: '/projects/:projectId/capture-groups/:captureGroupId',
          element: <CaptureGroupDetailView />,
        },
        {
          path: '/projects/:projectId/capture-groups',
          element: <div>Capture Groups List</div>,
        },
      ],
      [`/projects/${projectId}/capture-groups/${groupId}`],
    )
    const backBtn = await screen.findByRole('button', { name: /返回视频列表/ })
    await userEvent.setup().click(backBtn)
    await waitFor(() => {
      expect(router.state.location.pathname).toBe(paths.captureGroups(projectId))
    })
  })

  it('supports keyboard arrow navigation between camera streams and preserves active selection', async () => {
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

    const selector = await screen.findByRole('group', { name: '采集记录机位选择' })
    const cam1Btn = within(selector).getByRole('button', { name: /机位 1/ })
    const cam2Btn = within(selector).getByRole('button', { name: /机位 2/ })
    expect(cam1Btn).toHaveAttribute('aria-current', 'true')
    expect(cam2Btn).not.toHaveAttribute('aria-current')

    await user.click(cam1Btn)
    fireEvent.keyDown(cam1Btn, { key: 'ArrowRight' })
    await waitFor(() => {
      expect(cam2Btn).toHaveAttribute('aria-current', 'true')
    })
    expect(cam1Btn).not.toHaveAttribute('aria-current')

    fireEvent.keyDown(cam2Btn, { key: 'ArrowLeft' })
    await waitFor(() => {
      expect(cam1Btn).toHaveAttribute('aria-current', 'true')
    })
    expect(cam2Btn).not.toHaveAttribute('aria-current')
  })
})
