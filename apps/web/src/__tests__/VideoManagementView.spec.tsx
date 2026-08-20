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
    expect(await screen.findByText('synthetic-no-hand.mp4')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'synthetic-no-hand.mp4' })).toHaveAttribute(
      'href',
      expect.stringContaining(`/projects/${projectId}/assets/`),
    )
    expect(screen.queryByText('V1.18 P0 · 单视频闭环')).not.toBeInTheDocument()
    expect(screen.getByText('待处理')).toBeInTheDocument()
    expect(screen.getByText(/标注 1 · 复核 0 · 清洗 0/)).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '下一步' })).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '相机' })).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '留存' })).not.toBeInTheDocument()
    expect(screen.getByText('更多筛选').closest('details')).not.toHaveAttribute('open')
    expect(screen.queryByRole('columnheader', { name: '手部检测' })).not.toBeInTheDocument()
    expect(screen.queryByText('未发现手部')).not.toBeInTheDocument()
    await user.click(screen.getByText('更多筛选'))
    expect(screen.getByText('手部候选状态')).toBeInTheDocument()
    expect(screen.getByText('待人工质量判断')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '下一步：人工质量判断' })).toHaveAttribute(
      'href',
      expect.stringContaining(`/projects/${projectId}/assets/`),
    )
    expect(screen.getByText('可检测')).toBeInTheDocument()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain(`/projects/${projectId}/videos?limit=50`)
    expect(String(fetchMock.mock.calls[0]?.[0])).not.toContain('/clip-assets')
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
    expect(screen.getByRole('link', { name: '下一步：人工质量判断' })).toBeInTheDocument()
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
    expect(screen.getByRole('link', { name: '下一步：人工质量判断' })).toBeInTheDocument()
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

    expect(await screen.findByText('等待人工复核')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '下一步：人工复核' })).toBeInTheDocument()
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
    expect(await screen.findByText('数据管理汇总暂不可用，视频列表仍可使用。')).toBeInTheDocument()
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

    expect(await screen.findByLabelText('数据管理汇总')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /刷\s*新/ }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '暂时无法读取视频管理列表，请稍后重试。',
    )
    expect(screen.queryByLabelText('数据管理汇总')).not.toBeInTheDocument()
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

    expect(await screen.findByRole('link', { name: '下一步：进入人工标注' })).toBeInTheDocument()
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
    expect(screen.getByText('视频检测中')).toBeInTheDocument()
    expect(listRequests).toBe(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_500)
    })
    expect(screen.getByText('可检测')).toBeInTheDocument()
    expect(screen.getByText('视频检测完成')).toBeInTheDocument()
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

    expect(await screen.findByText('需重新人工质量判断')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '下一步：人工质量判断' })).toHaveAttribute(
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

  it('routes a qualified unassigned video to its detail gate', async () => {
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
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByText('待进入人工标注')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '下一步：进入人工标注' })).toHaveAttribute(
      'href',
      expect.stringContaining(`/projects/${projectId}/assets/`),
    )
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
    expect(screen.getByRole('link', { name: '下一步：进入人工标注' })).toHaveAttribute(
      'href',
      expect.stringContaining(`/projects/${projectId}/assets/`),
    )
  })
})

function videoRow(
  overrides: Partial<{
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
