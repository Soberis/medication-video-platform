import { afterEach, describe, expect, it, vi } from 'vitest'

import { createPrivacyAssessment, getPrivacyStatus } from '@/api/privacy'

const projectId = '30000000-0000-4000-8000-000000000001'
const clipAssetId = '50000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('privacy API', () => {
  it('reads privacy status within an explicit project scope', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        clip_asset_id: clipAssetId,
        input_digest: 'a'.repeat(64),
        risk_level: 'UNKNOWN',
        required_action: null,
        privacy_release_status: 'BLOCKED',
        current_assessment: null,
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(getPrivacyStatus(projectId, clipAssetId)).resolves.toMatchObject({
      risk_level: 'UNKNOWN',
      privacy_release_status: 'BLOCKED',
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${clipAssetId}/privacy-status`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })

  it('creates an immutable assessment with a fresh idempotency key', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ privacy_assessment_id: '70000000-0000-4000-8000-000000000001' }, 201),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('privacy-idempotency-0001') })

    await createPrivacyAssessment(projectId, {
      clip_asset_id: clipAssetId,
      supersedes_assessment_id: null,
      risk_level: 'NONE',
      required_action: 'RELEASE_REVIEW_REQUIRED',
      reason_code: 'NO_IDENTIFIABLE_INFORMATION_OBSERVED',
      finding_types: [],
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/privacy-assessments`,
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'Idempotency-Key': 'privacy-idempotency-0001' }),
      }),
    )
  })
})

function jsonResponse(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
