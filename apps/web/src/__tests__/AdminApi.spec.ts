import { afterEach, describe, expect, it, vi } from 'vitest'

import { changePlatformAccountEnabled, createPlatformAccount } from '@/api/admin'

afterEach(() => vi.unstubAllGlobals())

describe('platform account API', () => {
  it('uses the protected account endpoint and csrf header without persisting the password', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(accountResponse()))
    vi.stubGlobal('fetch', fetchMock)
    Object.defineProperty(document, 'cookie', { configurable: true, value: 'XSRF-TOKEN=csrf-test-token' })

    await createPlatformAccount('reviewer-01', 'member-password-123')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/admin/accounts',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ username: 'reviewer-01', password: 'member-password-123' }),
        headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-test-token' }),
      }),
    )
  })

  it('changes account state through the narrow enabled endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ...accountResponse(), enabled: false }))
    vi.stubGlobal('fetch', fetchMock)

    await changePlatformAccountEnabled('reviewer-01', false)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/admin/accounts/reviewer-01/enabled',
      expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ enabled: false }) }),
    )
  })
})

function accountResponse() {
  return {
    username: 'reviewer-01',
    role: 'MEMBER',
    enabled: true,
    version: 0,
    created_at: '2026-07-21T00:00:00Z',
    updated_at: '2026-07-21T00:00:00Z',
  }
}

function jsonResponse(body: object) {
  return new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
  })
}
