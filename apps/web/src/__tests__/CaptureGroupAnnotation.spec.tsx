import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { paths } from '@/app/paths'
import {
  formatAnnotationTime,
  getActionDisplayName,
  initialDraftSegments,
  validActionCandidates,
  validateSegments,
} from '@/features/capture-group/capture-group-detail-model'
import { renderRoute } from '@/test/render'
import CaptureGroupDetailView from '@/views/CaptureGroupDetailView'

import {
  cameraViewFixture,
  captureGroupFixture,
  groupId,
  groupTask,
  labelSchema,
  projectId,
  qualityFixture,
  stubGroupDetail,
} from './capture-group-fixtures'

afterEach(() => vi.unstubAllGlobals())

describe('action candidate draft validation', () => {
  const task = groupTask(groupId, true)
  const schema = labelSchema()
  const candidate = {
    action_candidate_id: 'synthetic-candidate',
    ai_job_id: 'synthetic-job',
    project_id: projectId,
    capture_group_id: groupId,
    clip_asset_id: task.primary_clip_asset_id!,
    start_ms: 0,
    end_ms: 1000,
    action_label: 'acquire_item',
    confidence: 0.8,
    source_window: 1,
    stitched_windows: [],
    created_at: '2026-09-10T00:00:00Z',
  }

  it('accepts exact task labels and adjacent half-open intervals', () => {
    const next = { ...candidate, start_ms: 1000, end_ms: 2000 }
    expect(validActionCandidates(task, [next, candidate], schema, 2000)).toEqual([candidate, next])
  })

  it.each([
    { project_id: 'other-project' },
    { capture_group_id: 'other-group' },
    { clip_asset_id: 'other-camera' },
    { annotation_task_id: 'other-task' },
    { label_schema_id: 'other-schema' },
    { label_schema_digest: 'other-digest' },
    { action_label: 'prepare' },
    { action_label: '准备' },
    { start_ms: -1 },
    { end_ms: 2001 },
    { start_ms: 0.5 },
  ])('rejects the entire batch for mismatched source, label or time: %j', (change) => {
    expect(
      validActionCandidates(task, [{ ...candidate, ...change }], schema, 2000, {
        projectId,
        captureGroupId: groupId,
        activeClipAssetId: task.primary_clip_asset_id,
      }),
    ).toEqual([])
  })

  it('rejects overlap, mixed jobs, missing duration and mismatched schemas', () => {
    expect(validActionCandidates(task, [candidate, candidate], schema, 2000)).toEqual([])
    expect(
      validActionCandidates(
        task,
        [candidate, { ...candidate, ai_job_id: 'other-job', start_ms: 1000, end_ms: 2000 }],
        schema,
        2000,
      ),
    ).toEqual([])
    expect(validActionCandidates(task, [candidate], schema, null)).toEqual([])
    expect(
      validActionCandidates(task, [candidate], { ...schema, schema_digest: 'other' }, 2000),
    ).toEqual([])
  })

  it('restores a returned camera from that camera’s immutable v2 revision snapshot', () => {
    const returnedTask = groupTask(groupId, true, { revision: true })
    returnedTask.status = 'ASSIGNED'
    returnedTask.can_submit = true
    returnedTask.camera_views = [
      cameraViewFixture(1, { state: 'PENDING' }),
      cameraViewFixture(2, {
        state: 'SUBMITTED',
        segments: [
          {
            segment_id: 'current-camera-2',
            start_ms: 2200,
            end_ms: 2800,
            label: 'open_container',
            source_candidate_ids: [],
          },
        ],
      }),
    ]

    const restored = initialDraftSegments(returnedTask, [], schema, 20000, {
      projectId,
      captureGroupId: groupId,
      activeCameraNumber: 1,
      activeClipAssetId: returnedTask.camera_views[0]!.clip_asset_id,
    })

    expect(restored).toMatchObject([
      {
        segment_id: 'segment-camera-1',
        start_ms: 12345,
        end_ms: 18760,
        label: 'acquire_item',
      },
    ])
  })
})

describe('formatAnnotationTime', () => {
  it('formats millisecond values without changing their authoritative unit', () => {
    expect(formatAnnotationTime(0)).toBe('00:00')
    expect(formatAnnotationTime(12345)).toBe('00:12.345')
    expect(formatAnnotationTime(60000)).toBe('01:00')
  })

  it('formats the hour boundary with an hours component', () => {
    expect(formatAnnotationTime(3_599_999)).toBe('59:59.999')
    expect(formatAnnotationTime(3_600_000)).toBe('01:00:00')
    expect(formatAnnotationTime(3_600_123)).toBe('01:00:00.123')
  })

  it('does not format invalid or non-finite values', () => {
    expect(formatAnnotationTime(Number.NaN)).toBeNull()
    expect(formatAnnotationTime(Number.POSITIVE_INFINITY)).toBeNull()
    expect(formatAnnotationTime(-1)).toBeNull()
    expect(formatAnnotationTime(1.5)).toBeNull()
  })

  it('rejects invalid draft timestamps before submission', () => {
    expect(
      validateSegments([
        { draftId: 1, start_ms: Number.NaN, end_ms: 1000, label: 'label-1' },
        { draftId: 2, start_ms: 0, end_ms: Number.POSITIVE_INFINITY, label: 'label-1' },
      ]),
    ).toEqual({
      1: { start: '开始时间必须是非负整数毫秒。' },
      2: { end: '结束时间必须是非负整数毫秒。' },
    })
  })

  it('rejects segments that exceed total duration', () => {
    expect(
      validateSegments(
        [
          { draftId: 1, start_ms: 0, end_ms: 1000, label: 'label-1' },
          { draftId: 2, start_ms: 1000, end_ms: 1001, label: 'label-1' },
        ],
        1000,
      ),
    ).toEqual({
      2: { end: '结束时间不能超出视频时长（最大 1000 毫秒）。' },
    })
  })
})

