import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import LoginView from '@/views/LoginView.vue'

afterEach(() => vi.unstubAllGlobals())

describe('LoginView', () => {
  it('keeps the in-memory password when the API is temporarily unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const wrapper = await mountLoginView()

    await wrapper.find('input[name="username"]').setValue('platform-admin')
    await wrapper.find('input[name="password"]').setValue('synthetic-password-123')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('认证服务暂不可用')
    expect((wrapper.find('input[name="password"]').element as HTMLInputElement).value).toBe(
      'synthetic-password-123',
    )
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
    const wrapper = await mountLoginView()

    await wrapper.find('input[name="username"]').setValue('platform-admin')
    await wrapper.find('input[name="password"]').setValue('incorrect-password')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('账号或密码不正确')
    expect((wrapper.find('input[name="password"]').element as HTMLInputElement).value).toBe('')
  })
})

async function mountLoginView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: LoginView },
      { path: '/projects', name: 'project-list', component: { template: '<div />' } },
    ],
  })
  await router.push('/login')
  await router.isReady()
  return mount(LoginView, { global: { plugins: [createPinia(), router] } })
}
