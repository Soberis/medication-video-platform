import { afterEach, describe, expect, it, vi } from 'vitest'

import { downloadHandLandmarkExport, getHandLandmarkExportPreview } from '@/api/hand-landmarks'

const projectId = '30000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('MediaPipe hand landmark export API', () => {
  it('reads the server-owned hand-landmarks.v1 preview', async () => {
    const preview = {
      schema_version: 'hand-landmarks.v1',
      eligible_event_count: 2,
      events_with_landmarks: 1,
      sample_count: 12,
      events_without_landmarks: 1,
      file_name: 'mediapipe_hand_landmarks_v1.csv',
      content_type: 'text/csv; charset=UTF-8',
      reason_counts: { MEDIAPIPE_NOT_FINISHED: 1 },
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

  it('downloads the server-generated CSV Blob without reading its rows', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('server,csv\n'))
    vi.stubGlobal('fetch', fetchMock)

    const blob = await downloadHandLandmarkExport(projectId)
    expect(await blob.text()).toBe('server,csv\n')

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/hand-landmarks/v1/download`,
      expect.objectContaining({
        method: 'GET',
        headers: { Accept: 'text/csv; charset=UTF-8' },
        cache: 'no-store',
      }),
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
      message: '当前身份没有项目手部训练数据导出的权限。',
    })
  })
})

function jsonResponse(body: object, init?: ResponseInit) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
}
