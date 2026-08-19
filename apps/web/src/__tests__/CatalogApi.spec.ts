import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  authorizeClipAssetPlayback,
  deleteClipAsset,
  getDataManagementSummary,
  listClipAssetActivity,
  listVideoSummaries,
  requestHandPoseCandidates,
  requestClipAssetMediaProbe,
} from '@/api/catalog'

const projectId = '30000000-0000-4000-8000-000000000001'
const clipAssetId = '50000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('catalog API', () => {
  it('uses the identical bounded filter set for video rows and aggregate summary', async () => {
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(JSON.stringify({ items: [], next_cursor: null }), {
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    const filters = {
      source: 'PLATFORM_LOCAL' as const,
      mediaStatus: 'INGESTED' as const,
      handStatus: 'SUCCEEDED' as const,
      handResult: 'NO_HAND_DETECTED' as const,
      annotationStatus: 'COMPLETED' as const,
      cleaningStatus: 'PASS' as const,
      cameraNumber: 5,
      capturedFrom: '2026-08-10T00:00:00Z',
      capturedBefore: '2026-08-11T00:00:00Z',
    }

    await listVideoSummaries(projectId, filters, null)
    await getDataManagementSummary(projectId, filters)

    const rowUrl = new URL(String(fetchMock.mock.calls[0]?.[0]), 'http://localhost')
    const summaryUrl = new URL(String(fetchMock.mock.calls[1]?.[0]), 'http://localhost')
    for (const key of [
      'source',
      'media_status',
      'hand_status',
      'hand_result',
      'annotation_status',
      'cleaning_status',
      'camera_number',
      'captured_from',
      'captured_before',
    ]) {
      expect(summaryUrl.searchParams.get(key)).toBe(rowUrl.searchParams.get(key))
    }
  })

  it('soft deletes a clip asset with a fresh idempotency key and a reason', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('delete-idempotency-key-0001') })

    await expect(deleteClipAsset(projectId, clipAssetId, '上传错误')).resolves.toBeUndefined()

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${clipAssetId}`,
      expect.objectContaining({
        method: 'DELETE',
        body: JSON.stringify({ reason: '上传错误' }),
        headers: expect.objectContaining({
          'Idempotency-Key': 'delete-idempotency-key-0001',
        }),
      }),
    )
  })

  it('requests a no-store data-review playback grant without persisting the URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          grant_id: '70000000-0000-4000-8000-000000000001',
          purpose: 'DATA_REVIEW',
          media_url: 'https://storage.invalid/review.mp4?signature=synthetic',
          expires_at: '2026-07-21T01:08:03Z',
          content_type: 'video/mp4',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(authorizeClipAssetPlayback(projectId, clipAssetId)).resolves.toMatchObject({
      purpose: 'DATA_REVIEW',
      content_type: 'video/mp4',
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${clipAssetId}/playback-grants`,
      expect.objectContaining({ method: 'POST', cache: 'no-store' }),
    )
  })

  it('explicitly queues a media probe before the user asks to play an uploaded video', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          media_job_id: '80000000-0000-4000-8000-000000000001',
          project_id: projectId,
          clip_asset_id: clipAssetId,
          job_type: 'MEDIA_PROBE',
          status: 'QUEUED',
        }),
        { status: 202, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('media-probe-idempotency-0001') })

    await expect(requestClipAssetMediaProbe(projectId, clipAssetId)).resolves.toMatchObject({
      job_type: 'MEDIA_PROBE',
      status: 'QUEUED',
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${clipAssetId}/media-probe-jobs`,
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'Idempotency-Key': 'media-probe-idempotency-0001' }),
      }),
    )
  })

  it('queues hand pose candidates without caching the request', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          ai_job_id: '81000000-0000-4000-8000-000000000001',
          project_id: projectId,
          clip_asset_id: clipAssetId,
          algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
          algorithm_version: 'v1',
          model_sha256: 'a'.repeat(64),
          input_sha256: 'b'.repeat(64),
          input_size_bytes: 8388608,
          sampling_fps: 10,
          status: 'QUEUED',
          requested_by: 'local-synthetic-admin',
          created_at: '2026-08-10T01:00:00Z',
          updated_at: '2026-08-10T01:00:00Z',
        }),
        { status: 202, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('hand-pose-idempotency-0001') })

    await expect(requestHandPoseCandidates(projectId, clipAssetId)).resolves.toMatchObject({
      algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
      status: 'QUEUED',
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/videos/${clipAssetId}/hand-pose-detections`,
      expect.objectContaining({
        method: 'POST',
        cache: 'no-store',
        headers: expect.objectContaining({ 'Idempotency-Key': 'hand-pose-idempotency-0001' }),
      }),
    )
  })

  it('reads minimized asset activity with an opaque cursor and no-store semantics', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          items: [
            {
              event_id: '71000000-0000-4000-8000-000000000001',
              occurred_at_utc: '2026-07-29T02:00:00Z',
              actor_type: 'USER',
              actor_id: 'reviewer',
              action: 'CLIP_ASSET_PLAYBACK_GRANTED',
              object_type: 'ClipAsset',
              object_id: clipAssetId,
              object_version: '1',
              outcome: 'SUCCEEDED',
              reason_code: 'DATA_REVIEW',
              request_id: 'playback-request-0001',
            },
          ],
          next_cursor: 'opaque-next-cursor',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()

    await expect(
      listClipAssetActivity(projectId, clipAssetId, 'opaque-previous-cursor', controller.signal),
    ).resolves.toMatchObject({
      items: [{ action: 'CLIP_ASSET_PLAYBACK_GRANTED' }],
      next_cursor: 'opaque-next-cursor',
    })

    const [url, options] = fetchMock.mock.calls[0] as [string, RequestInit]
    const requestUrl = new URL(url, 'http://localhost')
    expect(requestUrl.pathname).toBe(
      `/api/v1/projects/${projectId}/clip-assets/${clipAssetId}/activity`,
    )
    expect(requestUrl.searchParams.get('limit')).toBe('20')
    expect(requestUrl.searchParams.get('cursor')).toBe('opaque-previous-cursor')
    expect(options).toMatchObject({ method: 'GET', cache: 'no-store', signal: controller.signal })
  })
})
