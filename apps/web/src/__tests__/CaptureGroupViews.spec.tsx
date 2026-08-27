import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { AnnotationTask } from '@/api/annotation'
import type {
  CaptureGroup,
  CaptureGroupQualityReview,
  CaptureGroupSummary,
} from '@/api/capture-groups'
import { renderRoute } from '@/test/render'
import CaptureGroupDetailView from '@/views/CaptureGroupDetailView'
import CaptureGroupListView from '@/views/CaptureGroupListView'

const projectId = '30000000-0000-4000-8000-000000000001'
const groupId = '60000000-0000-4000-8000-000000000001'
const taskId = '70000000-0000-4000-8000-000000000001'
const schemaId = '80000000-0000-4000-8000-000000000001'
const clipIds = [1, 2, 4, 5].map(
  (cameraNumber) => `50000000-0000-4000-8000-${String(cameraNumber).padStart(12, '0')}`,
)

afterEach(() => vi.unstubAllGlobals())

describe('CaptureGroup web workflow', () => {
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

    expect(
      await screen.findByRole('heading', { level: 1, name: 'CaptureGroup 多机位' }),
    ).toBeInTheDocument()
    expect(await screen.findAllByRole('listitem')).toHaveLength(5)
    const firstRow = screen.getByRole('listitem', { name: /capture-1/ })
    expect(firstRow).toHaveTextContent('应 5 · 已收 4 · 缺失 1')
    expect(firstRow).toHaveTextContent('缺失：Cam 3')
    expect(firstRow).toHaveTextContent('接收完成 · 有缺失')
    expect(firstRow).toHaveTextContent('合格 · 排除部分视图')
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
    expect(receivingRow).toHaveTextContent('回执 · 接收中')
    expect(receivingRow).toHaveTextContent('应 1 · 已收 1 · 缺失 0')
    expect(receivingRow).toHaveTextContent('等待接收完成后进行组质量判断')

    const completeRow = screen.getByRole('listitem', { name: /complete-unreviewed/ })
    expect(completeRow).toHaveTextContent('应 5 · 已收 5 · 缺失 0')
    expect(completeRow).toHaveTextContent('待判断')
    expect(completeRow).toHaveTextContent('进行 Group Quality 判断')

    const incompleteRow = screen.getByRole('listitem', { name: /incomplete-gap/ })
    expect(incompleteRow).toHaveTextContent('回执 · 接收完成 · 有缺失')
    expect(incompleteRow).toHaveTextContent('应 5 · 已收 3 · 缺失 2')
    expect(incompleteRow).toHaveTextContent('缺失：Cam 2、Cam 4')

    const rejectedRow = screen.getByRole('listitem', { name: /rejected-no-task/ })
    expect(rejectedRow).toHaveTextContent('不合格')
    expect(rejectedRow).toHaveTextContent('补充视图事实并重新判断组质量')

    const qualifiedRow = screen.getByRole('listitem', { name: /qualified-task/ })
    expect(qualifiedRow).toHaveTextContent('合格')
    expect(qualifiedRow).toHaveTextContent('任务 70000000…0001')
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).includes('/quality-review')),
    ).toHaveLength(5)
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
    expect(screen.queryByRole('listitem', { name: /capture-page-2/ })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '加载更多' }))

    expect(await screen.findByRole('listitem', { name: /capture-page-2/ })).toBeInTheDocument()
    expect(screen.getByRole('listitem', { name: /capture-page-1/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '加载更多' })).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/capture-groups?limit=20&cursor=cursor-page-2`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).includes('/quality-review')).length,
    ).toBe(2)
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
    })

    expect(await screen.findByText('当前项目暂无服务端 CaptureGroup。')).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(([input]) => String(input).endsWith('/annotation-tasks')),
    ).toBe(false)
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

    expect(await screen.findByText('回执仍在接收中')).toBeInTheDocument()
    expect(
      screen.getByText(/最终 QUALIFIED \/ QUALIFIED_WITH_EXCLUSIONS 操作已禁用/),
    ).toBeInTheDocument()
    const qualitySelect = screen.getByRole('combobox', { name: 'Group Quality 结论' })
    expect(qualitySelect).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存 Group Quality' })).toBeDisabled()
    expect(fetchMock).not.toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/capture-groups/${groupId}/quality-review`,
      expect.objectContaining({ method: 'PUT' }),
    )
  })

  it('keeps one downgraded task and disables new Revision submission while preserving history and navigation', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const task = groupTask(groupId, true, { revision: true })
    const fetchMock = stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'NEEDS_REVIEW'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    expect(await screen.findByText(/当前 Group Quality 为“需复核”/)).toBeInTheDocument()
    expect(screen.getByText(/任务和既有 Revision 历史仍可审计/)).toBeInTheDocument()
    expect(screen.getByText('第 1 次正式修订')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '提交 Revision' })).toBeDisabled()
    expect(screen.getByLabelText('Primary View')).toBeDisabled()
    expect(screen.getByRole('link', { name: 'Review / Cleaning 上下文' })).toHaveAttribute(
      'href',
      `/projects/${projectId}/annotation-tasks/${taskId}`,
    )
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
    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    expect(await screen.findByText('唯一 CaptureGroup task · 已完成')).toBeInTheDocument()
    expect(screen.getByText('首个正式 Revision 已存在；Primary View 已锁定。')).toBeInTheDocument()
    expect(screen.getByLabelText('Primary View')).toBeDisabled()
    expect(screen.getAllByText('Review · 已通过').length).toBeGreaterThan(0)
    expect(screen.getByText('Cleaning · 已通过')).toBeInTheDocument()
    const compareStrip = screen.getByLabelText('Supporting View 相机切换')
    expect(within(compareStrip).getAllByRole('button', { name: /Cam / })).toHaveLength(2)
  })
})

