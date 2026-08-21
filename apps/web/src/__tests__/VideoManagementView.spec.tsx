import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { renderRoute } from '@/test/render'
import VideoManagementView from '@/views/VideoManagementView'

const projectId = '30000000-0000-4000-8000-000000000001'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('VideoManagementView', () => {
  it('renders the server summary while keeping a no-hand candidate out of the default workflow view', async () => {
    const listResponse = jsonResponse({
      items: [
        {
          video_id: '50000000-0000-4000-8000-000000000001',
          display_name: 'synthetic-no-hand.mp4',
          source_name: 'PLATFORM_LOCAL',
          camera_number: 1,
          captured_at: '2026-08-11T02:00:00Z',
          received_at: '2026-08-11T02:00:03Z',
          media_status: 'INGESTED',
          quality_decision: 'UNREVIEWED',
          hand_status: 'SUCCEEDED',
          hand_result: 'NO_HAND_DETECTED',
          hand_retryable: false,
          annotation_status: 'NOT_STARTED',
          cleaning_status: 'NOT_STARTED',
          retention_status: 'NOT_ASSESSED',
        },
      ],
      next_cursor: null,
    })
    const fetchMock = vi.fn().mockImplementation((url: string) =>
      url.includes('/video-data/summary')
        ? jsonResponse({
            total_videos: 1,
            total_duration: 5_000_000,
            storage_bytes: 1024,
            counts_by_source: { PLATFORM_LOCAL: 1 },
            counts_by_camera: { '1': 1 },
            counts_by_status: { INGESTED: 1 },
            pending_annotation: 1,
            pending_review: 0,
            pending_cleaning: 0,
            usable_count: 0,
          })
        : listResponse,
    )
    vi.stubGlobal('fetch', fetchMock)

    const user = userEvent.setup()
    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByRole('heading', { name: '视频管理' })).toBeInTheDocument()
    expect(
      screen.getByText('按状态、来源和拍摄时间筛选项目视频，查看处理进度并进入下一步工作。'),
    ).toBeInTheDocument()
    expect(await screen.findByText('synthetic-no-hand.mp4')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /synthetic-no-hand\.mp4/ })).toHaveAttribute(
      'href',
      expect.stringContaining(`/projects/${projectId}/assets/`),
    )
    expect(screen.queryByText('V1.18 P0 · 单视频闭环')).not.toBeInTheDocument()
    expect(screen.getByText('视频总数')).toBeInTheDocument()
    expect(screen.getByText('待处理')).toBeInTheDocument()
    expect(screen.getByText(/标注 1 · 复核 0 · 清洗 0/)).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '媒体状态' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '标注' })).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '配药动作标注' })).not.toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '下一步' })).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '相机' })).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '留存' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '更多筛选' })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
    expect(screen.queryByRole('button', { name: '清除筛选' })).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '手部检测' })).not.toBeInTheDocument()
    expect(screen.queryByText('未发现手部')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '更多筛选' }))
    expect(screen.getByRole('combobox', { name: '手部候选状态' })).toBeInTheDocument()
    expect(screen.getByText('待人工质量判断')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '人工质量判断' })).toHaveAttribute(
      'href',
      expect.stringContaining(`/projects/${projectId}/assets/`),
    )
    expect(screen.getByText('媒体检测完成')).toBeInTheDocument()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain(`/projects/${projectId}/videos?limit=50`)
    expect(String(fetchMock.mock.calls[0]?.[0])).not.toContain('/clip-assets')
  })

  it('restores URL-backed filters and clears only video filter parameters from an empty result', async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation((url: string) =>
        url.includes('/video-data/summary')
          ? jsonResponse({ ...summaryBody(), total_videos: 0, pending_annotation: 0 })
          : jsonResponse({ items: [], next_cursor: null }),
      )
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    const { router } = renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos?view=compact&mediaStatus=INGESTED&source=PLATFORM_LOCAL&handStatus=FAILED`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByText('没有符合当前筛选条件的视频')).toBeInTheDocument()
    expect(
      screen.getByText('没有符合当前筛选条件的视频').closest('.workspace-empty-state'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /更多筛选/ })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    expect(screen.getByLabelText('2 项已应用')).toHaveTextContent('2')
    expect(screen.getByText('媒体检测完成')).toBeInTheDocument()
    expect(screen.getByText('平台本地上传')).toBeInTheDocument()

    await user.click(screen.getAllByRole('button', { name: '清除筛选' })[0]!)

    await waitFor(() => expect(router.state.location.search).toBe('?view=compact'))
    expect(await screen.findByText('当前项目还没有视频')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '清除筛选' })).not.toBeInTheDocument()
  })

  it('writes a changed primary filter to the URL', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation((url: string) =>
          url.includes('/video-data/summary')
            ? jsonResponse(summaryBody())
            : jsonResponse({ items: [videoRow()], next_cursor: null }),
        ),
    )
    const user = userEvent.setup()
    const { router } = renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    await screen.findByText('synthetic-hand-failed.mp4')
    await user.click(screen.getByRole('combobox', { name: '媒体状态' }))
    const processingOptions = await screen.findAllByText('媒体检测中')
    await user.click(processingOptions[processingOptions.length - 1]!)

    await waitFor(() => expect(router.state.location.search).toBe('?mediaStatus=INGESTING'))
    expect(screen.getByRole('button', { name: '清除筛选' })).toBeInTheDocument()
  })

  it('appends the next cursor page without replacing the current videos', async () => {
    const first = videoRow()
    const second = videoRow({
      video_id: '50000000-0000-4000-8000-000000000002',
      display_name: 'synthetic-second-page.mp4',
    })
    let videoPageRequests = 0
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/video-data/summary')) return Promise.resolve(jsonResponse(summaryBody()))
      videoPageRequests += 1
      return Promise.resolve(
        videoPageRequests === 1
          ? jsonResponse({ items: [first], next_cursor: 'cursor-page-2' })
          : jsonResponse({ items: [second], next_cursor: null }),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByText('synthetic-hand-failed.mp4')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '加载更多' }))

    expect(await screen.findByText('synthetic-second-page.mp4')).toBeInTheDocument()
    expect(screen.getByText('synthetic-hand-failed.mp4')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '加载更多' })).not.toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(([input]) => String(input).includes('cursor=cursor-page-2')),
    ).toBe(true)
  })

  it('keeps the primary annotation step available when hand pose is terminally failed', async () => {
    const row = videoRow({ hand_status: 'FAILED', hand_result: 'UNKNOWN', hand_retryable: false })
    const fetchMock = vi
      .fn()
      .mockImplementation((url: string) =>
        url.includes('/video-data/summary')
          ? jsonResponse(summaryBody())
          : jsonResponse({ items: [row], next_cursor: null }),
      )
    vi.stubGlobal('fetch', fetchMock)

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByText('synthetic-hand-failed.mp4')).toBeInTheDocument()
    expect(screen.getByText('待人工质量判断')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重试检测' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '人工质量判断' })).toBeInTheDocument()
  })

  it('does not make optional hand detection a list-level action', async () => {
    const row = videoRow({ hand_status: 'NOT_REQUESTED', annotation_status: 'NOT_STARTED' })
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation((url: string) =>
          url.includes('/video-data/summary')
            ? jsonResponse(summaryBody())
            : jsonResponse({ items: [row], next_cursor: null }),
        ),
    )

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByText('synthetic-hand-failed.mp4')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '检测手部' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '人工质量判断' })).toBeInTheDocument()
  })

  it('presents submitted formal annotations as waiting for human review', async () => {
    const row = videoRow({
      quality_decision: 'QUALIFIED',
      hand_status: 'SUCCEEDED',
      hand_result: 'HAND_DETECTED',
      annotation_status: 'SUBMITTED',
    })
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation((url: string) =>
          url.includes('/video-data/summary')
            ? jsonResponse(summaryBody())
            : jsonResponse({ items: [row], next_cursor: null }),
        ),
    )

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByText('待人工复核')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '人工复核' })).toBeInTheDocument()
  })

  it('keeps the video list usable when the auxiliary summary request fails', async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation((url: string) =>
        url.includes('/video-data/summary')
          ? Promise.reject(new Error('synthetic summary outage'))
          : jsonResponse({ items: [videoRow()], next_cursor: null }),
      )
    vi.stubGlobal('fetch', fetchMock)

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByText('synthetic-hand-failed.mp4')).toBeInTheDocument()
    expect(await screen.findByText('视频摘要暂不可用，视频列表仍可使用。')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('clears the previous summary when a refreshed video page fails', async () => {
    let videoPageRequests = 0
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/video-data/summary')) return jsonResponse(summaryBody())
      videoPageRequests += 1
      return videoPageRequests === 1
        ? jsonResponse({ items: [videoRow()], next_cursor: null })
        : Promise.reject(new Error('synthetic video page outage'))
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByLabelText('项目视频摘要')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /刷\s*新/ }))

    expect(await screen.findByRole('alert')).toHaveTextContent('暂时无法读取视频列表，请稍后重试。')
    expect(screen.queryByLabelText('项目视频摘要')).not.toBeInTheDocument()
  })

  it('recovers the video workspace after retrying a failed list request', async () => {
    let videoPageRequests = 0
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/video-data/summary')) return Promise.resolve(jsonResponse(summaryBody()))
      videoPageRequests += 1
      return videoPageRequests === 1
        ? Promise.reject(new Error('synthetic initial outage'))
        : Promise.resolve(jsonResponse({ items: [videoRow()], next_cursor: null }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByRole('alert')).toHaveTextContent('暂时无法读取视频列表，请稍后重试。')
    await user.click(screen.getByRole('button', { name: /重\s*试/ }))

    expect(await screen.findByText('synthetic-hand-failed.mp4')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(videoPageRequests).toBe(2)
  })

  it('does not expose a hand-pose retry action in the video list', async () => {
    const row = videoRow({
      quality_decision: 'QUALIFIED',
      hand_status: 'FAILED',
      hand_result: 'UNKNOWN',
      hand_retryable: true,
    })
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/video-data/summary')) return Promise.resolve(jsonResponse(summaryBody()))
      return Promise.resolve(jsonResponse({ items: [row], next_cursor: null }))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByText('待系统分配标注任务')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '进入人工标注' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重试检测' })).not.toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) =>
          String(input).endsWith('/hand-pose-detections') && init?.method === 'POST',
      ),
    ).toBe(false)
  })

  it('polls transient media state and announces the terminal transition once', async () => {
    vi.useFakeTimers()
    let listRequests = 0
    const transientRow = videoRow({ media_status: 'INGESTING', quality_decision: 'QUALIFIED' })
    const ingestedRow = { ...transientRow, media_status: 'INGESTED' as const }
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/video-data/summary')) return Promise.resolve(jsonResponse(summaryBody()))
      listRequests += 1
      return Promise.resolve(
        jsonResponse({
          items: [listRequests === 1 ? transientRow : ingestedRow],
          next_cursor: null,
        }),
      )
    })
    vi.stubGlobal('fetch', fetchMock)

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(screen.getAllByText('媒体检测中').length).toBeGreaterThan(0)
    expect(listRequests).toBe(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_500)
    })
    expect(screen.getAllByText('媒体检测完成').length).toBeGreaterThanOrEqual(2)
    expect(listRequests).toBe(2)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000)
    })
    expect(listRequests).toBe(2)
  })

  it('keeps NEEDS_REVIEW on the quality gate before annotation', async () => {
    const row = videoRow({ quality_decision: 'NEEDS_REVIEW' })
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation((url: string) =>
          url.includes('/video-data/summary')
            ? jsonResponse(summaryBody())
            : jsonResponse({ items: [row], next_cursor: null }),
        ),
    )

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByText('需要重新判断质量')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '人工质量判断' })).toHaveAttribute(
      'href',
      expect.stringContaining(`/projects/${projectId}/assets/`),
    )
  })

  it('does not offer annotation when quality is rejected', async () => {
    const row = videoRow({ quality_decision: 'REJECTED' })
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation((url: string) =>
          url.includes('/video-data/summary')
            ? jsonResponse(summaryBody())
            : jsonResponse({ items: [row], next_cursor: null }),
        ),
    )

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByText('质量判断未通过')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '查看质量结论' })).toHaveAttribute(
      'href',
      expect.stringContaining(`/projects/${projectId}/assets/`),
    )
    expect(screen.queryByText('下一步：进入人工标注')).not.toBeInTheDocument()
  })

  it('waits for V1.24 automatic assignment instead of exposing a manual assignment route', async () => {
    const row = videoRow({ quality_decision: 'QUALIFIED' })
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation((url: string) =>
          url.includes('/video-data/summary')
            ? jsonResponse(summaryBody())
            : jsonResponse({ items: [row], next_cursor: null }),
        ),
    )

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos?mediaStatus=INGESTED`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByText('待系统分配标注任务')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '进入人工标注' })).not.toBeInTheDocument()
  })

  it('keeps an assigned qualified video on the annotation task page', async () => {
    const row = videoRow({ quality_decision: 'QUALIFIED', annotation_status: 'ASSIGNED' })
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation((url: string) =>
          url.includes('/video-data/summary')
            ? jsonResponse(summaryBody())
            : jsonResponse({ items: [row], next_cursor: null }),
        ),
    )

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByRole('link', { name: '继续人工标注' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '继续人工标注' })).toHaveAttribute(
      'href',
      expect.stringContaining(`/projects/${projectId}/annotation-tasks`),
    )
  })

  it('keeps manual annotation available when no hand is detected after qualification', async () => {
    const row = videoRow({
      quality_decision: 'QUALIFIED',
      hand_status: 'SUCCEEDED',
      hand_result: 'NO_HAND_DETECTED',
      annotation_status: 'ASSIGNED',
    })
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation((url: string) =>
          url.includes('/video-data/summary')
            ? jsonResponse(summaryBody())
            : jsonResponse({ items: [row], next_cursor: null }),
        ),
    )

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByText('synthetic-hand-failed.mp4')).toBeInTheDocument()
    expect(screen.queryByText('未发现手部')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '继续人工标注' })).toHaveAttribute(
      'href',
      expect.stringContaining(`/projects/${projectId}/annotation-tasks`),
    )
  })

  it('keeps same-named uploads distinguishable by their immutable asset ids', async () => {
    const first = videoRow({
      video_id: '50000000-0000-4000-8000-000000000001',
      display_name: 'video-1.mp4',
      received_at: '2026-08-20T09:24:39Z',
    })
    const second = videoRow({
      video_id: '50000000-0000-4000-8000-000000000002',
      display_name: 'video-1.mp4',
      received_at: '2026-08-19T14:33:50Z',
    })
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation((url: string) =>
          url.includes('/video-data/summary')
            ? jsonResponse(summaryBody())
            : jsonResponse({ items: [first, second], next_cursor: null }),
        ),
    )

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    const links = await screen.findAllByRole('link', { name: /视频 video-1\.mp4，视频资产 ID/ })
    expect(links).toHaveLength(2)
    expect(links[0]).toHaveAccessibleName(
      '视频 video-1.mp4，视频资产 ID 50000000-0000-4000-8000-000000000001',
    )
    expect(links[1]).toHaveAccessibleName(
      '视频 video-1.mp4，视频资产 ID 50000000-0000-4000-8000-000000000002',
    )
    expect(links[0]).toHaveAttribute('title', 'video-1.mp4')
    expect(links[1]).toHaveAttribute('title', 'video-1.mp4')
    expect(screen.getByText('视频 ID 50000000…0001')).toBeInTheDocument()
    expect(screen.getByText('视频 ID 50000000…0002')).toBeInTheDocument()
  })
})

