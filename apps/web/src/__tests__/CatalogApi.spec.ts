import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  authorizeClipAssetPlayback,
  requestHandPoseCandidates,
  requestClipAssetMediaProbe,
} from '@/api/catalog'

const projectId = '30000000-0000-4000-8000-000000000001'
const clipAssetId = '50000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('catalog API', () => {
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
})
