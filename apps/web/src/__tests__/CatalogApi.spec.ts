import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  authorizeClipAssetPlayback,
  getLatestHandPoseCandidates,
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

  it('fetches latest hand pose candidates with no-store', async () => {
    const candidateData = {
      job: {
        ai_job_id: '81000000-0000-4000-8000-000000000001',
        project_id: projectId,
        clip_asset_id: clipAssetId,
        algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
        algorithm_version: 'v1',
        model_sha256: 'a'.repeat(64),
        input_sha256: 'b'.repeat(64),
        input_size_bytes: 8388608,
        sampling_fps: 10,
        profile_sha256: 'c'.repeat(64),
        status: 'SUCCEEDED',
        hand_result: 'HAND_DETECTED',
        requested_by: 'system',
        created_at: '2026-08-10T01:00:00Z',
        updated_at: '2026-08-10T01:00:00Z',
      },
      candidates: [
        {
          frame_timestamp_ms: 500,
          image_width: 1920,
          image_height: 1080,
          hand_index: 0,
          handedness: 'RIGHT',
          handedness_score: 0.98,
          bounding_box: { x: 100, y: 100, width: 200, height: 200 },
          landmarks: [{ x: 0.5, y: 0.5, z: 0 }],
          valid_landmark_count: 21,
        },
      ],
    }
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(candidateData), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(getLatestHandPoseCandidates(projectId, clipAssetId)).resolves.toEqual(candidateData)

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/videos/${clipAssetId}/hand-pose-candidates`,
      expect.objectContaining({ cache: 'no-store' }),
    )
  })
})