function videoRow(
  overrides: Partial<{
    video_id: string
    display_name: string
    received_at: string
    media_status: 'UPLOADED' | 'INGESTING' | 'INGESTED' | 'INGEST_FAILED'
    quality_decision: 'UNREVIEWED' | 'QUALIFIED' | 'REJECTED' | 'NEEDS_REVIEW'
    hand_status: 'NOT_REQUESTED' | 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
    hand_result: 'UNKNOWN' | 'HAND_DETECTED' | 'NO_HAND_DETECTED'
    hand_retryable: boolean
    annotation_status: 'NOT_STARTED' | 'ASSIGNED' | 'SUBMITTED' | 'COMPLETED'
    cleaning_status: 'NOT_STARTED' | 'PASS' | 'WARNING' | 'REJECT'
  }> = {},
) {
  return {
    video_id: '50000000-0000-4000-8000-000000000001',
    display_name: 'synthetic-hand-failed.mp4',
    source_name: 'PLATFORM_LOCAL',
    camera_number: 1,
    captured_at: '2026-08-11T02:00:00Z',
    received_at: '2026-08-11T02:00:03Z',
    media_status: 'INGESTED' as const,
    quality_decision: 'UNREVIEWED' as const,
    hand_status: 'NOT_REQUESTED' as const,
    hand_result: 'UNKNOWN' as const,
    hand_retryable: false,
    annotation_status: 'NOT_STARTED',
    cleaning_status: 'NOT_STARTED',
    retention_status: 'NOT_ASSESSED',
    ...overrides,
  }
}

function summaryBody() {
  return {
    total_videos: 1,
    total_duration: 5_000_000,
    storage_bytes: 1024,
    counts_by_source: { PLATFORM_LOCAL: 1 },
    counts_by_camera: { '1': 1 },
    counts_by_status: { INGESTED: 1 },
    pending_annotation: 1,
    pending_review: 0,
    pending_cleaning: 0,
    usable_count: 0,
  }
}

function jsonResponse(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
