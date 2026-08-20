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
      vi.fn().mockResolvedValue(
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
    expect(screen.getByRole('link', { name: '返回项目列表' })).toHaveTextContent('配药视频数据管理')
    expect(screen.getByText('选择项目，继续管理配药操作和视频。')).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('项目')
    expect(screen.getByText('跳到主要内容')).toHaveAttribute('href', '#main-content')
    const navigation = screen.getByRole('navigation', { name: '主导航' })
    expect(within(navigation).queryByText('配药操作')).not.toBeInTheDocument()
    expect(within(navigation).queryByText('视频资产')).not.toBeInTheDocument()
    expect(within(navigation).queryByText('配药动作标注')).not.toBeInTheDocument()
    expect(within(navigation).getByRole('link', { name: '项目' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(navigation).getByText('管理')).toBeInTheDocument()
    expect(screen.queryByText('上传排障')).not.toBeInTheDocument()
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

  it('keeps the ordinary project workflow in the primary navigation', () => {
    const projectId = '30000000-0000-4000-8000-000000000001'
    renderRouter(
      [
        {
          path: `/projects/:projectId/videos`,
          element: <AppShell />,
          children: [{ index: true, element: <div>视频管理页面</div> }],
        },
      ],
      [`/projects/${projectId}/videos`],
      { username: 'project-member', role: 'MEMBER' },
    )

    const navigation = screen.getByRole('navigation', { name: '主导航' })
    expect(within(navigation).getByRole('link', { name: '视频管理' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(navigation).getByRole('link', { name: '配药动作标注' })).toBeInTheDocument()
    expect(within(navigation).queryByRole('link', { name: '多相机查看' })).not.toBeInTheDocument()
    expect(within(navigation).queryByRole('link', { name: '文件夹' })).not.toBeInTheDocument()
    expect(
      within(navigation).queryByRole('link', { name: '数据集版本（后续）' }),
    ).not.toBeInTheDocument()
    expect(
      within(navigation).queryByRole('link', { name: '视频资产（高级）' }),
    ).not.toBeInTheDocument()
    expect(within(navigation).queryByRole('link', { name: '采集事件日志' })).not.toBeInTheDocument()
    expect(within(navigation).queryByRole('link', { name: '上传排障' })).not.toBeInTheDocument()
    expect(
      within(navigation).queryByRole('link', { name: '本地上传（辅助）' }),
    ).not.toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/Ego|第一视角|wearable camera/i)
  })

  it('keeps a video detail in the video-management navigation context', () => {
    const projectId = '30000000-0000-4000-8000-000000000001'
    const assetId = '50000000-0000-4000-8000-000000000001'
    renderRouter(
      [
        {
          path: '/',
          element: <AppShell />,
          children: [{ path: 'projects/:projectId/assets/:id', element: <div>视频详情页面</div> }],
        },
      ],
      [`/projects/${projectId}/assets/${assetId}`],
      { username: 'project-member', role: 'MEMBER' },
    )

    const navigation = screen.getByRole('navigation', { name: '主导航' })
    expect(within(navigation).getByRole('link', { name: '视频管理' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(navigation).getByText('管理').closest('.sidebar-nav-group')).not.toHaveAttribute(
      'data-active',
    )
  })

  it('only highlights local upload in the management menu on the local-upload page', () => {
    renderRouter(
      [
        {
          path: '/',
          element: <AppShell />,
          children: [{ path: 'uploads/new', element: <div>本地上传页面</div> }],
        },
      ],
      ['/uploads/new'],
      { username: 'platform-admin', role: 'ADMIN' },
    )

    const navigation = screen.getByRole('navigation', { name: '主导航' })
    expect(within(navigation).getByRole('link', { name: '本地上传（辅助）' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(navigation).getByText('管理').closest('.sidebar-nav-group')).toHaveAttribute(
      'data-active',
      'true',
    )
    expect(within(navigation).getByText('上传排障').closest('a')).not.toHaveAttribute(
      'aria-current',
    )
  })

  it('lets users collapse and expand the canonical sidebar', async () => {
    renderRouter(
      [
        {
          path: '/',
          element: <AppShell />,
          children: [{ path: 'projects', element: <div>项目页面</div> }],
        },
      ],
      ['/projects'],
      { username: 'platform-admin', role: 'ADMIN' },
    )

    const user = userEvent.setup()
    const sidebar = screen.getByRole('complementary', { name: '应用侧栏' })
    const frame = sidebar.closest('.app-frame')

    expect(frame).not.toHaveAttribute('data-sidebar-collapsed')
    await user.click(screen.getByRole('button', { name: '收起侧栏' }))
    expect(frame).toHaveAttribute('data-sidebar-collapsed', 'true')
    await user.click(screen.getByRole('button', { name: '展开侧栏' }))
    expect(frame).not.toHaveAttribute('data-sidebar-collapsed')
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
