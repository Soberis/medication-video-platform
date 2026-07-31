import { afterEach, describe, expect, it, vi } from 'vitest'

import { getQualityReview, recordQualityReview } from '@/api/quality'

const projectId = '30000000-0000-4000-8000-000000000001'
const clipAssetId = '50000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('quality review API', () => {
  it('reads and updates the project-scoped quality fact with its expected version', async () => {
    const current = {
      quality_review_id: null,
      project_id: projectId,
      clip_asset_id: clipAssetId,
      decision: 'UNREVIEWED',
      reason_code: null,
      reviewed_by: null,
      reviewed_at: null,
      version: 0,
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(current))
      .mockResolvedValueOnce(
        jsonResponse({
          ...current,
          quality_review_id: '60000000-0000-4000-8000-000000000001',
          decision: 'QUALIFIED',
          reason_code: 'MEETS_REQUIREMENTS',
          reviewed_by: 'reviewer',
          reviewed_at: '2026-07-22T01:02:03Z',
        }),
      )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('quality-request-0001') })

    await expect(getQualityReview(projectId, clipAssetId)).resolves.toMatchObject({
      decision: 'UNREVIEWED',
    })
    await expect(
      recordQualityReview(projectId, clipAssetId, {
        decision: 'QUALIFIED',
        reason_code: 'MEETS_REQUIREMENTS',
        expected_version: 0,
      }),
    ).resolves.toMatchObject({ decision: 'QUALIFIED' })

    expect(fetchMock).toHaveBeenLastCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${clipAssetId}/quality-review`,
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({
          decision: 'QUALIFIED',
          reason_code: 'MEETS_REQUIREMENTS',
          expected_version: 0,
        }),
        headers: expect.objectContaining({ 'X-Request-Id': 'quality-request-0001' }),
      }),
    )
  })
})

function jsonResponse(body: object) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
