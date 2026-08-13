import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { renderRouter } from '@/test/render'
import { LegacyProjectAssetListRedirect } from '@/app/router'
import LoginView from '@/views/LoginView'

afterEach(() => vi.unstubAllGlobals())

describe('LoginView', () => {
  it('keeps the in-memory password when the API is temporarily unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByLabelText('账号'), 'platform-admin')
    await user.type(screen.getByLabelText('密码'), 'synthetic-password-123')
    await user.click(screen.getByRole('button', { name: /登\s*录/ }))

    expect(await screen.findByText(/认证服务暂不可用/)).toBeInTheDocument()
    expect(screen.getByLabelText('密码')).toHaveValue('synthetic-password-123')
  })

  it('clears the password after invalid credentials are rejected', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ error_code: 'INVALID_CREDENTIALS' }), {
          status: 401,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      ),
    )
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByLabelText('账号'), 'platform-admin')
    await user.type(screen.getByLabelText('密码'), 'incorrect-password')
    await user.click(screen.getByRole('button', { name: /登\s*录/ }))

    expect(await screen.findByText(/账号或密码不正确/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('密码')).toHaveValue(''))
  })

  it.each([
    '/login?redirect=//external.invalid/path',
    '/login?redirect=/%5Cexternal.invalid/path',
    '/login?redirect=/login',
  ])('rejects an external-looking post-login redirect: %s', async (entry) => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ username: 'platform-admin', role: 'ADMIN' })),
    )
    const user = userEvent.setup()
    const { router } = renderLogin(entry)

    await user.type(screen.getByLabelText('账号'), 'platform-admin')
    await user.type(screen.getByLabelText('密码'), 'synthetic-password-123')
    await user.click(screen.getByRole('button', { name: /登\s*录/ }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/projects'))
    expect(await screen.findByText('Projects')).toBeInTheDocument()
  })

  it('returns to the requested internal route after login state changes', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ username: 'platform-admin', role: 'ADMIN' })),
    )
    const user = userEvent.setup()
    const { router } = renderLogin('/login?redirect=%2Fprojects%2Fproject-1%2Fassets')

    await user.type(screen.getByLabelText('账号'), 'platform-admin')
    await user.type(screen.getByLabelText('密码'), 'synthetic-password-123')
    await user.click(screen.getByRole('button', { name: /登\s*录/ }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/projects/project-1/videos'))
    expect(await screen.findByText('Videos')).toBeInTheDocument()
  })
})

function renderLogin(entry = '/login') {
  return renderRouter(
    [
      { path: '/login', element: <LoginView /> },
      { path: '/projects', element: <div>Projects</div> },
      {
        path: '/projects/:projectId/assets',
        element: <LegacyProjectAssetListRedirect />,
      },
      { path: '/projects/:projectId/videos', element: <div>Videos</div> },
    ],
    [entry],
  )
}

function jsonResponse(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
