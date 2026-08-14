import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { renderRoute } from '@/test/render'
import VideoManagementView from '@/views/VideoManagementView'

const projectId = '30000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('VideoManagementView', () => {
  it('renders the server summary with one bounded request and explicit no-hand result', async () => {
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
    expect(screen.getByText('标注链路待办')).toBeInTheDocument()
    expect(screen.getByText(/待办：标注 1/)).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '下一步' })).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '相机' })).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '留存' })).not.toBeInTheDocument()
    expect(screen.getByText('更多筛选').closest('details')).not.toHaveAttribute('open')
    expect(screen.getByText('未发现手部')).toBeInTheDocument()
    expect(screen.getByText('可检测')).toBeInTheDocument()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain(`/projects/${projectId}/videos?limit=50`)
    expect(String(fetchMock.mock.calls[0]?.[0])).not.toContain('/clip-assets')
  })

  it('does not expose retry for a terminal failed hand pose run that the server marked non-retryable', async () => {
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
    expect(screen.getByText('不可重试')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重试检测' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '进入配药动作标注' })).toBeInTheDocument()
  })

  it('keeps manual annotation available before optional hand detection is requested', async () => {
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
    expect(screen.getByRole('button', { name: '检测手部' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '进入配药动作标注' })).toBeInTheDocument()
  })

  it('presents submitted formal annotations as waiting for human review', async () => {
    const row = videoRow({
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
    expect(screen.getByRole('link', { name: '查看标注 / 复核' })).toBeInTheDocument()
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

  it('uses the retry endpoint for a retryable failed row', async () => {
    const row = videoRow({ hand_status: 'FAILED', hand_result: 'UNKNOWN', hand_retryable: true })
    const queuedJob = {
      ai_job_id: 'b1000000-0000-4000-8000-000000000001',
      status: 'QUEUED',
      hand_result: 'UNKNOWN',
    }
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/video-data/summary')) return Promise.resolve(jsonResponse(summaryBody()))
      if (init?.method === 'POST' && url.endsWith('/hand-pose-detections/retry')) {
        return Promise.resolve(jsonResponse(queuedJob, 202))
      }
      return Promise.resolve(jsonResponse({ items: [row], next_cursor: null }))
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('video-retry-ui-0001') })
    const user = userEvent.setup()

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByRole('link', { name: '进入配药动作标注' })).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: '重试检测' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/videos/50000000-0000-4000-8000-000000000001/hand-pose-detections/retry`,
        expect.objectContaining({
          method: 'POST',
          headers: expect.objectContaining({ 'Idempotency-Key': 'video-retry-ui-0001' }),
        }),
      ),
    )
    expect(await screen.findByText('已重新排队手部检测。')).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) =>
          String(input).endsWith('/hand-pose-detections') && init?.method === 'POST',
      ),
    ).toBe(false)
  })
})

function videoRow(
  overrides: Partial<{
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