function stubGroupList(
  groups: CaptureGroup[],
  tasks: AnnotationTask[],
  options: { qualityByGroup?: Record<string, CaptureGroupQualityReview> } = {},
) {
  const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = String(input)
    const path = new URL(url, 'http://localhost').pathname
    if (path.endsWith('/capture-groups')) {
      return Promise.resolve(
        jsonResponse({ items: groups.map(summaryFromGroup), next_cursor: null }),
      )
    }
    if (path.endsWith('/annotation-tasks')) return Promise.resolve(jsonResponse(taskList(tasks)))
    const group = groups.find((candidate) =>
      url.endsWith(`/capture-groups/${candidate.capture_group_id}`),
    )
    if (group) {
      return Promise.resolve(jsonResponse(group))
    }
    if (url.includes('/quality-review')) {
      const group = groups.find((candidate) => url.includes(candidate.capture_group_id))
      return Promise.resolve(
        jsonResponse(
          (group && options.qualityByGroup?.[group.capture_group_id]) ??
            (group
              ? qualityFixture(
                  group,
                  group.missing_camera_numbers.length ? 'QUALIFIED_WITH_EXCLUSIONS' : 'QUALIFIED',
                )
              : qualityFixture(groups[0]!, 'QUALIFIED')),
        ),
      )
    }
    return Promise.reject(new Error(`Unexpected fetch: ${url}`))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function stubPagedGroupList(
  pages: Array<{ group: CaptureGroup; nextCursor: string | null }>,
  tasks: AnnotationTask[],
) {
  let pageIndex = 0
  const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = String(input)
    const path = new URL(url, 'http://localhost').pathname
    if (path.endsWith('/capture-groups')) {
      const page = pages[Math.min(pageIndex++, pages.length - 1)]!
      return Promise.resolve(
        jsonResponse({ items: [summaryFromGroup(page.group)], next_cursor: page.nextCursor }),
      )
    }
    if (path.endsWith('/annotation-tasks')) return Promise.resolve(jsonResponse(taskList(tasks)))
    if (url.includes('/quality-review')) {
      const page = pages.find(({ group }) => url.includes(group.capture_group_id)) ?? pages[0]!
      return Promise.resolve(jsonResponse(qualityFixture(page.group, 'QUALIFIED')))
    }
    return Promise.reject(new Error(`Unexpected fetch: ${url}`))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function summaryFromGroup(group: CaptureGroup): CaptureGroupSummary {
  return {
    capture_group_id: group.capture_group_id,
    capture_group_key: group.capture_group_key,
    device_time_utc: group.device_time_utc,
    receipt_state: group.receipt_state,
    expected_camera_count: group.expected_camera_count,
    received_camera_count: group.received_camera_count,
    missing_camera_numbers: group.missing_camera_numbers,
    created_at: group.created_at,
  }
}

function stubGroupDetail(
  group: CaptureGroup,
  options: {
    task: AnnotationTask | null
    quality: CaptureGroupQualityReview
    candidateStatus: 'FAILED' | 'NO_HAND_DETECTED'
    candidateStatusByCamera?: Record<number, 'FAILED' | 'NO_HAND_DETECTED'>
  },
) {
  const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.endsWith(`/capture-groups/${group.capture_group_id}`))
      return Promise.resolve(jsonResponse(group))
    if (url.endsWith(`/capture-groups/${group.capture_group_id}/quality-review`)) {
      return Promise.resolve(jsonResponse(options.quality))
    }
    if (url.endsWith(`/capture-groups/${group.capture_group_id}/quality-review/history`)) {
      return Promise.resolve(
        jsonResponse({ items: options.quality.result === 'UNREVIEWED' ? [] : [options.quality] }),
      )
    }
    if (url.endsWith('/annotation-tasks'))
      return Promise.resolve(jsonResponse(taskList(options.task ? [options.task] : [])))
    if (url.endsWith('/annotation-label-schema'))
      return Promise.resolve(jsonResponse(labelSchema()))
    if (url.includes('/quality-review') && url.includes('/clip-assets/')) {
      const clipAssetId = url.split('/clip-assets/')[1]?.split('/')[0]
      return Promise.resolve(jsonResponse(viewQuality(group, clipAssetId ?? '', options.quality)))
    }
    if (
      url.includes('/clip-assets/') &&
      !url.includes('/quality-review') &&
      !url.includes('/media-report')
    ) {
      const clipAssetId = url.split('/clip-assets/')[1]
      return Promise.resolve(jsonResponse(clipAsset(group, clipAssetId ?? '')))
    }
    if (url.endsWith('/hand-pose-candidates')) {
      const clipAssetId = url.split('/videos/')[1]?.split('/')[0]
      const cameraNumber = group.streams.find(
        (stream) => stream.clip_asset_id === clipAssetId,
      )?.camera_number
      const status =
        (cameraNumber !== undefined
          ? options.candidateStatusByCamera?.[cameraNumber]
          : undefined) ?? options.candidateStatus
      return Promise.resolve(jsonResponse(handPoseResponse(group, clipAssetId ?? '', status)))
    }
    if (url.endsWith('/media-report')) return Promise.resolve(jsonResponse(mediaReport(group, url)))
    if (url.endsWith('/primary-view') && init?.method === 'PUT') {
      return Promise.resolve(jsonResponse(options.task ?? {}))
    }
    if (url.endsWith('/revisions') && init?.method === 'POST') {
      return Promise.resolve(
        jsonResponse({
          annotation_revision_id: '90000000-0000-4000-8000-000000000001',
          project_id: projectId,
          annotation_task_id: taskId,
          clip_asset_id: group.streams[0]?.clip_asset_id,
          revision_number: 1,
          submitted_by: 'reviewer',
          submitted_at: '2026-08-20T01:00:00Z',
        }),
      )
    }
    return Promise.reject(new Error(`Unexpected fetch: ${init?.method ?? 'GET'} ${url}`))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function captureGroupFixture(
  captureGroupId: string,
  options: {
    expected: number[]
    received: number[]
    receipt: 'RECEIVING' | 'COMPLETE' | 'INCOMPLETE'
    key?: string
  },
): CaptureGroup {
  const members = options.expected.map((cameraNumber) => ({
    camera_channel_id: `40000000-0000-4000-8000-${String(cameraNumber).padStart(12, '0')}`,
    camera_index: cameraNumber - 1,
    camera_number: cameraNumber,
    camera_role: `camera-${cameraNumber}`,
    mount_position: `position-${cameraNumber}`,
    enabled: true,
    enabled_for_hand_detection: true,
  }))
  const streams: CaptureGroup['streams'] = options.received.map((cameraNumber) => ({
    camera_stream_id: `41000000-0000-4000-8000-${String(cameraNumber).padStart(12, '0')}`,
    clip_asset_id: `50000000-0000-4000-8000-${String(cameraNumber).padStart(12, '0')}`,
    client_file_id: `camera-${cameraNumber}.mp4`,
    camera_channel_id: `40000000-0000-4000-8000-${String(cameraNumber).padStart(12, '0')}`,
    camera_index: cameraNumber - 1,
    camera_number: cameraNumber,
    camera_role: `camera-${cameraNumber}`,
    mount_position: `position-${cameraNumber}`,
    record_start_utc: '2026-08-20T01:00:00Z',
    time_offset_ms: cameraNumber * 10,
    sync_status: cameraNumber === 1 ? 'WARNING' : 'PASS',
    stream_status: 'INGESTED',
    quality_status: 'PASS',
    enabled_for_hand_detection: true,
  }))
  return {
    capture_session_id: captureGroupId,
    capture_group_id: captureGroupId,
    project_id: projectId,
    batch_id: '42000000-0000-4000-8000-000000000001',
    device_id: 'synthetic-device',
    metadata_upload_file_id: null,
    metadata_schema_version: 'capture-session-metadata-v1',
    metadata_sha256: null,
    expected_camera_count: options.expected.length,
    received_camera_count: options.received.length,
    sync_group_id: 'sync-synthetic',
    device_time_utc: '2026-08-20T01:00:00Z',
    device_timezone: 'Asia/Shanghai',
    server_receive_time: '2026-08-20T01:00:02Z',
    assembly_status: options.receipt === 'RECEIVING' ? 'OPEN' : options.receipt,
    quality_status: 'PASS',
    capture_group_key: options.key ?? 'capture-1',
    capture_group_key_source: 'UPSTREAM_MANIFEST_KEY',
    capture_group_manifest_id: null,
    expected_camera_numbers: options.expected,
    received_camera_numbers: options.received,
    missing_camera_numbers: options.expected.filter(
      (cameraNumber) => !options.received.includes(cameraNumber),
    ),
    receipt_state: options.receipt,
    receipt_finalized_at: options.receipt === 'RECEIVING' ? null : '2026-08-20T01:01:00Z',
    camera_set_snapshot: {
      camera_set_snapshot_id: '43000000-0000-4000-8000-000000000001',
      source_camera_set_id: '44000000-0000-4000-8000-000000000001',
      camera_set_key: 'synthetic-set',
      configuration_version: 1,
      layout_version: 'v1',
      expected_camera_count: options.expected.length,
      members,
    },
    streams,
    created_at: '2026-08-20T01:00:02Z',
  }
}

function qualityFixture(
  group: CaptureGroup,
  result: CaptureGroupQualityReview['result'],
  options: { rejectedCamera?: number } = {},
): CaptureGroupQualityReview {
  return {
    capture_group_quality_review_id:
      result === 'UNREVIEWED' ? null : '45000000-0000-4000-8000-000000000001',
    project_id: projectId,
    capture_group_id: group.capture_group_id,
    result,
    reason: result === 'UNREVIEWED' ? null : 'synthetic human decision',
    reviewer_actor_id: result === 'UNREVIEWED' ? null : 'reviewer',
    revision: result === 'UNREVIEWED' ? 0 : 1,
    version: result === 'UNREVIEWED' ? 0 : 1,
    decided_at: result === 'UNREVIEWED' ? null : '2026-08-20T01:02:00Z',
    confirmed_view_set:
      result === 'UNREVIEWED'
        ? null
        : {
            schema_version: 'capture-group-view-set.v1',
            capture_group_id: group.capture_group_id,
            capture_group_version: 1,
            camera_set_snapshot_id: group.camera_set_snapshot.camera_set_snapshot_id,
            receipt_state: group.receipt_state,
            views: group.expected_camera_numbers.map((cameraNumber) => {
              const stream = group.streams.find(
                (candidate) => candidate.camera_number === cameraNumber,
              )
              const rejected = options.rejectedCamera === cameraNumber
              return {
                camera_number: cameraNumber,
                camera_stream_id: stream?.camera_stream_id ?? null,
                clip_asset_id: stream?.clip_asset_id ?? null,
                camera_channel_id: stream?.camera_channel_id ?? null,
                present: Boolean(stream),
                media_status: stream?.stream_status ?? 'MISSING',
                technical_quality_status: stream?.quality_status ?? null,
                stream_version: 1,
                clip_asset_version: stream ? 1 : 0,
                asset_available: Boolean(stream),
                quality_review_decision: rejected
                  ? 'REJECTED'
                  : stream
                    ? 'QUALIFIED'
                    : 'UNREVIEWED',
                quality_review_version: rejected || stream ? 1 : 0,
              }
            }),
          },
    input_version: result === 'UNREVIEWED' ? null : 'capture-group-view-set.v1:synthetic',
    confirmed_view_set_sha256: null,
  }
}

function viewQuality(group: CaptureGroup, clipAssetId: string, quality: CaptureGroupQualityReview) {
  const view = quality.confirmed_view_set?.views.find(
    (candidate) => candidate.clip_asset_id === clipAssetId,
  )
  return {
    quality_review_id:
      view?.quality_review_decision === 'REJECTED' ? '46000000-0000-4000-8000-000000000001' : null,
    project_id: projectId,
    clip_asset_id: clipAssetId,
    decision: view?.quality_review_decision === 'REJECTED' ? 'REJECTED' : 'QUALIFIED',
    reason_code:
      view?.quality_review_decision === 'REJECTED' ? 'OTHER_QUALITY_FAILURE' : 'MEETS_REQUIREMENTS',
    reviewed_by: 'reviewer',
    reviewed_at: '2026-08-20T01:02:00Z',
    version: view?.quality_review_version ?? 0,
  }
}

function groupTask(
  captureGroupId: string,
  canSubmit: boolean,
  options: { revision?: boolean } = {},
): AnnotationTask {
  return {
    annotation_task_id: taskId,
    project_id: projectId,
    clip_asset_id: null,
    capture_group_id: captureGroupId,
    primary_clip_asset_id: clipIds[0] ?? null,
    primary_camera_number: 1,
    capture_group_quality_review_id: '45000000-0000-4000-8000-000000000001',
    group_quality_revision: 1,
    confirmed_view_set: null,
    group_input_version: 'capture-group-view-set.v1:synthetic',
    label_schema_id: schemaId,
    label_schema_digest: 'a'.repeat(64),
    assignee_actor_id: 'reviewer',
    created_by: 'system',
    status: options.revision ? 'COMPLETED' : 'ASSIGNED',
    can_submit: options.revision ? false : canSubmit,
    can_review: false,
    can_recheck_post_cleaning: Boolean(options.revision),
    annotation_revision: options.revision
      ? {
          annotation_revision_id: '47000000-0000-4000-8000-000000000001',
          label_schema_id: schemaId,
          label_schema_digest: 'a'.repeat(64),
          revision_number: 1,
          annotation_document: {
            schema_version: 'annotation-document.v1',
            segments: [{ start_ms: 0, end_ms: 1000, label: 'PREPARE' }],
          },
          submitted_by: 'reviewer',
          submitted_at: '2026-08-20T01:03:00Z',
          review: {
            annotation_review_id: '48000000-0000-4000-8000-000000000001',
            project_id: projectId,
            annotation_revision_id: '47000000-0000-4000-8000-000000000001',
            decision: 'APPROVED',
            review_note: null,
            reviewed_by: 'admin',
            reviewed_at: '2026-08-20T01:04:00Z',
          },
          post_cleaning_result: {
            cleaning_result_id: '49000000-0000-4000-8000-000000000001',
            cleaning_run_id: '4a000000-0000-4000-8000-000000000001',
            rule_set_key: 'capture-group-cleaning',
            rule_set_version: 'v1',
            rule_set_digest: 'b'.repeat(64),
            outcome: 'PASS',
            reason_codes: [],
            executed_checks: ['GROUP_QUALITY'],
            evaluated_at: '2026-08-20T01:05:00Z',
          },
        }
      : null,
    created_at: '2026-08-20T01:02:00Z',
    updated_at: '2026-08-20T01:04:00Z',
    version: options.revision ? 2 : 0,
  }
}

function taskList(items: AnnotationTask[]) {
  return {
    items,
    progress: {
      scope: 'PROJECT',
      total: items.length,
      awaiting_submission: items.filter((item) => item.status === 'ASSIGNED').length,
      rework_required: 0,
      awaiting_review: items.filter((item) => item.status === 'SUBMITTED').length,
      completed: items.filter((item) => item.status === 'COMPLETED').length,
    },
  }
}

function labelSchema() {
  return {
    label_schema_id: schemaId,
    schema_key: 'medication-primary-step',
    schema_version: 'p0-primary-step.v1',
    schema_digest: 'a'.repeat(64),
    layer: 'primary_step',
    labels: [
      {
        label_id: 'PREPARE',
        display_name: '准备',
        description: '准备动作',
        sort_order: 1,
      },
    ],
  }
}

function clipAsset(group: CaptureGroup, clipAssetId: string) {
  const stream = group.streams.find((candidate) => candidate.clip_asset_id === clipAssetId)
  return {
    clip_asset_id: clipAssetId,
    batch_id: group.batch_id,
    source_upload_file_id: 'source-file',
    client_file_id: stream?.client_file_id ?? 'unknown.mp4',
    content_type: 'video/mp4',
    size_bytes: 1024,
    sha256: 'c'.repeat(64),
    status: stream?.stream_status === 'INGESTED' ? 'INGESTED' : 'INGEST_FAILED',
    session_binding: null,
    created_at: group.created_at,
    updated_at: group.created_at,
  }
}

function handPoseResponse(
  group: CaptureGroup,
  clipAssetId: string,
  status: 'FAILED' | 'NO_HAND_DETECTED',
) {
  return {
    job: {
      ai_job_id: '4b000000-0000-4000-8000-000000000001',
      project_id: projectId,
      clip_asset_id: clipAssetId,
      algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
      algorithm_version: 'v1',
      model_sha256: 'd'.repeat(64),
      input_sha256: 'e'.repeat(64),
      input_size_bytes: 1024,
      sampling_fps: 10,
      profile_sha256: 'f'.repeat(64),
      status,
      hand_result: status === 'NO_HAND_DETECTED' ? 'NO_HAND_DETECTED' : 'UNKNOWN',
      run_summary: {
        analysis_complete: status === 'NO_HAND_DETECTED',
        hand_result: status === 'NO_HAND_DETECTED' ? 'NO_HAND_DETECTED' : 'UNKNOWN',
        total_video_frames: 10,
        inference_frame_count: 10,
        frames_with_hand: 0,
        frames_without_hand: 10,
        left_hand_detection_count: 0,
        right_hand_detection_count: 0,
        invalid_landmark_count: 0,
        processing_duration_ms: 10,
        started_at: group.created_at,
        completed_at: group.created_at,
        failure_reason: status === 'FAILED' ? 'AI_HAND_POSE_INFERENCE_FAILED' : null,
        retryable: status === 'FAILED',
      },
      requested_by: 'system',
      created_at: group.created_at,
      updated_at: group.created_at,
    },
    candidates: [],
  }
}

function mediaReport(group: CaptureGroup, url: string) {
  const clipAssetId = url.split('/clip-assets/')[1]?.split('/')[0] ?? ''
  return {
    media_report_id: '4c000000-0000-4000-8000-000000000001',
    media_job_id: '4d000000-0000-4000-8000-000000000001',
    project_id: projectId,
    clip_asset_id: clipAssetId,
    container_format: 'mp4',
    duration_microseconds: 2_000_000,
    video_codec: 'h264',
    video_profile: 'High',
    width: 1280,
    height: 720,
    pixel_format: 'yuv420p',
    primary_video_bitrate_bps: 2_000_000,
    average_frame_rate_numerator: 30,
    average_frame_rate_denominator: 1,
    frame_count: 60,
    variable_frame_rate: false,
    audio_stream_count: 0,
    decode_verified: true,
    input_digest_verified: true,
    warning_codes: [],
    created_at: group.created_at,
  }
}

function jsonResponse(body: object, init?: ResponseInit) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
}
