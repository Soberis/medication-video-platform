import { App as AntdApp, ConfigProvider } from 'antd'
import { act, renderHook, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { CaptureGroup } from '@/api/capture-groups'
import { useCaptureGroupDetailData } from '@/features/capture-group/use-capture-group-detail-data'
import { renderRoute } from '@/test/render'
import CaptureGroupDetailView from '@/views/CaptureGroupDetailView'

import {
  captureGroupFixture,
  clipIds,
  groupId,
  groupTask,
  jsonResponse,
  labelSchema,
  projectId,
  qualityFixture,
  taskList,
} from './capture-group-fixtures'

afterEach(() => vi.unstubAllGlobals())

describe('CaptureGroup detail loading', () => {
  it('loads the group and first view before a pending human task response', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const taskResponse = deferredResponse()
    const fetchMock = stubPrimaryDetail(group, taskResponse.promise)

    renderRoute(<CaptureGroupDetailView />, {
      path: '/projects/:projectId/capture-groups/:captureGroupId',
      route: `/projects/${projectId}/capture-groups/${groupId}`,
    })

    expect(await screen.findByRole('heading', { level: 1, name: 'capture-1' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: '视频' })).toBeInTheDocument()
    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(([input]) =>
          String(input).includes(`/clip-assets/${clipIds[0]}`),
        ),
      ).toBe(true)
      expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith('/media-report'))).toBe(
        true,
      )
      expect(
        fetchMock.mock.calls.some(([input]) => String(input).endsWith('/hand-pose-candidates')),
      ).toBe(true)
    })
    expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith('/quality-review'))).toBe(
      false,
    )

    await act(async () => {
      taskResponse.resolve(taskList([]))
      await Promise.resolve()
    })
  })

  it('keeps the primary detail successful when the task read fails', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const fetchMock = stubHookDetail([group], {}, { taskError: true })
    const { result } = renderDetailHook(projectId, groupId)

    await waitFor(() => expect(result.current.groupLoadState).toBe('success'))
    await waitFor(() => expect(result.current.taskVisibility).toBe('unavailable'))
    expect(result.current.group?.capture_group_id).toBe(groupId)
    expect(result.current.task).toBeNull()
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/capture-groups/'))).toBe(
      true,
    )
    // 组质量准入已改为系统冻结，前端详情不再拉取人工质量事实。
    expect(
      fetchMock.mock.calls.some(([input]) =>
        String(input).includes('/capture-groups/' + groupId + '/quality-review'),
      ),
    ).toBe(false)
  })

  it('keeps ASSIGNEE empty distinct from a project-wide empty task scope while loading facts', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const assigneeFetchMock = stubHookDetail([group], {
      [projectId]: Promise.resolve(jsonResponse(taskList([], 'ASSIGNEE'))),
    })
    const assignee = renderDetailHook(projectId, groupId)
    await waitFor(() => expect(assignee.result.current.taskVisibility).toBe('permission-limited'))
    expect(assignee.result.current.task).toBeNull()
    expect(
      assigneeFetchMock.mock.calls.some(([input]) => String(input).endsWith('/quality-review')),
    ).toBe(false)

    assignee.unmount()
    const projectFetchMock = stubHookDetail([group], {
      [projectId]: Promise.resolve(jsonResponse(taskList([], 'PROJECT'))),
    })
    const project = renderDetailHook(projectId, groupId)
    await waitFor(() => expect(project.result.current.taskVisibility).toBe('visible'))
    expect(project.result.current.task).toBeNull()
    expect(
      projectFetchMock.mock.calls.some(([input]) => String(input).endsWith('/quality-review')),
    ).toBe(false)
  })

  it('loads the label schema after the optional human task becomes visible', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
    })
    const taskResponse = deferredResponse()
    const fetchMock = stubHookDetail([group], { [projectId]: taskResponse.promise })
    const { result } = renderDetailHook(projectId, groupId)

    await waitFor(() => expect(result.current.groupLoadState).toBe('success'))
    expect(result.current.task).toBeNull()
    expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith('/quality-review'))).toBe(
      false,
    )
    expect(
      fetchMock.mock.calls.some(([input]) => String(input).endsWith('/quality-review/history')),
    ).toBe(false)
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/clip-assets/'))).toBe(
      false,
    )
    expect(
      fetchMock.mock.calls.some(([input]) => String(input).endsWith('/annotation-label-schema')),
    ).toBe(false)

    await act(async () => {
      taskResponse.resolve(taskList([groupTask(groupId, true)]))
      await Promise.resolve()
    })

    await waitFor(() => expect(result.current.task?.capture_group_id).toBe(groupId))
    await waitFor(() => {
      // 组质量准入已改为系统冻结，前端不再拉取人工质量及其历史。
      expect(
        fetchMock.mock.calls.some(([input]) =>
          String(input).includes(`/capture-groups/${groupId}/quality-review`),
        ),
      ).toBe(false)
      expect(
        fetchMock.mock.calls.some(([input]) => String(input).endsWith('/annotation-label-schema')),
      ).toBe(true)
    })
    // 机位级质量评审已退出标注准入，页面不再逐机位拉取该事实。
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/clip-assets/'))).toBe(
      false,
    )
  })

  it('ignores a delayed Group A task response after switching project and group', async () => {
    const projectA = projectId
    const projectB = '30000000-0000-4000-8000-000000000002'
    const groupA = captureGroupFixture('60000000-0000-4000-8000-000000000002', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'project-a-group',
    })
    const groupBBase = captureGroupFixture('60000000-0000-4000-8000-000000000003', {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'project-b-group',
    })
    const groupB: CaptureGroup = {
      ...groupBBase,
      project_id: projectB,
      streams: groupBBase.streams.map((stream) => ({
        ...stream,
        clip_asset_id: '50000000-0000-4000-8000-000000000099',
      })),
    }
    const groupATaskResponse = deferredResponse()
    const fetchMock = stubHookDetail([groupA, groupB], {
      [projectA]: groupATaskResponse.promise,
      [projectB]: Promise.resolve(jsonResponse(taskList([], 'PROJECT'))),
    })
    const { result, rerender } = renderHook(
      ({
        currentProjectId,
        currentGroupId,
      }: {
        currentProjectId: string
        currentGroupId: string
      }) => useCaptureGroupDetailData(currentProjectId, currentGroupId),
      {
        initialProps: { currentProjectId: projectA, currentGroupId: groupA.capture_group_id },
        wrapper: AppWrapper,
      },
    )

    await waitFor(() =>
      expect(result.current.group?.capture_group_id).toBe(groupA.capture_group_id),
    )
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([input]) => String(input).endsWith('/annotation-tasks')),
      ).toBe(true),
    )

    await act(async () => {
      rerender({ currentProjectId: projectB, currentGroupId: groupB.capture_group_id })
      await Promise.resolve()
    })
    await waitFor(() =>
      expect(result.current.group?.capture_group_id).toBe(groupB.capture_group_id),
    )
    expect(result.current.group?.project_id).toBe(projectB)
    expect(result.current.selectedClipAssetId).toBe(groupB.streams[0]?.clip_asset_id)

    await act(async () => {
      groupATaskResponse.resolve(taskList([groupTask(groupA.capture_group_id, true)]))
      await Promise.resolve()
    })
    await waitFor(() =>
      expect(result.current.group?.capture_group_id).toBe(groupB.capture_group_id),
    )
    expect(result.current.task).toBeNull()
    expect(
      fetchMock.mock.calls.some(([input]) =>
        String(input).includes(`/capture-groups/${groupA.capture_group_id}/quality-review`),
      ),
    ).toBe(false)
  })

  it('automatically polls capture group while receipt_state is RECEIVING and stops once completed', async () => {
    vi.useFakeTimers()
    const receivingGroup = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1],
      receipt: 'RECEIVING',
    })
    const completedGroup = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })

    let getCount = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input)
        if (url.endsWith(`/capture-groups/${groupId}`)) {
          getCount += 1
          return Promise.resolve(jsonResponse(getCount >= 3 ? completedGroup : receivingGroup))
        }
        if (url.endsWith('/annotation-tasks')) {
          return Promise.resolve(jsonResponse(taskList([])))
        }
        if (url.endsWith('/quality-review')) {
          return Promise.resolve(jsonResponse(qualityFixture(receivingGroup, 'UNREVIEWED')))
        }
        if (url.endsWith('/quality-review-history')) {
          return Promise.resolve(jsonResponse({ history: [] }))
        }
        if (url.endsWith('/annotation-label-schema')) {
          return Promise.resolve(jsonResponse(labelSchema))
        }
        return Promise.reject(new Error(`Unhandled fetch: ${url}`))
      }),
    )

    const { result } = renderDetailHook(projectId, groupId)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(result.current.group?.receipt_state).toBe('RECEIVING')
    expect(getCount).toBe(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
    })
    expect(getCount).toBe(2)
    expect(result.current.group?.receipt_state).toBe('RECEIVING')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
    })
    expect(getCount).toBe(3)
    expect(result.current.group?.receipt_state).toBe('COMPLETE')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10000)
    })
    expect(getCount).toBe(3)

    vi.useRealTimers()
  })
})

