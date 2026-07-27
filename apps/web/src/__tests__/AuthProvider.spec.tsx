import { render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { AuthProvider, useAuth } from '@/app/auth'

afterEach(() => vi.unstubAllGlobals())

describe('AuthProvider', () => {
  it('deduplicates the initial session read under StrictMode', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        username: 'platform-admin',
        role: 'ADMIN',
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    render(
      <StrictMode>
        <AuthProvider>
          <AuthState />
        </AuthProvider>
      </StrictMode>,
    )

    expect(await screen.findByText('platform-admin · ADMIN')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/auth/session',
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })

  it('keeps an authentication service failure distinct from an anonymous session', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ error_code: 'AUTH_SERVICE_UNAVAILABLE' }, 503)),
    )

    render(
      <AuthProvider>
        <AuthState />
      </AuthProvider>,
    )

    expect(await screen.findByText('AUTH_SERVICE_UNAVAILABLE')).toBeInTheDocument()
    expect(screen.queryByText('anonymous')).not.toBeInTheDocument()
  })
})

function AuthState() {
  const auth = useAuth()
  if (!auth.initialized) return <span>loading</span>
  if (auth.sessionError) return <span>{auth.sessionError.errorCode}</span>
  if (!auth.session) return <span>anonymous</span>
  return (
    <span>
      {auth.session.username} · {auth.session.role}
    </span>
  )
}

function jsonResponse(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
