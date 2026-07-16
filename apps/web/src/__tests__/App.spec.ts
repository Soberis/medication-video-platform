import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import App from '@/App.vue'
import SessionListView from '@/views/SessionListView.vue'

afterEach(() => vi.unstubAllGlobals())

describe('App', () => {
  it('uses capture sessions as the default business entry', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ items: [], next_cursor: null })))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', redirect: '/sessions' },
        { path: '/sessions', component: SessionListView },
      ],
    })

    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, {
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('配药视频数据管理平台')
    expect(wrapper.text()).toContain('配药操作记录')
    expect(wrapper.text()).toContain('数据由边端自动上传')
    expect(router.currentRoute.value.path).toBe('/sessions')
  })
})

function jsonResponse(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
