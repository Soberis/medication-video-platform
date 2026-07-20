import { flushPromises, mount } from '@vue/test-utils'
import { ElSelect } from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'

import LocalUploadView from '@/views/LocalUploadView.vue'

afterEach(() => vi.unstubAllGlobals())

describe('LocalUploadView', () => {
  it('shows each selected local video as a separate queued upload', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          items: [
            {
              project_id: '30000000-0000-4000-8000-000000000001',
              display_name: '合成测试项目',
              status: 'ACTIVE',
              current_role: 'MEMBER',
              created_at: '2026-07-20T01:00:00Z',
            },
          ],
        }),
      ),
    )
    const wrapper = mount(LocalUploadView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    const input = wrapper.get('#local-upload-files').element as HTMLInputElement
    Object.defineProperty(input, 'files', {
      configurable: true,
      value: [
        new File(['video-a'], 'first.mp4', { type: 'video/mp4' }),
        new File(['video-b'], 'second.mp4', { type: 'video/mp4' }),
      ],
    })

    await wrapper.get('#local-upload-files').trigger('change')

    expect(wrapper.text()).toContain('first.mp4')
    expect(wrapper.text()).toContain('second.mp4')
    expect(wrapper.text()).toContain('开始上传（2 个视频）')
    expect(wrapper.text()).toContain('按顺序分别上传')
  })

  it('clears a project that is no longer authorized after refresh', async () => {
    const project = {
      project_id: '30000000-0000-4000-8000-000000000001',
      display_name: '合成测试项目',
      status: 'ACTIVE',
      current_role: 'MEMBER',
      created_at: '2026-07-20T01:00:00Z',
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ items: [project] }))
      .mockResolvedValueOnce(jsonResponse({ items: [] }))
      .mockResolvedValueOnce(jsonResponse({ items: [] }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(LocalUploadView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()

    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', project.project_id)
    await flushPromises()
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.text-link').exists()).toBe(false)
    expect((wrapper.get('button[type="submit"]').element as HTMLButtonElement).disabled).toBe(true)
  })
})

function jsonResponse(body: object) {
  return new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
  })
}