describe('action label presentation', () => {
  it('prefers the immutable schema name and falls back to the palette for legacy labels', () => {
    const schema = labelSchema()

    expect(getActionDisplayName('acquire_item', schema)).toBe('拿取容器/工具')
    expect(getActionDisplayName('retrieve_medicine', null)).toBe('拿取药瓶/耗材')
    expect(getActionDisplayName('unknown_action', null)).toBe('unknown_action')
  })
})

describe('CaptureGroup annotation workflow', () => {
  it('keeps a long saved camera draft compact and submits without a separate draft save', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true)
    task.camera_views = [
      cameraViewFixture(1, {
        state: 'DRAFT',
        segments: Array.from({ length: 9 }, (_, index) => ({
          segment_id: `segment-${index}`,
          start_ms: index * 100,
          end_ms: index * 100 + 90,
          label: 'acquire_item',
          source_candidate_ids: [],
        })),
      }),
    ]
    const fetchMock = stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'FAILED',
    })
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })
    await screen.findByRole('button', { name: '展开全部' })
    expect(screen.queryByLabelText('开始时间（毫秒）')).not.toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: '提交机位' }))
    await waitFor(() => {
      const writes = fetchMock.mock.calls.filter(
        ([url, init]) => String(url).endsWith('/camera-views/1') && init?.method === 'PUT',
      )
      expect(writes).toHaveLength(1)
      expect(JSON.parse(String(writes[0]![1]?.body))).toMatchObject({
        state: 'SUBMITTED',
        segments: task.camera_views![0]!.segments,
      })
    })
  }, 25000)

  it('holds other camera actions while a state update is in flight', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true)
    task.camera_views = [
      cameraViewFixture(1, {
        state: 'DRAFT',
        segments: [
          {
            segment_id: 'ready-segment',
            start_ms: 100,
            end_ms: 760,
            label: 'acquire_item',
            source_candidate_ids: [],
          },
        ],
      }),
    ]
    const fetchMock = stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    let releaseUpdate = () => {}
    const updateGate = new Promise<void>((resolve) => {
      releaseUpdate = resolve
    })
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL, init?: RequestInit) =>
        String(input).includes('/camera-views/1') && init?.method === 'PUT'
          ? updateGate.then(() => fetchMock(input, init))
          : fetchMock(input, init),
      ),
    )
    const user = userEvent.setup()
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    await user.click(await screen.findByRole('button', { name: '提交机位' }))
    expect(screen.getByRole('button', { name: '排除机位' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: '第 1 个时间段动作标签' })).toBeDisabled()
    releaseUpdate()
    await waitFor(() => expect(screen.getByRole('button', { name: '排除机位' })).toBeEnabled())
    expect(screen.getByText('本机位已提交')).toBeInTheDocument()
  })

  it('does not expose intermediate draft saves and keeps exclude action available before submission', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, false)
    stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'FAILED',
    })

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    await screen.findByLabelText('开始时间（毫秒）')
    await waitFor(() => expect(screen.getByLabelText('开始时间（毫秒）')).toBeEnabled())
    expect(screen.queryByText('暂存，稍后继续')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '保存本机位草稿' })).not.toBeInTheDocument()
    expect(screen.queryByText('整组机位标注')).not.toBeInTheDocument()
    expect(
      screen.queryByText('请先完成并提交或排除所有预期机位，再提交整组修订。'),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '排除机位' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '提交复核' })).toBeDisabled()
  })

  it('keeps automatic action drafting independent from no-hand detection and protects edits', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    stubGroupDetail(group, {
      task: groupTask(groupId, true),
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })
    expect(await screen.findByText('手部检测：已完成 · 未检测到手部')).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('MediaPipe 在采样帧中未检测到手')
    expect(screen.queryByRole('button', { name: '启动自动标注' })).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('开始时间（毫秒）'), '1')
    expect(screen.queryByRole('button', { name: '载入动作草稿' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('开始时间（毫秒）')).toHaveValue(1)
  })
  it('preserves unsaved segments in permanent workspace and blocks leaving until confirmed', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    stubGroupDetail(group, {
      task: groupTask(groupId, true),
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'FAILED',
    })
    const user = userEvent.setup()
    const { router } = renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })
    await screen.findByLabelText('开始时间（毫秒）')
    await waitFor(() => expect(screen.getByLabelText('开始时间（毫秒）')).toBeEnabled())
    const start = screen.getByLabelText('开始时间（毫秒）')
    await user.clear(start)
    await user.type(start, '123')
    await user.click(screen.getByRole('button', { name: '刷新' }))
    await waitFor(() => expect(screen.getByLabelText('开始时间（毫秒）')).toHaveValue(123))
    await user.click(screen.getByRole('button', { name: /机位 2/ }))
    expect(screen.getByLabelText('开始时间（毫秒）')).toHaveValue(0)
    await user.click(screen.getByRole('button', { name: /机位 1/ }))
    expect(screen.getByLabelText('开始时间（毫秒）')).toHaveValue(123)
    await user.click(screen.getByRole('button', { name: /机位 2/ }))
    expect(screen.getByLabelText('开始时间（毫秒）')).toHaveValue(0)
    expect(
      screen.getByRole('button', { name: '第 1 个时间段：使用当前时间作为开始' }),
    ).toBeDisabled()
    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)
    await user.click(screen.getByRole('link', { name: '视频' }))
    await waitFor(() =>
      expect(Array.from(router.state.blockers.values())[0]?.state).toBe('blocked'),
    )
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('离开并丢弃未提交的标注？')
    await user.click(within(dialog).getByRole('button', { name: '继续编辑' }))
    expect(router.state.location.pathname).toContain(groupId)
    expect(screen.getByLabelText('开始时间（毫秒）')).toHaveValue(0)
    await user.click(screen.getByRole('button', { name: /机位 1/ }))
    expect(screen.getByLabelText('开始时间（毫秒）')).toHaveValue(123)
    await user.click(screen.getByRole('link', { name: '视频' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: '丢弃并离开' }),
    )
    await waitFor(() => expect(router.state.location.pathname).toBe(paths.captureGroups(projectId)))
  }, 30000)

  it('keeps a completed task read-only and navigates to its review result regardless of historical quality state', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true, { revision: true })
    task.camera_views = task.camera_views?.map((view) => ({
      ...view,
      version: 1,
      state: 'SUBMITTED',
      segments: [
        {
          segment_id: `segment-camera-${view.camera_number}`,
          start_ms: view.camera_number === 1 ? 12345 : 0,
          end_ms: view.camera_number === 1 ? 18760 : 1000,
          label: 'acquire_item',
          source_candidate_ids: [],
        },
      ],
    }))
    // 历史质量记录停留在“待复核”，但质量关卡已取消：它只读保留，不再阻塞或改写导航。
    const fetchMock = stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'NEEDS_REVIEW'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    expect(screen.queryByRole('button', { name: '重新确认整体质量' })).not.toBeInTheDocument()
    expect(await screen.findByText('标注复核')).toBeInTheDocument()
    await user.click(await screen.findByText('更多信息'))
    const auditDetails = screen.getByText('人工标签修订、复核与适用性检查').closest('details')
    if (!auditDetails) throw new Error('Annotation audit details were not mounted.')
    await user.click(within(auditDetails).getByText('人工标签修订、复核与适用性检查'))
    expect(within(auditDetails).getByText('任务 ID')).toBeInTheDocument()
    expect(within(auditDetails).getByText('第 1 次正式修订')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '提交复核' })).not.toBeInTheDocument()
    expect(
      within(auditDetails).getByRole('link', { name: '打开采集记录人工标签任务上下文' }),
    ).toHaveAttribute('href', paths.captureGroupFocus(projectId, groupId, 'review'))
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).endsWith('/revisions')),
    ).toHaveLength(0)
  })

  it('shows the first Revision lock and only one group task before supporting-view switching', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true, { revision: true })
    stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    expect(await screen.findByRole('button', { name: /机位 1/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(screen.queryByRole('button', { name: '查看结果' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '查看复核详情' }))
    expect(screen.getByText('00:12.345 → 00:18.760')).toBeInTheDocument()
    expect(screen.getByText('00:00 → 00:01')).toBeInTheDocument()
    await user.click(await screen.findByText('更多信息'))
    const auditDetails = screen.getByText('人工标签修订、复核与适用性检查').closest('details')
    if (!auditDetails) throw new Error('Annotation audit details were not mounted.')
    await user.click(within(auditDetails).getByText('人工标签修订、复核与适用性检查'))
    expect(within(auditDetails).getByText('已完成')).toBeInTheDocument()
    expect(within(auditDetails).getByText('第 1 次正式修订')).toBeInTheDocument()
    expect(within(auditDetails).getByText('机位进度')).toBeInTheDocument()
    expect(within(auditDetails).getByText('2 / 2 路已提交或排除')).toBeInTheDocument()
    expect(screen.getAllByText('人工复核 · 已通过').length).toBeGreaterThan(0)
    expect(screen.getByRole('heading', { level: 3, name: '标注检查' })).toBeInTheDocument()
    expect(screen.getByText('当前规则检查通过')).toBeInTheDocument()
    const compareStrip = screen.getByLabelText('采集记录机位选择')
    expect(within(compareStrip).getAllByRole('button', { name: /机位 / })).toHaveLength(2)
  })

  it('renders the colocated annotation form without a duplicate entry CTA', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    group.camera_set_snapshot.members[0]!.camera_role = 'PLATFORM_LOCAL'
    const task = groupTask(groupId, true)
    stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'FAILED',
    })
    const user = userEvent.setup()
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    const submitButton = await screen.findByRole('button', { name: '提交复核' })
    const annotationSection = screen.getByRole('region', { name: '动作标注' })
    expect(within(annotationSection).getByText('待提交')).toBeInTheDocument()
    expect(within(annotationSection).queryByText(task.assignee_actor_id)).not.toBeInTheDocument()
    expect(within(annotationSection).queryByText('整组机位标注')).not.toBeInTheDocument()
    expect(within(annotationSection).queryByText(/时间轴彼此独立/)).not.toBeInTheDocument()
    expect(within(annotationSection).getByText('机位 1')).toBeInTheDocument()
    const camera2Btn = screen.getByRole('button', { name: /机位 2/ })
    await user.click(camera2Btn)
    expect(within(annotationSection).getByText('机位 2')).toBeInTheDocument()
    expect(screen.queryByText('PLATFORM_LOCAL')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '继续标注' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '继续配药动作标注' })).not.toBeInTheDocument()
    expect(screen.getAllByText('动作标注').length).toBeGreaterThan(0)
    expect(screen.queryByText('打开采集记录标注')).not.toBeInTheDocument()
    expect(submitButton).toBeInTheDocument()
  })

  it('updates readable time below each millisecond input as the draft changes', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    stubGroupDetail(group, {
      task: groupTask(groupId, true),
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'FAILED',
    })

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    const form = (await screen.findByRole('button', { name: '提交复核' })).closest('form')
    if (!form) throw new Error('Annotation form was not mounted.')
    const startInput = within(form).getByLabelText('开始时间（毫秒）')
    const endInput = within(form).getByLabelText('结束时间（毫秒）')
    const startDescribedBy = startInput.getAttribute('aria-describedby')!
    const endDescribedBy = endInput.getAttribute('aria-describedby')!
    expect(startDescribedBy).toMatch(/start-readable/)
    expect(endDescribedBy).toMatch(/end-readable/)
    expect(form.querySelector(`#${startDescribedBy}`)).toHaveTextContent('00:00')
    expect(form.querySelector(`#${endDescribedBy}`)).toHaveTextContent('00:01')

    fireEvent.change(startInput, { target: { value: '12345' } })
    fireEvent.change(endInput, { target: { value: '18760' } })

    expect(form.querySelector(`#${startDescribedBy}`)).toHaveTextContent('00:12.345')
    expect(form.querySelector(`#${endDescribedBy}`)).toHaveTextContent('00:18.760')

    fireEvent.change(startInput, { target: { value: '' } })
    expect((startInput as HTMLInputElement).value).toBe('')
    expect(startInput.getAttribute('aria-describedby') ?? '').not.toContain('start-readable')
    expect(form.querySelector(`#${startDescribedBy}`)).toBeNull()
  })

  it('groups each segment by action and time range, and supports adding and deleting segments', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    stubGroupDetail(group, {
      task: groupTask(groupId, true),
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'FAILED',
    })
    const user = userEvent.setup()
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    const submitButton = await screen.findByRole('button', { name: '提交复核' })
    const form = submitButton.closest('form')
    if (!form) throw new Error('Annotation form was not mounted.')
    expect(within(form).getByText('动作标签')).toBeInTheDocument()
    expect(within(form).getAllByText('时间范围')).toHaveLength(1)
    expect(within(form).getByLabelText('开始时间（毫秒）')).toHaveValue(0)
    expect(within(form).getByLabelText('结束时间（毫秒）')).toHaveValue(1000)
    expect(within(form).getByRole('button', { name: '添加时间段' })).toBeInTheDocument()

    await user.click(within(form).getByRole('button', { name: '添加时间段' }))
    expect(within(form).getAllByText('时间范围')).toHaveLength(2)
    expect(within(form).getAllByRole('button', { name: /删除第 \d 个时间段/ })).toHaveLength(2)

    await user.click(within(form).getByRole('button', { name: '删除第 1 个时间段' }))
    expect(within(form).getAllByText('时间范围')).toHaveLength(1)
    expect(within(form).getByLabelText('第 1 个时间段动作标签')).toBeInTheDocument()
  })

  it('keeps validation guards for non-negative starts, ordered ends, and required labels', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const fetchMock = stubGroupDetail(group, {
      task: groupTask(groupId, true),
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'FAILED',
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    const submitButton = await screen.findByRole('button', { name: '提交复核' })
    const form = submitButton.closest('form')
    if (!form) throw new Error('Annotation form was not mounted.')
    await user.click(within(form).getByRole('button', { name: '提交机位' }))

    expect(screen.getAllByText('请选择标签。').length).toBeGreaterThan(0)
    const labelField = within(form).getByRole('combobox', {
      name: '第 1 个时间段动作标签',
    })
    expect(labelField).toHaveAttribute('aria-invalid', 'true')
    const descriptionId = labelField.getAttribute('aria-describedby')
    expect(descriptionId).toMatch(/^segment-\d+-label-error$/)
    expect(document.getElementById(descriptionId ?? '')).toHaveTextContent('请选择标签。')
    expect(document.activeElement).toBe(labelField)
    expect(
      validateSegments([
        { draftId: 1, start_ms: -1, end_ms: 0, label: '' },
        { draftId: 2, start_ms: 0, end_ms: 0, label: 'label-1' },
      ]),
    ).toEqual({
      1: {
        start: '开始时间不能小于 0 毫秒。',
        label: '请选择标签。',
      },
      2: { end: '结束时间必须晚于开始时间。' },
    })
    expect(
      fetchMock.mock.calls.filter(
        ([input, init]) => String(input).endsWith('/revisions') && init?.method === 'POST',
      ),
    ).toHaveLength(0)
  })

  it('copies the authoritative playback position into the draft without submitting', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const fetchMock = stubGroupDetail(group, {
      task: groupTask(groupId, true),
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'FAILED',
      playbackGrant: {
        grant_id: 'playback-grant-annotation',
        purpose: 'DATA_REVIEW',
        media_url: '/synthetic/authorized-annotation.mp4',
        expires_at: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
        content_type: 'video/mp4',
      },
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    await user.click(await screen.findByRole('button', { name: '播放视频' }, { timeout: 3000 }))
    const video = await screen.findByLabelText('视频预览')
    Object.defineProperty(video, 'currentTime', {
      configurable: true,
      get: () => 12.345,
      set: () => {},
    })
    fireEvent.loadedMetadata(video)
    fireEvent.timeUpdate(video)

    const form = (await screen.findByRole('button', { name: '提交复核' })).closest('form')
    if (!form) throw new Error('Annotation form was not mounted.')
    const startButton = await within(form).findByRole('button', {
      name: '第 1 个时间段：使用当前时间作为开始',
    })
    const endButton = within(form).getByRole('button', {
      name: '第 1 个时间段：使用当前时间作为结束',
    })
    await waitFor(() => expect(startButton).not.toBeDisabled())
    await waitFor(() => {
      fireEvent.timeUpdate(video)
      fireEvent.click(startButton)
      expect(screen.getByLabelText('开始时间（毫秒）')).toHaveValue(12345)
    })
    await waitFor(() => {
      fireEvent.timeUpdate(video)
      fireEvent.click(endButton)
      expect(screen.getByLabelText('结束时间（毫秒）')).toHaveValue(12345)
    })
    expect(
      fetchMock.mock.calls.filter(
        ([input, init]) => String(input).endsWith('/revisions') && init?.method === 'POST',
      ),
    ).toHaveLength(0)
  })

  it('allows Space playback outside controls but never steals it while typing', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    stubGroupDetail(group, {
      task: groupTask(groupId, true),
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'FAILED',
      playbackGrant: {
        grant_id: 'playback-grant-shortcut',
        purpose: 'DATA_REVIEW',
        media_url: '/synthetic/authorized-shortcut.mp4',
        expires_at: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
        content_type: 'video/mp4',
      },
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    await user.click(await screen.findByRole('button', { name: '播放视频' }, { timeout: 3000 }))
    const video = await screen.findByLabelText('视频预览')
    let paused = true
    const play = vi.fn(() => {
      paused = false
      return Promise.resolve()
    })
    const pause = vi.fn(() => {
      paused = true
    })
    Object.defineProperty(video, 'paused', { configurable: true, get: () => paused })
    Object.defineProperty(video, 'play', { configurable: true, value: play })
    Object.defineProperty(video, 'pause', { configurable: true, value: pause })

    fireEvent.keyDown(document.body, { code: 'Space', key: ' ' })
    expect(play).toHaveBeenCalledTimes(1)
    fireEvent.keyDown(document.body, { code: 'Space', key: ' ' })
    expect(pause).toHaveBeenCalledTimes(1)

    const startInput = screen.getByLabelText('开始时间（毫秒）')
    startInput.focus()
    fireEvent.keyDown(startInput, { code: 'Space', key: ' ' })
    expect(play).toHaveBeenCalledTimes(1)
    expect(pause).toHaveBeenCalledTimes(1)
  })

  it('automatically loads AI candidates into the timeline and keeps the workflow compact', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const candidateItem = {
      action_candidate_id: 'cand-test-1',
      ai_job_id: 'job-test-1',
      project_id: projectId,
      capture_group_id: groupId,
      clip_asset_id: group.streams[0]!.clip_asset_id,
      start_ms: 1000,
      end_ms: 1800,
      action_label: 'acquire_item',
      target_object: 'vial',
      confidence: 0.92,
      source_window: 1,
      stitched_windows: [],
      created_at: '2026-09-10T00:00:00Z',
    }
    stubGroupDetail(group, {
      task: groupTask(groupId, true),
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'HAND_DETECTED',
      actionCandidates: [candidateItem],
      actionCandidateJob: {
        job_id: 'job-test-1',
        project_id: projectId,
        capture_group_id: groupId,
        clip_asset_id: group.streams[0]!.clip_asset_id,
        status: 'SUCCEEDED',
        algorithm_key: 'QWEN3_5_ACTION',
        error_code: null,
        error_message: null,
        created_at: '2026-09-10T00:00:00Z',
        updated_at: '2026-09-10T00:00:00Z',
      },
    })

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
    })

    expect(await screen.findByText('AI 预填待核对', {}, { timeout: 10000 })).toBeInTheDocument()
    expect(screen.queryByText('AI 动作预标注建议')).not.toBeInTheDocument()
    expect(screen.getAllByLabelText('开始时间（毫秒）')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: '查看来源 (1)' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '播放核对' })).not.toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByLabelText('开始时间（毫秒）')).toHaveValue(1000)
      expect(screen.getByLabelText('结束时间（毫秒）')).toHaveValue(1800)
    })
  })

  it('allows fine-tuning segment start and end times via nudge buttons and arrow keys', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    stubGroupDetail(group, {
      task: groupTask(groupId, true),
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'HAND_DETECTED',
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    await screen.findByLabelText('开始时间（毫秒）')
    await waitFor(() => expect(screen.getByLabelText('开始时间（毫秒）')).toBeEnabled())
    const startInput = screen.getByLabelText('开始时间（毫秒）')
    expect(startInput).toHaveValue(0)

    // Click +100 button on start time
    const startPlus100 = screen.getByRole('button', {
      name: '第 1 个时间段：开始时间增加 100 毫秒',
    })
    await user.click(startPlus100)
    expect(startInput).toHaveValue(100)

    // Click -100 button on start time
    const startMinus100 = screen.getByRole('button', {
      name: '第 1 个时间段：开始时间减少 100 毫秒',
    })
    await user.click(startMinus100)
    expect(startInput).toHaveValue(0)

    // Arrow keys inside start input: ArrowUp -> +100
    fireEvent.keyDown(startInput, { key: 'ArrowUp' })
    expect(startInput).toHaveValue(100)

    // Shift + ArrowUp inside start input: +500 -> 600
    fireEvent.keyDown(startInput, { key: 'ArrowUp', shiftKey: true })
    expect(startInput).toHaveValue(600)

    // ArrowDown inside start input: -100 -> 500
    fireEvent.keyDown(startInput, { key: 'ArrowDown' })
    expect(startInput).toHaveValue(500)

    // Shift + ArrowDown: -500 -> 0
    fireEvent.keyDown(startInput, { key: 'ArrowDown', shiftKey: true })
    expect(startInput).toHaveValue(0)

    // End time nudging
    const endInput = screen.getByLabelText('结束时间（毫秒）')
    expect(endInput).toHaveValue(1000)

    const endPlus100 = screen.getByRole('button', { name: '第 1 个时间段：结束时间增加 100 毫秒' })
    await user.click(endPlus100)
    expect(endInput).toHaveValue(1100)

    const endMinus100 = screen.getByRole('button', { name: '第 1 个时间段：结束时间减少 100 毫秒' })
    await user.click(endMinus100)
    expect(endInput).toHaveValue(1000)
  })

  it('directly triggers video playback and mounts video when clicking segment play button while video is idle', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    stubGroupDetail(group, {
      task: groupTask(groupId, true),
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'HAND_DETECTED',
      playbackGrant: {
        grant_id: 'playback-grant-direct-slice',
        purpose: 'DATA_REVIEW',
        media_url: '/synthetic/authorized-direct-slice.mp4',
        expires_at: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
        content_type: 'video/mp4',
      },
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
    })

    // Video placeholder is ready (async grant + high parallel load can exceed 1s)
    await screen.findByRole('button', { name: '播放视频' }, { timeout: 5000 })

    const playSegmentBtn = await screen.findByRole('button', { name: '播放第 1 个时间段' })
    expect(playSegmentBtn).toBeInTheDocument()

    // Click segment play button
    await user.click(playSegmentBtn)

    // Verify video is now mounted / loaded
    const video = await screen.findByLabelText('视频预览', {}, { timeout: 5000 })
    expect(video).toBeInTheDocument()
  })
})

