import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import App from '@/App.vue'
import { useAuthStore } from '@/stores/auth'
import ProjectListView from '@/views/ProjectListView.vue'

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
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', redirect: '/projects' },
        { path: '/projects', name: 'project-list', component: ProjectListView },
        {
          path: '/projects/:projectId/sessions',
          name: 'session-list',
          component: { template: '<div>Session list</div>' },
        },
        {
          path: '/projects/:projectId/assets',
          name: 'asset-list',
          component: { template: '<div>Asset list</div>' },
        },
        {
          path: '/uploads',
          name: 'upload-progress',
          component: { template: '<div>Upload progress</div>' },
        },
        {
          path: '/uploads/new',
          name: 'local-upload',
          component: { template: '<div>Local upload</div>' },
        },
      ],
    })

    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, {
      global: {
        plugins: [createPinia(), router],
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('配药视频数据管理平台')
    expect(wrapper.text()).toContain('授权项目')
    expect(wrapper.text()).toContain('合成测试项目')
    expect(wrapper.findAll('h1')).toHaveLength(1)
    expect(wrapper.get('h1').text()).toBe('授权项目')
    expect(wrapper.find('.skip-link').attributes('href')).toBe('#main-content')
    const navigationText = wrapper.get('nav[aria-label="主导航"]').text()
    expect(navigationText).not.toContain('配药操作')
    expect(navigationText).not.toContain('视频资产')
    expect(navigationText).not.toContain('标注任务')
    expect(wrapper.findAll('.router-link-active')).toHaveLength(1)
    expect(wrapper.text()).toContain('管理')
    expect(wrapper.text()).toContain('上传排障')
    expect(wrapper.text()).not.toContain('账号管理')
    expect(wrapper.text()).not.toContain('切换身份')
    expect(wrapper.text()).not.toContain('新建项目')
    expect(router.currentRoute.value.path).toBe('/projects')
  })

  it('only offers first-project creation when the server grants the capability', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ items: [], can_create_projects: true })),
    )
    const wrapper = mount(ProjectListView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()

    expect(wrapper.text()).toContain('新建项目')
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
    const wrapper = mount(ProjectListView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()

    expect(wrapper.findAll('button').filter((button) => button.text() === '更多')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('改名')
    expect(wrapper.text()).not.toContain('归档')
  })

  it('returns to login after logout succeeds', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.session = { username: 'platform-admin', role: 'ADMIN' }
    auth.initialized = true
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/login', name: 'login', component: { template: '<div>Login</div>' } },
        { path: '/projects', name: 'project-list', component: { template: '<div>Projects</div>' } },
        { path: '/uploads', name: 'upload-progress', component: { template: '<div />' } },
        { path: '/uploads/new', name: 'local-upload', component: { template: '<div />' } },
        { path: '/admin/users', name: 'account-management', component: { template: '<div />' } },
      ],
    })
    await router.push('/projects')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [pinia, router] } })

    const logoutButton = wrapper.findAll('button').find((button) => button.text() === '退出登录')
    expect(logoutButton).toBeDefined()
    await logoutButton!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('login')
    expect(auth.session).toBeNull()
  })
})

function jsonResponse(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