function renderDetailHook(currentProjectId: string, currentGroupId: string) {
  return renderHook(
    ({ projectId: hookProjectId, captureGroupId: hookGroupId }) =>
      useCaptureGroupDetailData(hookProjectId, hookGroupId),
    {
      initialProps: { projectId: currentProjectId, captureGroupId: currentGroupId },
      wrapper: AppWrapper,
    },
  )
}

function AppWrapper({ children }: { children: ReactNode }) {
  return (
    <ConfigProvider>
      <AntdApp>{children}</AntdApp>
    </ConfigProvider>
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

function stubPrimaryDetail(group: CaptureGroup, taskResponse: Promise<Response>) {
  const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = String(input)
    if (url.endsWith(`/capture-groups/${group.capture_group_id}`)) {
      return Promise.resolve(jsonResponse(group))
    }
    if (url.endsWith('/annotation-tasks')) return taskResponse
    if (url.endsWith('/media-report')) {
      return Promise.resolve(
        jsonResponse({
          media_report_id: 'media-report-1',
          media_job_id: 'media-job-1',
          project_id: group.project_id,
          clip_asset_id: group.streams[0]?.clip_asset_id,
          container_format: 'mp4',
          duration_microseconds: 1_000_000,
          video_codec: 'h264',
          video_profile: null,
          width: 1280,
          height: 720,
          pixel_format: 'yuv420p',
          primary_video_bitrate_bps: null,
          average_frame_rate_numerator: 30,
          average_frame_rate_denominator: 1,
          frame_count: 30,
          variable_frame_rate: false,
          audio_stream_count: 0,
          decode_verified: true,
          input_digest_verified: true,
          warning_codes: [],
          created_at: group.created_at,
        }),
      )
    }
    if (url.endsWith('/hand-pose-candidates')) {
      return Promise.resolve(
        jsonResponse({
          job: { status: 'SUCCEEDED', hand_result: 'NO_HAND_DETECTED' },
          candidates: [],
        }),
      )
    }
    if (url.includes('/clip-assets/')) {
      const clipAssetId = url.split('/clip-assets/')[1]?.split('/')[0] ?? ''
      return Promise.resolve(
        jsonResponse({ clip_asset_id: clipAssetId, content_type: 'video/mp4', status: 'INGESTED' }),
      )
    }
    if (url.endsWith('/annotation-label-schema')) {
      return Promise.resolve(jsonResponse(labelSchema()))
    }
    return Promise.reject(new Error(`Unexpected fetch: ${url}`))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function stubHookDetail(
  groups: CaptureGroup[],
  taskResponses: Record<string, Promise<Response> | Response>,
  options: { qualityError?: boolean; taskError?: boolean } = {},
) {
  const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = String(input)
    const path = new URL(url, 'http://localhost').pathname
    const group = groups.find((candidate) =>
      path.endsWith(`/capture-groups/${candidate.capture_group_id}`),
    )
    if (group) return Promise.resolve(jsonResponse(group))
    if (path.endsWith('/annotation-tasks')) {
      const currentProjectId = path.split('/projects/')[1]?.split('/')[0] ?? ''
      if (options.taskError) return Promise.reject(new Error('TASK_LIST_UNAVAILABLE'))
      return taskResponses[currentProjectId] ?? Promise.reject(new Error('Unexpected project'))
    }
    const qualityGroup = groups.find((candidate) =>
      path.includes(`/capture-groups/${candidate.capture_group_id}/`),
    )
    if (path.endsWith('/quality-review/history') && qualityGroup) {
      return Promise.resolve(jsonResponse({ items: [] }))
    }
    if (path.endsWith('/quality-review') && qualityGroup) {
      if (options.qualityError) return Promise.reject(new Error('QUALITY_UNAVAILABLE'))
      return Promise.resolve(jsonResponse(qualityFixture(qualityGroup, 'QUALIFIED')))
    }
    if (path.endsWith('/annotation-label-schema'))
      return Promise.resolve(jsonResponse(labelSchema()))
    return Promise.reject(new Error(`Unexpected fetch: ${url}`))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}
