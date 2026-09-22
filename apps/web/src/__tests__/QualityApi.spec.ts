import { afterEach, describe, expect, it, vi } from 'vitest'

import { getQualityReview } from '@/api/quality'

const projectId = '30000000-0000-4000-8000-000000000001'
const clipAssetId = '50000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('quality review API', () => {
  it('reads the project-scoped quality fact', async () => {
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
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(current))
    vi.stubGlobal('fetch', fetchMock)

    await expect(getQualityReview(projectId, clipAssetId)).resolves.toMatchObject({
      decision: 'UNREVIEWED',
    })

    expect(fetchMock).toHaveBeenLastCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${clipAssetId}/quality-review`,
      expect.objectContaining({ method: 'GET' }),
    )
  })
})

function jsonResponse(body: object) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
