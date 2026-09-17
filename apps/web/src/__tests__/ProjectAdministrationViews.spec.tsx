import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { PlatformAccount } from '@/api/admin'
import type { DataProject, ProjectMembership, ProjectRole } from '@/api/projects'
import { renderRoute } from '@/test/render'
import AccountManagementView from '@/views/AccountManagementView'
import ProjectMembershipView from '@/views/ProjectMembershipView'

afterEach(() => vi.unstubAllGlobals())

const projectId = '30000000-0000-4000-8000-000000000001'

describe('ProjectMembershipView', () => {
  it('shows inline validation when no platform account is selected', async () => {
    const fetchMock = membershipFetch(projectWithRole('ADMIN'))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderMembership('ADMIN')

    expect(await screen.findByRole('heading', { name: '项目成员列表' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '保存成员' }))

    expect(await screen.findByText('请选择平台账号。')).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) =>
          String(input).includes('/memberships/') &&
          (init as RequestInit | undefined)?.method === 'PUT',
      ),
    ).toBe(false)
  })

  it('submits a selected account and project role', async () => {
    const fetchMock = membershipFetch(projectWithRole('ADMIN'))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderMembership('ADMIN')

    expect(await screen.findByRole('heading', { name: '项目成员列表' })).toBeInTheDocument()
    await user.click(screen.getByRole('combobox', { name: '平台账号' }))
    await user.click(
      await screen.findByText('reviewer（平台成员）', {
        selector: '.ant-select-item-option-content',
      }),
    )
    await user.click(screen.getByRole('combobox', { name: '项目角色' }))
    await user.click(
      await screen.findByText('项目管理员', { selector: '.ant-select-item-option-content' }),
    )
    await user.click(screen.getByRole('button', { name: '保存成员' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/memberships/reviewer`,
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({ role: 'ADMIN' }),
        }),
      )
    })
  })

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

  it('requires confirmation before removing a project member', async () => {
    const fetchMock = membershipFetch(projectWithRole('ADMIN'))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderMembership('ADMIN')

    expect(await screen.findByRole('heading', { name: '项目成员列表' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '移除项目成员：reviewer' }))
    expect(
      await screen.findByText(/移除“reviewer”后，该账号将无法查看当前项目及其视频数据/),
    ).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '移除成员' }))
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/memberships/reviewer`,
        expect.objectContaining({ method: 'DELETE' }),
      )
    })
  })
})

describe('AccountManagementView', () => {
  it('shows inline validation when the username is empty', async () => {
    vi.stubGlobal('fetch', accountsFetch([adminAccount()]))
    const user = userEvent.setup()
    renderRoute(<AccountManagementView />, { route: '/admin/users', path: '/admin/users' })

    await screen.findByRole('heading', { name: '平台账号列表' })
    await user.click(screen.getByRole('button', { name: '创建账号' }))

    expect(await screen.findByText('请输入账号。')).toBeInTheDocument()
  })

  it('shows inline validation when the initial password is empty', async () => {
    vi.stubGlobal('fetch', accountsFetch([adminAccount()]))
    const user = userEvent.setup()
    renderRoute(<AccountManagementView />, { route: '/admin/users', path: '/admin/users' })

    await screen.findByRole('heading', { name: '平台账号列表' })
    await user.type(screen.getByLabelText('账号'), 'reviewer')
    await user.click(screen.getByRole('button', { name: '创建账号' }))

    expect(await screen.findByText('请输入初始密码。')).toBeInTheDocument()
  })

  it('keeps username and password constraints visible inline', async () => {
    vi.stubGlobal('fetch', accountsFetch([adminAccount()]))
    const user = userEvent.setup()
    renderRoute(<AccountManagementView />, { route: '/admin/users', path: '/admin/users' })

    await screen.findByRole('heading', { name: '平台账号列表' })
    await user.type(screen.getByLabelText('账号'), 'invalid username')
    await user.type(screen.getByLabelText('初始密码'), 'short')
    await user.click(screen.getByRole('button', { name: '创建账号' }))

    expect(
      await screen.findByText('账号需为 3 至 128 位字母、数字或 . _ : @ -。'),
    ).toBeInTheDocument()
    expect(await screen.findByText('初始密码长度需为 12 至 72 个字符。')).toBeInTheDocument()
  })

  it('keeps real login status visible and makes the initial-admin restriction explicit', async () => {
    const accounts = [
      adminAccount(),
      memberAccount('reviewer', true),
      memberAccount('disabled', false),
    ]
    vi.stubGlobal('fetch', accountsFetch(accounts))
    renderRoute(<AccountManagementView />, { route: '/admin/users', path: '/admin/users' })

    expect(await screen.findByRole('heading', { name: '平台账号列表' })).toBeInTheDocument()
    expect(screen.getByText('管理员不可停用')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '停用账号：reviewer' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '启用账号：disabled' })).toBeInTheDocument()
    // Scope to the column header: the fixed-layout table also renders a hidden
    // measure node that repeats the header text.
    expect(screen.getByRole('columnheader', { name: '登录状态' })).toBeInTheDocument()
  })

  it('creates a member account from the compact account form', async () => {
    const fetchMock = accountsFetch([adminAccount()])
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderRoute(<AccountManagementView />, { route: '/admin/users', path: '/admin/users' })

    await screen.findByRole('heading', { name: '平台账号列表' })
    await user.type(screen.getByLabelText('账号'), 'reviewer')
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
    await user.click(screen.getByRole('button', { name: '停用账号：reviewer' }))
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
    await user.click(screen.getByRole('button', { name: '启用账号：reviewer' }))
    expect(await screen.findByText(/启用“reviewer”后，该账号可以再次登录/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '启用账号' }))
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/admin/accounts/reviewer/enabled`,
        expect.objectContaining({
          method: 'PATCH',
          body: JSON.stringify({ enabled: true }),
        }),
      )
    })
  }, 20000)
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
  let currentMembership: ProjectMembership | null = membership
  return vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (url === `/api/v1/projects/${projectId}`) return Promise.resolve(jsonResponse(project))
    if (url === `/api/v1/projects/${projectId}/memberships`) {
      return Promise.resolve(jsonResponse({ items: currentMembership ? [currentMembership] : [] }))
    }
    if (method === 'PUT' && url === `/api/v1/projects/${projectId}/memberships/reviewer`) {
      const body = JSON.parse(String(init?.body)) as { role: ProjectRole }
      currentMembership = { ...membership, role: body.role }
      return Promise.resolve(jsonResponse(currentMembership))
    }
    if (method === 'DELETE' && url === `/api/v1/projects/${projectId}/memberships/reviewer`) {
      currentMembership = null
      return Promise.resolve(jsonResponse(membership))
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
