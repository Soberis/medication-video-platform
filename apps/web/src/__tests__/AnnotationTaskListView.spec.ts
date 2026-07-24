import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import AnnotationTaskListView from '@/views/AnnotationTaskListView.vue'

const projectId = '30000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('annotation task list view', () => {
  it('renders only server-provided visible tasks and links back to the video', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            items: [
              {
                annotation_task_id: 'a0000000-0000-4000-8000-000000000001',
                project_id: projectId,
                clip_asset_id: '50000000-0000-4000-8000-000000000001',
                assignee_actor_id: 'local-synthetic-uploader',
                created_by: 'local-synthetic-admin',
                status: 'ASSIGNED',
                can_submit: true,
                created_at: '2026-07-23T01:00:00Z',
                updated_at: '2026-07-23T01:00:00Z',
                version: 0,
              },
            ],
          }),
          { headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/projects/:projectId/annotation-tasks', name: 'annotation-task-list', component: AnnotationTaskListView },
        { path: '/projects/:projectId/assets/:id', name: 'asset-detail', component: { template: '<div />' } },
      ],
    })
    await router.push(`/projects/${projectId}/annotation-tasks`)
    await router.isReady()
    const wrapper = mount(AnnotationTaskListView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('标注任务')
    expect(wrapper.text()).toContain('已分配')
    expect(wrapper.text()).toContain('提交标注')
    expect(wrapper.text()).toContain('local-synthetic-uploader')
    expect(wrapper.find('a').attributes('href')).toBe(`/projects/${projectId}/assets/50000000-0000-4000-8000-000000000001`)
  })
})
