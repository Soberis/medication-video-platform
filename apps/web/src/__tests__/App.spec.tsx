import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import AppShell from '@/App'
import { renderRoute, renderRouter } from '@/test/render'
import ProjectListView from '@/views/ProjectListView'

afterEach(() => vi.unstubAllGlobals())

describe('App', () => {
  it('uses the authorized project list as the explicit business entry', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({
            items: [
              {
                project_id: '30000000-0000-4000-8000-000000000001',
                display_name: '合成测试项目',
                status: 'ACTIVE',
                current_role: 'ADMIN',
                created_at: '2026-07-20T00:00:00Z',
              },
            ],
            can_create_projects: false,
          }),
        ),
    )
    const { router } = renderRouter(
      [
        {
          path: '/',
          element: <AppShell />,
          children: [{ path: 'projects', element: <ProjectListView /> }],
        },
      ],
      ['/projects'],
      { username: 'project-member', role: 'MEMBER' },
    )

    expect(await screen.findByText('合成测试项目')).toBeInTheDocument()
    expect(screen.getByText('配药视频数据管理平台')).toBeInTheDocument()
    expect(screen.getByText('选择项目，继续管理配药操作、视频和文件夹。')).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('项目')
    expect(screen.getByText('跳到主要内容')).toHaveAttribute('href', '#main-content')
    const navigation = screen.getByRole('navigation', { name: '主导航' })
    expect(within(navigation).queryByText('配药操作')).not.toBeInTheDocument()
    expect(within(navigation).queryByText('视频资产')).not.toBeInTheDocument()
    expect(within(navigation).queryByText('标注任务')).not.toBeInTheDocument()
    expect(within(navigation).getByRole('link', { name: '项目' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(navigation).getByText('管理')).toBeInTheDocument()
    expect(screen.getByText('上传排障')).toBeInTheDocument()
    expect(screen.queryByText('账号管理')).not.toBeInTheDocument()
    expect(screen.queryByText('新建项目')).not.toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/projects')
  })

  it('only offers first-project creation when the server grants the capability', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ items: [], can_create_projects: true })),
    )
    renderRoute(<ProjectListView />, { route: '/projects', path: '/projects' })
    expect(
      (await screen.findAllByRole('button', { name: /新\s*建\s*项\s*目/ })).length,
    ).toBeGreaterThan(0)
  })

  it('only exposes project rename and archive commands to project administrators', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          items: [
            {
              project_id: '30000000-0000-4000-8000-000000000001',
              display_name: '管理员项目',
              status: 'ACTIVE',
              current_role: 'ADMIN',
              created_at: '2026-07-20T00:00:00Z',
            },
            {
              project_id: '30000000-0000-4000-8000-000000000002',
              display_name: '普通成员项目',
              status: 'ACTIVE',
              current_role: 'MEMBER',
              created_at: '2026-07-20T00:00:00Z',
            },
          ],
          can_create_projects: false,
        }),
      ),
    )
    renderRoute(<ProjectListView />, { route: '/projects', path: '/projects' })
    expect(await screen.findByText('管理员项目')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /管理项目/ })).toHaveLength(1)
    expect(screen.getByRole('link', { name: '进入项目：管理员项目' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '管理项目：管理员项目' })).toBeInTheDocument()
    expect(screen.queryByText('修改名称')).not.toBeInTheDocument()
    expect(screen.queryByText('归档项目')).not.toBeInTheDocument()
  })

  it('returns to login after logout succeeds', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
    const user = userEvent.setup()
    const { router } = renderRouter(
      [
        { path: '/login', element: <div>Login</div> },
        {
          path: '/projects',
          element: <AppShell />,
          children: [{ index: true, element: <div>Projects</div> }],
        },
      ],
      ['/projects'],
      { username: 'platform-admin', role: 'ADMIN' },
    )
    await user.click(screen.getByRole('button', { name: '退出登录' }))
    expect(await screen.findByText('Login')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
  })
})

function jsonResponse(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
