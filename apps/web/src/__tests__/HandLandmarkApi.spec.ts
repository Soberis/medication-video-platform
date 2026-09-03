import { afterEach, describe, expect, it, vi } from 'vitest'

import { downloadHandLandmarkExport, getHandLandmarkExportPreview } from '@/api/hand-landmarks'

const projectId = '30000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('MediaPipe hand landmark export API', () => {
  it('reads the server-owned hand-landmarks.v1 preview', async () => {
    const preview = {
      schema_version: 'hand-landmarks.v1',
      hand_detection_view_count: 3,
      completed_view_count: 1,
      views_with_hand: 1,
      sample_count: 12,
      no_job_view_count: 0,
      queued_view_count: 0,
      running_view_count: 1,
      failed_view_count: 0,
      no_hand_view_count: 1,
      file_name: 'mediapipe_hand_landmarks_v1_project_20260903T112530Z.csv',
      generated_at: '2026-09-03T11:25:30Z',
      media_type: 'text/csv; charset=UTF-8',
    }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(preview))
    vi.stubGlobal('fetch', fetchMock)

    await expect(getHandLandmarkExportPreview(projectId)).resolves.toEqual(preview)

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/hand-landmarks/v1/preview`,
      expect.objectContaining({
        method: 'GET',
        headers: { Accept: 'application/json' },
        cache: 'no-store',
      }),
    )
  })

  it('downloads project and CaptureGroup scopes with the preview generation time', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('server,csv\n'))
    vi.stubGlobal('fetch', fetchMock)

    const blob = await downloadHandLandmarkExport(
      projectId,
      '2026-09-03T11:25:30Z',
      '49de2828-0000-4000-8000-000000000001',
    )
    expect(await blob.text()).toBe('server,csv\n')

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/hand-landmarks/v1/download?generated_at=2026-09-03T11%3A25%3A30Z&capture_group_id=49de2828-0000-4000-8000-000000000001`,
      expect.objectContaining({
        method: 'GET',
        headers: { Accept: 'text/csv; charset=UTF-8' },
        cache: 'no-store',
      }),
    )
  })

  it('requests a single CaptureGroup preview only when scoped', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({}))
    vi.stubGlobal('fetch', fetchMock)

    await getHandLandmarkExportPreview(projectId, '49de2828-0000-4000-8000-000000000001')

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/hand-landmarks/v1/preview?capture_group_id=49de2828-0000-4000-8000-000000000001`,
      expect.objectContaining({ method: 'GET' }),
    )
  })

  it('maps a permission-limited response to safe Chinese feedback', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse(
          { error_code: 'PROJECT_ACCESS_DENIED', error_message: 'internal permission details' },
          { status: 403 },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)

    await expect(getHandLandmarkExportPreview(projectId)).rejects.toMatchObject({
      errorCode: 'PROJECT_ACCESS_DENIED',
      message: '当前身份没有项目手部检测关键点导出的权限。',
    })
  })
})

function jsonResponse(body: object, init?: ResponseInit) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
}
