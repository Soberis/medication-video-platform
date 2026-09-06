import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import AppShell from '@/App'
import { renderRoute, renderRouter } from '@/test/render'
import ProjectListView from '@/views/ProjectListView'
import { paths } from '@/app/paths'

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
    expect(screen.queryByText('选择项目并进入工作区。')).not.toBeInTheDocument()
    expect(screen.queryByText('统一管理配药操作、视频资产和配药动作标注。')).not.toBeInTheDocument()
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
    expect(within(navigation).queryByText('设置')).not.toBeInTheDocument()
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

  it('creates a project and reloads the project list', async () => {
    const fetchMock = projectListFetch([], true)
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderRoute(<ProjectListView />, { route: '/projects', path: '/projects' })

    const pageToolbar = await screen.findByRole('toolbar', { name: '页面操作' })
    await user.click(await within(pageToolbar).findByRole('button', { name: '新建项目' }))
    const dialog = await screen.findByRole('dialog', { name: '新建项目' })
    await user.type(within(dialog).getByRole('textbox'), '合成验证项目')
    await user.click(within(dialog).getByRole('button', { name: '创建项目' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/projects',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ display_name: '合成验证项目' }),
        }),
      )
    })
    expect(await screen.findByText('合成验证项目')).toBeInTheDocument()
  })

  it('renames a project from its administrator menu', async () => {
    const project = projectFixture('管理员项目')
    const fetchMock = projectListFetch([project], false)
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderRoute(<ProjectListView />, { route: '/projects', path: '/projects' })

    await user.click(await screen.findByRole('button', { name: '管理项目：管理员项目' }))
    await user.click(await screen.findByRole('menuitem', { name: '修改名称' }))
    const dialog = await screen.findByRole('dialog', { name: '修改项目名称' })
    const input = within(dialog).getByRole('textbox')
    await user.clear(input)
    await user.type(input, '已更新项目')
    await user.click(within(dialog).getByRole('button', { name: '保存名称' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${project.project_id}`,
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({ display_name: '已更新项目' }),
        }),
      )
    })
    expect(await screen.findByText('已更新项目')).toBeInTheDocument()
  })

  it('requires the exact project name before archiving', async () => {
    const project = projectFixture('待归档项目')
    const fetchMock = projectListFetch([project], false)
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderRoute(<ProjectListView />, { route: '/projects', path: '/projects' })

    await user.click(await screen.findByRole('button', { name: '管理项目：待归档项目' }))
    await user.click(await screen.findByRole('menuitem', { name: '归档项目' }))
    const dialog = await screen.findByRole('dialog', { name: '归档项目' })
    const input = within(dialog).getByRole('textbox')
    await user.type(input, '待归档')
    await user.click(within(dialog).getByRole('button', { name: '确认归档' }))
    expect(
      await within(dialog).findByText('输入的项目名称与当前项目不一致，未执行归档。'),
    ).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) =>
          String(input) === `/api/v1/projects/${project.project_id}` &&
          (init as RequestInit | undefined)?.method === 'DELETE',
      ),
    ).toBe(false)

    await user.clear(input)
    await user.type(input, '待归档项目')
    await user.click(within(dialog).getByRole('button', { name: '确认归档' }))
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${project.project_id}`,
        expect.objectContaining({ method: 'DELETE' }),
      )
    })
    expect(
      await screen.findByText(/当前账号尚未加入项目。平台管理员可新建项目/),
    ).toBeInTheDocument()
  })

  it('treats data groups as the canonical video-management workspace', () => {
    const projectId = '30000000-0000-4000-8000-000000000001'
    renderRouter(
      [
        {
          path: `/projects/:projectId/capture-groups`,
          element: <AppShell />,
          children: [{ index: true, element: <div>视频页面</div> }],
        },
      ],
      [`/projects/${projectId}/capture-groups`],
      { username: 'project-member', role: 'MEMBER' },
    )

    const navigation = screen.getByRole('navigation', { name: '主导航' })
    expect(within(navigation).getByRole('link', { name: '视频' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(navigation).getByRole('link', { name: '视频' })).toHaveAttribute(
      'href',
      `/projects/${projectId}/capture-groups`,
    )
    expect(within(navigation).getByRole('link', { name: '标注任务' })).toBeInTheDocument()
  })

  it('marks the annotation task list as the active workspace navigation', () => {
    const projectId = '30000000-0000-4000-8000-000000000001'
    renderRouter(
      [
        {
          path: '/',
          element: <AppShell />,
          children: [
            {
              path: 'projects/:projectId/annotation-tasks',
              element: <div>标注任务页面</div>,
            },
          ],
        },
      ],
      [`/projects/${projectId}/annotation-tasks`],
      { username: 'project-member', role: 'MEMBER' },
    )

    const navigation = screen.getByRole('navigation', { name: '主导航' })
    expect(within(navigation).getByRole('link', { name: '标注任务' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(navigation).getByRole('link', { name: '视频' })).not.toHaveAttribute(
      'aria-current',
    )
  })

  it('keeps a CaptureGroup detail in the video-management navigation context', () => {
    const projectId = '30000000-0000-4000-8000-000000000001'
    const captureGroupId = '60000000-0000-4000-8000-000000000001'
    renderRouter(
      [
        {
          path: '/',
          element: <AppShell />,
          children: [
            {
              path: 'projects/:projectId/capture-groups/:captureGroupId',
              element: <div>视频详情页面</div>,
            },
          ],
        },
      ],
      [`/projects/${projectId}/capture-groups/${captureGroupId}`],
      { username: 'project-member', role: 'MEMBER' },
    )

    const navigation = screen.getByRole('navigation', { name: '主导航' })
    expect(within(navigation).getByRole('link', { name: '视频' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(navigation).queryByText('设置')).not.toBeInTheDocument()
  })

  it('keeps CaptureGroup annotation focus in the video-management navigation context', () => {
    const projectId = '30000000-0000-4000-8000-000000000001'
    const captureGroupId = '60000000-0000-4000-8000-000000000001'
    renderRouter(
      [
        {
          path: '/',
          element: <AppShell />,
          children: [
            {
              path: 'projects/:projectId/capture-groups/:captureGroupId',
              element: <div>配药动作标注工作台</div>,
            },
          ],
        },
      ],
      [`/projects/${projectId}/capture-groups/${captureGroupId}?focus=annotation`],
      { username: 'project-member', role: 'MEMBER' },
    )

    const navigation = screen.getByRole('navigation', { name: '主导航' })
    expect(within(navigation).getByRole('link', { name: '标注任务' })).not.toHaveAttribute(
      'aria-current',
    )
    expect(within(navigation).getByRole('link', { name: '视频' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(navigation).queryByText('设置')).not.toBeInTheDocument()
  })

  it('only highlights local upload in the management menu on the local-upload page', () => {
    renderRouter(
      [
        {
          path: '/',
          element: <AppShell />,
          children: [{ path: 'uploads/new', element: <div>上传视频页面</div> }],
        },
      ],
      ['/uploads/new'],
      { username: 'platform-admin', role: 'ADMIN' },
    )

    const navigation = screen.getByRole('navigation', { name: '主导航' })
    expect(within(navigation).getByRole('link', { name: '上传视频' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(navigation).getByText('设置').closest('.sidebar-nav-group')).toHaveAttribute(
      'data-active',
      'true',
    )
    expect(within(navigation).queryByText('上传排障')).not.toBeInTheDocument()
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
    expect(screen.getByRole('button', { name: '展开侧栏' })).toHaveAttribute(
      'aria-controls',
      'sidebar-navigation',
    )
    await user.click(screen.getByRole('button', { name: '展开侧栏' }))
    expect(frame).not.toHaveAttribute('data-sidebar-collapsed')
  })

  it('moves focus to the single main landmark through the skip link', async () => {
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
    await user.click(screen.getByRole('link', { name: '跳到主要内容' }))

    expect(screen.getAllByRole('main')).toHaveLength(1)
    expect(document.activeElement).toBe(screen.getByRole('main'))
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
    expect(screen.getByRole('link', { name: '进入项目：管理员项目' })).toHaveAttribute(
      'href',
      paths.captureGroups('30000000-0000-4000-8000-000000000001'),
    )
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

function projectFixture(displayName: string) {
  return {
    project_id: '30000000-0000-4000-8000-000000000001',
    display_name: displayName,
    status: 'ACTIVE' as const,
    current_role: 'ADMIN' as const,
    created_at: '2026-07-20T00:00:00Z',
  }
}

function projectListFetch(
  initialProjects: ReturnType<typeof projectFixture>[],
  canCreateProjects: boolean,
) {
  let projects = [...initialProjects]
  return vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (method === 'GET' && url === '/api/v1/projects') {
      return Promise.resolve(
        jsonResponse({ items: projects, can_create_projects: canCreateProjects }),
      )
    }
    if (method === 'POST' && url === '/api/v1/projects') {
      const body = JSON.parse(String(init?.body)) as { display_name: string }
      const created = projectFixture(body.display_name)
      projects = [...projects, created]
      return Promise.resolve(jsonResponse(created))
    }
    if (method === 'PUT' && url.startsWith('/api/v1/projects/')) {
      const projectId = url.split('/').at(-1)
      const body = JSON.parse(String(init?.body)) as { display_name: string }
      projects = projects.map((project) =>
        project.project_id === projectId
          ? { ...project, display_name: body.display_name }
          : project,
      )
      const updatedProject = projects.find((project) => project.project_id === projectId)
      return Promise.resolve(jsonResponse(updatedProject ?? projectFixture(body.display_name)))
    }
    if (method === 'DELETE' && url.startsWith('/api/v1/projects/')) {
      const projectId = url.split('/').at(-1)
      projects = projects.filter((project) => project.project_id !== projectId)
      return Promise.resolve(new Response(null, { status: 204 }))
    }
    return Promise.resolve(jsonResponse({ error_code: 'NOT_FOUND' }, 404))
  })
}