describe('simple camera submission workflow', () => {
  it('submits one camera while the group quality record is still unreviewed', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const assignedTask = groupTask(groupId, true)
    assignedTask.camera_views = [cameraViewFixture(1)]
    const fetchMock = stubGroupDetail(group, {
      task: assignedTask,
      taskResponses: [null, assignedTask],
      taskAfterAssignment: assignedTask,
      quality: qualityFixture(group, 'UNREVIEWED'),
      candidateStatus: 'HAND_DETECTED',
      viewQualityByCamera: {
        1: {
          quality_review_id: null,
          project_id: projectId,
          clip_asset_id: group.streams[0]!.clip_asset_id,
          decision: 'UNREVIEWED',
          reason_code: null,
          reviewed_by: null,
          reviewed_at: null,
          version: 0,
        },
      },
      actionCandidates: [
        {
          action_candidate_id: 'candidate-direct-submit',
          ai_job_id: 'job-direct-submit',
          project_id: projectId,
          capture_group_id: groupId,
          clip_asset_id: group.streams[0]!.clip_asset_id,
          start_ms: 1000,
          end_ms: 1800,
          action_label: 'acquire_item',
          confidence: 0.92,
          source_window: 1,
          stitched_windows: [],
          created_at: '2026-09-10T00:00:00Z',
        },
      ],
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    const submit = await screen.findByRole('button', { name: '提交机位' })
    expect(submit).toBeEnabled()
    expect(screen.getAllByRole('button', { name: '提交机位' })).toHaveLength(1)
    await user.click(submit)

    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          ([input, init]) =>
            String(input).endsWith(`/capture-groups/${groupId}/annotation-tasks`) &&
            init?.method === 'POST',
        ),
      ).toBe(true),
    )
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) => String(input).includes('/camera-views/1') && init?.method === 'PUT',
      ),
    ).toBe(true)
    // 提交机位标注不再需要任何人工质量确认动作。
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) => String(input).includes('/quality-review') && init?.method === 'PUT',
      ),
    ).toBe(false)
  })

  it('can exclude a previously submitted camera directly before group submission', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true)
    task.camera_views = [
      cameraViewFixture(1, {
        state: 'SUBMITTED',
        version: 1,
        segments: [
          {
            segment_id: 'submitted-before-exclusion',
            start_ms: 1000,
            end_ms: 1800,
            label: 'acquire_item',
            source_candidate_ids: [],
          },
        ],
        note: '此前已确认',
      }),
    ]
    const fetchMock = stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    await user.click(await screen.findByRole('button', { name: '排除机位' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/已提交人工标注/)).toBeInTheDocument()
    await user.type(within(dialog).getByRole('textbox'), '重新核对后确认该机位不纳入本次修订')
    await user.click(within(dialog).getByRole('button', { name: /确认排除/ }))

    const updates = () =>
      fetchMock.mock.calls.filter(
        ([input, init]) => String(input).includes('/camera-views/1') && init?.method === 'PUT',
      )
    await waitFor(() => expect(updates()).toHaveLength(1))
    expect(JSON.parse(String(updates()[0]?.[1]?.body))).toMatchObject({
      state: 'EXCLUDED',
      segments: [],
      note: '',
      exclusion_reason: '重新核对后确认该机位不纳入本次修订',
    })
  })

  it('reloads the camera version after a conflict and lets the user confirm exclusion again', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true)
    task.camera_views = [cameraViewFixture(1, { state: 'DRAFT', version: 1 })]
    const latestTask = {
      ...task,
      camera_views: [cameraViewFixture(1, { state: 'DRAFT' as const, version: 2 })],
    }
    const fetchMock = stubGroupDetail(group, {
      task,
      taskResponses: [task, latestTask],
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
      cameraViewUpdateError: {
        status: 409,
        errorCode: 'ANNOTATION_CAMERA_VIEW_VERSION_CONFLICT',
      },
    })
    const user = userEvent.setup()
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    await user.click(await screen.findByRole('button', { name: '排除机位' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByRole('textbox'), '无法确认目标动作')
    await user.click(within(dialog).getByRole('button', { name: /确认排除/ }))
    expect(await screen.findByText(/已加载最新状态；请核对后再次确认/)).toBeInTheDocument()
    expect(within(dialog).getByRole('textbox')).toHaveValue('无法确认目标动作')
    await user.click(within(dialog).getByRole('button', { name: /确认排除/ }))

    const updates = () =>
      fetchMock.mock.calls.filter(
        ([input, init]) => String(input).includes('/camera-views/1') && init?.method === 'PUT',
      )
    await waitFor(() => expect(updates()).toHaveLength(2))
    expect(JSON.parse(String(updates()[0]?.[1]?.body))).toMatchObject({
      expected_version: 1,
      state: 'EXCLUDED',
    })
    expect(JSON.parse(String(updates()[1]?.[1]?.body))).toMatchObject({
      expected_version: 2,
      state: 'EXCLUDED',
      exclusion_reason: '无法确认目标动作',
    })
    expect(await screen.findByRole('button', { name: /取消排除/ })).toBeInTheDocument()
  })

  it('cycles through reedit, exclusion, restoration and resubmission with current versions', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true, { revision: true })
    task.status = 'ASSIGNED'
    task.can_submit = true
    task.camera_views = [
      cameraViewFixture(1, {
        state: 'SUBMITTED',
        version: 1,
        segments: [
          {
            segment_id: 'confirmed-action',
            start_ms: 1000,
            end_ms: 1800,
            label: 'acquire_item',
            source_candidate_ids: [],
          },
        ],
      }),
    ]
    if (task.annotation_revision?.annotation_document.schema_version === 'annotation-document.v2') {
      const revisedCamera = task.annotation_revision.annotation_document.camera_views.find(
        (view) => view.camera_number === 1,
      )
      if (revisedCamera) {
        revisedCamera.segments = revisedCamera.segments.map((segment) => ({
          ...segment,
          start_ms: 100,
          end_ms: 760,
        }))
      }
    }
    const fetchMock = stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    await user.click((await screen.findAllByRole('button', { name: '重新编辑' })).at(-1)!)
    await user.click(await screen.findByRole('button', { name: '排除机位' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByRole('textbox'), '无法确认目标动作')
    await user.click(within(dialog).getByRole('button', { name: /确认排除/ }))

    await user.click(await screen.findByRole('button', { name: /取消排除/ }))
    const submit = await screen.findByRole('button', { name: '提交机位' })
    await waitFor(() => expect(submit).toBeEnabled())
    await user.click(submit)
    await user.click((await screen.findAllByRole('button', { name: '重新编辑' })).at(-1)!)
    await user.click(await screen.findByRole('button', { name: '排除机位' }))
    const nextDialog = await screen.findByRole('dialog')
    await user.type(within(nextDialog).getByRole('textbox'), '再次排除')
    await user.click(within(nextDialog).getByRole('button', { name: /确认排除/ }))

    const updates = () =>
      fetchMock.mock.calls.filter(
        ([input, init]) => String(input).includes('/camera-views/1') && init?.method === 'PUT',
      )
    await waitFor(() => expect(updates()).toHaveLength(6))
    expect(updates().map(([, init]) => JSON.parse(String(init?.body)).expected_version)).toEqual([
      1, 2, 3, 4, 5, 6,
    ])
    expect(updates().map(([, init]) => JSON.parse(String(init?.body)).state)).toEqual([
      'DRAFT',
      'EXCLUDED',
      'PENDING',
      'SUBMITTED',
      'DRAFT',
      'EXCLUDED',
    ])
    expect(await screen.findByRole('button', { name: /取消排除/ })).toBeInTheDocument()
  })

  it('lets a project member exclude another owner’s camera and restore it with a clean draft', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true, { revision: true })
    task.status = 'ASSIGNED'
    task.can_submit = true
    task.assignee_actor_id = 'another-project-member'
    task.camera_views = [cameraViewFixture(1, { state: 'DRAFT', note: 'old draft note' })]
    if (task.annotation_revision?.annotation_document.schema_version === 'annotation-document.v2') {
      const restoredRevisionCamera = task.annotation_revision.annotation_document.camera_views.find(
        (view) => view.camera_number === 1,
      )
      if (restoredRevisionCamera) {
        restoredRevisionCamera.segments = restoredRevisionCamera.segments.map((segment) => ({
          ...segment,
          start_ms: 100,
          end_ms: 760,
        }))
      }
    }
    const fetchMock = stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })
    await user.click(await screen.findByRole('button', { name: '排除机位' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByRole('textbox'), '测试镜头被遮挡')
    await user.click(within(dialog).getByRole('button', { name: /取\s*消/ }))
    expect(
      fetchMock.mock.calls.filter(
        ([input, init]) => String(input).includes('/camera-views/1') && init?.method === 'PUT',
      ),
    ).toHaveLength(0)
    await user.click(await screen.findByRole('button', { name: '排除机位' }))
    const reopenedDialog = await screen.findByRole('dialog')
    await user.type(within(reopenedDialog).getByRole('textbox'), '测试镜头被遮挡')
    await user.click(within(reopenedDialog).getByRole('button', { name: /确认排除/ }))
    await screen.findByRole('button', { name: /取消排除/ })
    const updates = () =>
      fetchMock.mock.calls.filter(
        ([input, init]) => String(input).includes('/camera-views/1') && init?.method === 'PUT',
      )
    expect(JSON.parse(String(updates()[0]?.[1]?.body))).toMatchObject({
      state: 'EXCLUDED',
      exclusion_reason: '测试镜头被遮挡',
      segments: [],
    })
    await user.click(await screen.findByRole('button', { name: /取消排除/ }))
    await waitFor(() => expect(updates()).toHaveLength(2))
    expect(JSON.parse(String(updates()[1]?.[1]?.body))).toMatchObject({
      state: 'PENDING',
      segments: [],
      note: '',
      exclusion_reason: null,
    })
    const submit = await screen.findByRole('button', { name: '提交机位' }, { timeout: 10000 })
    await waitFor(() => expect(submit).toBeEnabled(), { timeout: 5000 })
    await user.click(submit)
    await waitFor(() => expect(updates()).toHaveLength(3))
    expect(JSON.parse(String(updates()[2]?.[1]?.body))).toMatchObject({
      state: 'SUBMITTED',
      segments: [
        {
          segment_id: 'segment-camera-1',
          start_ms: 100,
          end_ms: 760,
          label: 'acquire_item',
        },
      ],
    })
    expect(screen.queryByRole('button', { name: '废弃视频' })).not.toBeInTheDocument()
  })

  it('requires explicit task re-preparation when the loaded capture input is stale', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true)
    task.camera_views = [cameraViewFixture(1)]
    const refreshedTask = {
      ...task,
      group_input_version: 'capture-group-view-set.v1:refreshed',
      version: task.version + 1,
      camera_views: [cameraViewFixture(1)],
    }
    const fetchMock = stubGroupDetail(group, {
      task,
      taskResponses: [task, refreshedTask],
      taskAfterAssignment: refreshedTask,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'HAND_DETECTED',
      cameraViewUpdateError: {
        status: 409,
        errorCode: 'ANNOTATION_CAMERA_VIEW_INPUT_VERSION_CONFLICT',
      },
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'ADMIN' },
    })

    await user.click(await screen.findByRole('button', { name: '排除机位' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByRole('textbox'), '采集输入已变化')
    await user.click(within(dialog).getByRole('button', { name: /确认排除/ }))

    expect(await screen.findByText('采集输入已变化，当前标注任务已过期。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '刷新并重新准备任务' })).toBeEnabled()
    expect(
      fetchMock.mock.calls.filter(
        ([input, init]) =>
          String(input).endsWith(`/capture-groups/${groupId}/annotation-tasks`) &&
          init?.method === 'POST',
      ),
    ).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: '刷新并重新准备任务' }))
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.filter(
          ([input, init]) =>
            String(input).endsWith(`/capture-groups/${groupId}/annotation-tasks`) &&
            init?.method === 'POST',
        ),
      ).toHaveLength(1),
    )
    await waitFor(() =>
      expect(screen.queryByText('采集输入已变化，当前标注任务已过期。')).not.toBeInTheDocument(),
    )
    expect(
      fetchMock.mock.calls.filter(
        ([input, init]) => String(input).includes('/camera-views/1') && init?.method === 'PUT',
      ),
    ).toHaveLength(1)
  })

  it('restores the current AI candidate draft after exclusion and resubmits it', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true)
    task.camera_views = [cameraViewFixture(1, { state: 'DRAFT' })]
    const candidate = {
      action_candidate_id: 'candidate-after-restore',
      ai_job_id: 'job-after-restore',
      project_id: projectId,
      capture_group_id: groupId,
      clip_asset_id: task.camera_views[0]!.clip_asset_id!,
      start_ms: 1000,
      end_ms: 1800,
      action_label: 'acquire_item',
      confidence: 0.9,
      source_window: 1,
      stitched_windows: [],
      created_at: '2026-09-10T00:00:00Z',
    }
    const fetchMock = stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'HAND_DETECTED',
      actionCandidates: [candidate],
    })
    const user = userEvent.setup()
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    await user.click(await screen.findByRole('button', { name: '排除机位' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByRole('textbox'), '测试排除后恢复')
    await user.click(within(dialog).getByRole('button', { name: /确认排除/ }))
    await screen.findByRole('button', { name: /取消排除/ })
    await user.click(await screen.findByRole('button', { name: /取消排除/ }))
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.filter(
          ([input, init]) => String(input).includes('/camera-views/1') && init?.method === 'PUT',
        ),
      ).toHaveLength(2),
    )

    const submit = await screen.findByRole('button', { name: '提交机位' })
    await waitFor(() => expect(submit).toBeEnabled(), { timeout: 5000 })
    await user.click(submit)
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.filter(
          ([input, init]) => String(input).includes('/camera-views/1') && init?.method === 'PUT',
        ),
      ).toHaveLength(3),
    )
    const updates = fetchMock.mock.calls.filter(
      ([input, init]) => String(input).includes('/camera-views/1') && init?.method === 'PUT',
    )
    expect(JSON.parse(String(updates[2]?.[1]?.body))).toMatchObject({
      state: 'SUBMITTED',
      segments: [
        {
          start_ms: 1000,
          end_ms: 1800,
          label: 'acquire_item',
          source_candidate_ids: ['candidate-after-restore'],
        },
      ],
    })
  })

  it('records independent-timeline correspondences without asking for a human reason', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true)
    task.camera_views = [1, 2].map((camera) =>
      cameraViewFixture(camera, {
        state: 'SUBMITTED',
        segments: [
          {
            segment_id: `segment-${camera}`,
            start_ms: 0,
            end_ms: 1000,
            label: 'acquire_item',
            source_candidate_ids: [],
          },
        ],
      }),
    )
    const fetchMock = stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })
    const submit = await screen.findByRole('button', { name: '提交复核' })
    expect(submit).toBeEnabled()
    expect(screen.queryByRole('textbox', { name: '跨机位对应未确认的原因' })).toBeNull()
    await user.click(submit)
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          ([input, init]) => String(input).endsWith('/revisions') && init?.method === 'POST',
        ),
      ).toBe(true),
    )
    const request = fetchMock.mock.calls.find(
      ([input, init]) => String(input).endsWith('/revisions') && init?.method === 'POST',
    )
    const body = JSON.parse(String(request?.[1]?.body))
    expect(body.correspondences).toHaveLength(2)
    expect(body.correspondences).toEqual(
      expect.arrayContaining(
        [1, 2].map((camera) =>
          expect.objectContaining({
            status: 'UNCERTAIN',
            reason: '各路机位按独立时间轴送审，未确认跨机位对应。',
            segments: [{ camera_number: camera, segment_id: `segment-${camera}` }],
          }),
        ),
      ),
    )
  })

  it('loads AI candidate segments on clicking 载入 AI button during editing', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true)
    task.camera_views = [
      cameraViewFixture(1, {
        state: 'DRAFT',
        segments: [
          {
            segment_id: 'manual-seg',
            start_ms: 0,
            end_ms: 500,
            label: 'acquire_item',
            source_candidate_ids: [],
          },
        ],
      }),
    ]
    const candidate = {
      action_candidate_id: 'ai-cand-1',
      ai_job_id: 'job-1',
      project_id: projectId,
      capture_group_id: groupId,
      clip_asset_id: task.camera_views[0]!.clip_asset_id!,
      start_ms: 600,
      end_ms: 1800,
      action_label: 'open_container',
      confidence: 0.95,
      source_window: 1,
      stitched_windows: [],
      created_at: '2026-09-10T00:00:00Z',
    }
    stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'HAND_DETECTED',
      actionCandidates: [candidate],
    })
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    const loadAiButton = await screen.findByRole('button', { name: /载入 AI/ }, { timeout: 10000 })
    expect(loadAiButton).toBeEnabled()
    await user.click(loadAiButton)

    await waitFor(() => {
      expect(screen.getByLabelText('开始时间（毫秒）')).toHaveValue(600)
      expect(screen.getByLabelText('结束时间（毫秒）')).toHaveValue(1800)
    })
  })

  it('loads AI candidate segments directly from submitted state on clicking 载入 AI', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true)
    task.camera_views = [
      cameraViewFixture(1, {
        state: 'SUBMITTED',
        segments: [
          {
            segment_id: 'submitted-manual-seg',
            start_ms: 0,
            end_ms: 1000,
            label: 'acquire_item',
            source_candidate_ids: [],
          },
        ],
      }),
    ]
    const candidate = {
      action_candidate_id: 'ai-cand-2',
      ai_job_id: 'job-2',
      project_id: projectId,
      capture_group_id: groupId,
      clip_asset_id: task.camera_views[0]!.clip_asset_id!,
      start_ms: 400,
      end_ms: 1600,
      action_label: 'open_container',
      confidence: 0.92,
      source_window: 1,
      stitched_windows: [],
      created_at: '2026-09-10T00:00:00Z',
    }
    const fetchMock = stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'HAND_DETECTED',
      actionCandidates: [candidate],
    })
    const user = userEvent.setup()

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}?focus=annotation`,
      session: { username: 'reviewer', role: 'MEMBER' },
    })

    const banner = (await screen.findByText('本机位已提交', {}, { timeout: 10000 })).closest(
      '.camera-action-state-banner',
    )!
    const loadAiBtn = await within(banner as HTMLElement).findByRole(
      'button',
      { name: /载入 AI/ },
      { timeout: 10000 },
    )
    expect(loadAiBtn).toBeEnabled()
    await user.click(loadAiBtn)

    await waitFor(() => {
      const writes = fetchMock.mock.calls.filter(
        ([url, init]) => String(url).endsWith('/camera-views/1') && init?.method === 'PUT',
      )
      expect(writes).toHaveLength(1)
      const body = JSON.parse(String(writes[0]![1]?.body))
      expect(body.state).toBe('DRAFT')
      expect(body.segments).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            start_ms: 400,
            end_ms: 1600,
            label: 'open_container',
          }),
        ]),
      )
    })
  })
})
