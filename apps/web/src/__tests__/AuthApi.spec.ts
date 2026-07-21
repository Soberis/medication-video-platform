import { afterEach, describe, expect, it, vi } from 'vitest'

import { getSession, login, logout } from '@/api/auth'

afterEach(() => vi.unstubAllGlobals())

describe('authentication API', () => {
  it('uses the login endpoint without putting a password into a URL or browser storage', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sessionResponse())
    vi.stubGlobal('fetch', fetchMock)

    await expect(login('platform-admin', 'synthetic-password-123')).resolves.toMatchObject({
      username: 'platform-admin',
      role: 'ADMIN',
    })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/auth/login',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ username: 'platform-admin', password: 'synthetic-password-123' }),
        credentials: 'same-origin',
      }),
    )
  })

  it('uses the csrf cookie for logout', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(sessionResponse())
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    Object.defineProperty(document, 'cookie', { configurable: true, value: 'XSRF-TOKEN=csrf-test-token' })

    await getSession()
    await logout()

    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/v1/auth/logout',
      expect.objectContaining({ headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-test-token' }) }),
    )
  })
})

function sessionResponse() {
  return new Response(
    JSON.stringify({ username: 'platform-admin', role: 'ADMIN' }),
    { headers: { 'Content-Type': 'application/json' } },
  )
}
