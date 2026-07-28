import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { renderRoute, renderRouter } from '@/test/render'
import CaptureEventLogListView from '@/views/CaptureEventLogListView'

const projectId = '30000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('CaptureEventLogListView', () => {
  it('shows only object-index facts and loads the next opaque page', async () => {
    const firstLog = captureEventLog('71000000-0000-4000-8000-000000000001', 'synthetic-edge-01')
    const secondLog = captureEventLog('71000000-0000-4000-8000-000000000002', 'synthetic-edge-02')
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      return Promise.resolve(
        jsonResponse(
          url.includes('cursor=opaque-next-cursor')
            ? { items: [secondLog], next_cursor: null }
            : {
                items: [{ ...firstLog, payload: 'must-not-be-rendered', storage_key: 'must-not-be-rendered' }],
                next_cursor: 'opaque-next-cursor',
              },
        ),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderEventLogs()

    expect(screen.getByRole('heading', { name: '采集事件日志' })).toBeInTheDocument()
    expect(await screen.findByText('synthetic-edge-01')).toBeInTheDocument()
    expect(screen.getByText('application/json')).toBeInTheDocument()
    expect(screen.getByText('2 KiB')).toBeInTheDocument()
    expect(screen.queryByText('must-not-be-rendered')).not.toBeInTheDocument()
    expect(screen.getByText('已显示 1 条采集事件日志索引')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '加载更多' }))

    expect(await screen.findByText('synthetic-edge-02')).toBeInTheDocument()
    expect(screen.getByText('已显示 2 条采集事件日志索引')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('cursor=opaque-next-cursor'),
      expect.objectContaining({ cache: 'no-store' }),
    )
  })

  it('applies server-supported filters and clears them explicitly', async () => {
    const batchId = '60000000-0000-4000-8000-000000000001'
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(jsonResponse({ items: [], next_cursor: null })),
    )
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderEventLogs()
    await screen.findByText('当前项目没有符合条件的采集事件日志索引。请先检查上传批次是否已完成。')

    await user.type(screen.getByLabelText('批次 ID'), batchId)
    await user.type(screen.getByLabelText('设备 ID'), 'synthetic-edge-01')
    await user.click(screen.getByRole('button', { name: '应用筛选' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    const filteredUrl = String(fetchMock.mock.calls[1]?.[0])
    expect(filteredUrl).toContain(`batch_id=${batchId}`)
    expect(filteredUrl).toContain('device_id=synthetic-edge-01')

    await user.click(screen.getByRole('button', { name: /重\s*置/ }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    const resetUrl = String(fetchMock.mock.calls[2]?.[0])
    expect(resetUrl).not.toContain('batch_id=')
    expect(resetUrl).not.toContain('device_id=')
  })

  it('shows a stable failure and keeps the page recoverable', async () => {
    let shouldFail = true
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(() =>
        Promise.resolve(
          shouldFail
            ? jsonResponse(
                {
                  error_code: 'PROJECT_NOT_FOUND',
                  error_message: 'Internal detail must not be shown.',
                  request_id: 'synthetic-request-id',
                },
                404,
              )
            : jsonResponse({ items: [captureEventLog('71000000-0000-4000-8000-000000000001')], next_cursor: null }),
        ),
      ),
    )
    const user = userEvent.setup()

    renderEventLogs()

    expect(await screen.findByText('PROJECT_NOT_FOUND')).toBeInTheDocument()
    expect(screen.getByText('未找到该项目，或当前用户尚未加入该项目。')).toBeInTheDocument()
    expect(screen.queryByText('Internal detail must not be shown.')).not.toBeInTheDocument()
    expect(screen.getByText('请求标识：synthetic-request-id')).toBeInTheDocument()

    shouldFail = false
    await user.click(screen.getByRole('button', { name: /重\s*试/ }))

    expect(await screen.findByText('synthetic-edge-01')).toBeInTheDocument()
  })

  it('does not render an old project response after the route changes', async () => {
    const otherProjectId = '30000000-0000-4000-8000-000000000002'
    let resolveOldProject: ((response: Response) => void) | undefined
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input)
        if (url.includes(`/projects/${projectId}/capture-event-logs`)) {
          return new Promise<Response>((resolve) => {
            resolveOldProject = resolve
          })
        }
        return Promise.resolve(
          jsonResponse({
            items: [captureEventLog('71000000-0000-4000-8000-000000000002', 'synthetic-edge-02')],
            next_cursor: null,
          }),
        )
      }),
    )
    const { router } = renderRouter(
      [
        {
          path: '/projects/:projectId/event-logs',
          element: <CaptureEventLogListView />,
        },
      ],
      [`/projects/${projectId}/event-logs`],
    )

    await waitFor(() => expect(resolveOldProject).toBeTypeOf('function'))
    await router.navigate(`/projects/${otherProjectId}/event-logs`)
    expect(await screen.findByText('synthetic-edge-02')).toBeInTheDocument()

    resolveOldProject?.(
      jsonResponse({
        items: [captureEventLog('71000000-0000-4000-8000-000000000001', 'synthetic-edge-01')],
        next_cursor: null,
      }),
    )

    await waitFor(() => expect(router.state.location.pathname).toBe(`/projects/${otherProjectId}/event-logs`))
    expect(screen.queryByText('synthetic-edge-01')).not.toBeInTheDocument()
  })
})

function renderEventLogs() {
  return renderRoute(<CaptureEventLogListView />, {
    route: `/projects/${projectId}/event-logs`,
    path: '/projects/:projectId/event-logs',
  })
}

function captureEventLog(captureEventLogId: string, deviceId = 'synthetic-edge-01') {
  return {
    capture_event_log_id: captureEventLogId,
    batch_id: '60000000-0000-4000-8000-000000000001',
    source_upload_file_id: '50000000-0000-4000-8000-000000000001',
    client_file_id: '40000000-0000-4000-8000-000000000001',
    device_id: deviceId,
    content_type: 'application/json',
    size_bytes: 2048,
    sha256: 'a'.repeat(64),
    indexed_at: '2026-07-28T04:00:00Z',
  }
}

function jsonResponse(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}
