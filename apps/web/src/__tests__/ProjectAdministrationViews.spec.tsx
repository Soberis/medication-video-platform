import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { PlatformAccount } from '@/api/admin'
import type { DataProject, ProjectMembership } from '@/api/projects'
import { renderRoute } from '@/test/render'
import AccountManagementView from '@/views/AccountManagementView'
import ProjectMembershipView from '@/views/ProjectMembershipView'

afterEach(() => vi.unstubAllGlobals())

const projectId = '30000000-0000-4000-8000-000000000001'

describe('ProjectMembershipView', () => {
  it('keeps project role and removes the redundant membership status column', async () => {
    vi.stubGlobal('fetch', membershipFetch(projectWithRole('ADMIN')))
    renderMembership('ADMIN')

    expect(await screen.findByRole('heading', { name: '项目成员列表' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存成员' })).toBeInTheDocument()
    expect(screen.getByText('项目管理员')).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '状态' })).not.toBeInTheDocument()
    expect(screen.queryByText('有效')).not.toBeInTheDocument()
  })

  it('shows a read-only explanation and no member-management controls for project members', async () => {
    vi.stubGlobal('fetch', membershipFetch(projectWithRole('MEMBER')))
    renderMembership('MEMBER')

    expect(await screen.findByText('当前账号无权管理成员')).toBeInTheDocument()
    expect(screen.getByText('只有项目管理员可以添加、修改或移除成员。')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '保存成员' })).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '操作' })).not.toBeInTheDocument()
  })
})

describe('AccountManagementView', () => {
  it('keeps real login status visible and makes the initial-admin restriction explicit', async () => {
    const accounts = [
      adminAccount(),
      memberAccount('reviewer', true),
      memberAccount('disabled', false),
    ]
    vi.stubGlobal('fetch', accountsFetch(accounts))
    renderRoute(<AccountManagementView />, { route: '/admin/users', path: '/admin/users' })

    expect(await screen.findByRole('heading', { name: '平台账号列表' })).toBeInTheDocument()
    expect(screen.getByText('不可停用')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '停用' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '启用' })).toBeInTheDocument()
    expect(screen.getByText('登录状态')).toBeInTheDocument()
  })

  it('creates a member account from the compact account form', async () => {
    const fetchMock = accountsFetch([adminAccount()])
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderRoute(<AccountManagementView />, { route: '/admin/users', path: '/admin/users' })

    await screen.findByRole('heading', { name: '平台账号列表' })
    await user.type(screen.getByLabelText('账号名'), 'reviewer')
    await user.type(screen.getByLabelText('初始密码'), 'synthetic-password')
    await user.click(screen.getByRole('button', { name: '创建账号' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/admin/accounts',
        expect.objectContaining({ method: 'POST' }),
      )
    })
  })

  it('requires confirmation before disabling and then exposes enable again', async () => {
    const fetchMock = accountsFetch([adminAccount(), memberAccount('reviewer', true)])
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderRoute(<AccountManagementView />, { route: '/admin/users', path: '/admin/users' })

    await screen.findByRole('heading', { name: '平台账号列表' })
    await user.click(screen.getByRole('button', { name: '停用' }))
    expect(await screen.findByText(/停用“reviewer”会阻止其创建新的登录会话/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '停用账号' }))
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/admin/accounts/reviewer/enabled`,
        expect.objectContaining({
          method: 'PATCH',
          body: JSON.stringify({ enabled: false }),
        }),
      )
    })
    expect(await screen.findByRole('button', { name: '启用' })).toBeInTheDocument()
  })
})

function renderMembership(role: 'ADMIN' | 'MEMBER') {
  return renderRoute(<ProjectMembershipView />, {
    route: `/projects/${projectId}/members`,
    path: '/projects/:projectId/members',
    session: { username: role === 'ADMIN' ? 'admin' : 'reviewer', role },
  })
}

function projectWithRole(role: DataProject['current_role']): DataProject {
  return {
    project_id: projectId,
    display_name: '合成测试项目',
    status: 'ACTIVE',
    current_role: role,
    created_at: '2026-07-20T00:00:00Z',
  }
}

function membershipFetch(project: DataProject) {
  const membership: ProjectMembership = {
    membership_id: '40000000-0000-4000-8000-000000000001',
    project_id: projectId,
    actor_id: 'reviewer',
    role: project.current_role === 'ADMIN' ? 'ADMIN' : 'MEMBER',
    status: 'ACTIVE',
    version: 1,
    updated_at: '2026-07-20T00:00:00Z',
  }
  return vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = String(input)
    if (url === `/api/v1/projects/${projectId}`) return Promise.resolve(jsonResponse(project))
    if (url === `/api/v1/projects/${projectId}/memberships`) {
      return Promise.resolve(jsonResponse({ items: [membership] }))
    }
    if (url === '/api/v1/admin/accounts') {
      return Promise.resolve(
        jsonResponse({ items: [adminAccount(), memberAccount('reviewer', true)] }),
      )
    }
    return Promise.resolve(jsonResponse({ error_code: 'NOT_FOUND' }, 404))
  })
}

function adminAccount(): PlatformAccount {
  return {
    username: 'admin',
    role: 'ADMIN',
    enabled: true,
    version: 1,
    created_at: '2026-07-20T00:00:00Z',
    updated_at: '2026-07-20T00:00:00Z',
  }
}

function memberAccount(username: string, enabled: boolean): PlatformAccount {
  return {
    username,
    role: 'MEMBER',
    enabled,
    version: 1,
    created_at: '2026-07-20T00:00:00Z',
    updated_at: '2026-07-20T00:00:00Z',
  }
}

function accountsFetch(initialAccounts: PlatformAccount[]) {
  let accounts = [...initialAccounts]
  return vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (method === 'POST' && url === '/api/v1/admin/accounts') {
      const body = JSON.parse(String(init?.body)) as { username: string }
      const created = memberAccount(body.username, true)
      accounts = [...accounts, created]
      return Promise.resolve(jsonResponse(created))
    }
    if (method === 'PATCH' && url.endsWith('/enabled')) {
      const username = decodeURIComponent(url.split('/').at(-2) ?? '')
      const body = JSON.parse(String(init?.body)) as { enabled: boolean }
      accounts = accounts.map((account) =>
        account.username === username ? { ...account, enabled: body.enabled } : account,
      )
      const updated = accounts.find((account) => account.username === username)
      return Promise.resolve(jsonResponse(updated ?? accounts[0]))
    }
    return Promise.resolve(jsonResponse({ items: accounts }))
  })
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
