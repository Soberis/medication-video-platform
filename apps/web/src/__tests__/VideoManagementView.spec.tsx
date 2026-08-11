import { screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { renderRoute } from '@/test/render'
import VideoManagementView from '@/views/VideoManagementView'

const projectId = '30000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('VideoManagementView', () => {
  it('renders the server summary with one bounded request and explicit no-hand result', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        items: [
          {
            video_id: '50000000-0000-4000-8000-000000000001',
            display_name: 'synthetic-no-hand.mp4',
            camera_number: 1,
            captured_at: '2026-08-11T02:00:00Z',
            received_at: '2026-08-11T02:00:03Z',
            media_status: 'INGESTED',
            hand_status: 'SUCCEEDED',
            hand_result: 'NO_HAND_DETECTED',
            annotation_status: 'NOT_STARTED',
            cleaning_status: 'NOT_STARTED',
            retention_status: 'NOT_ASSESSED',
          },
        ],
        next_cursor: null,
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    renderRoute(<VideoManagementView />, {
      route: `/projects/${projectId}/videos`,
      path: '/projects/:projectId/videos',
    })

    expect(await screen.findByRole('heading', { name: '视频管理' })).toBeInTheDocument()
    expect(await screen.findByText('synthetic-no-hand.mp4')).toBeInTheDocument()
    expect(screen.getByText('未发现手部')).toBeInTheDocument()
    expect(screen.getByText('可检测')).toBeInTheDocument()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain(`/projects/${projectId}/videos?limit=50`)
    expect(String(fetchMock.mock.calls[0]?.[0])).not.toContain('/clip-assets')
  })
})

function jsonResponse(body: object) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
