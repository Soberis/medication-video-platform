import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import AssetDetailView from '@/views/AssetDetailView.vue'
import AssetListView from '@/views/AssetListView.vue'
import SessionListView from '@/views/SessionListView.vue'
import { useAuthStore } from '@/stores/auth'

const projectId = '30000000-0000-4000-8000-000000000001'
const sessionId = '40000000-0000-4000-8000-000000000001'
const assetId = '50000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('catalog views', () => {
  it('distinguishes bound, ingested and pending quality facts in the session list', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          items: [
            {
              capture_session_id: sessionId,
              batch_id: '60000000-0000-4000-8000-000000000001',
              device_id: 'synthetic-edge-01',
              camera_set_id: '70000000-0000-4000-8000-000000000001',
              camera_set_key: 'dispensing-cabin-a',
              camera_set_configuration_version: 1,
              camera_layout_version: 'layout-v1',
              expected_camera_count: 2,
              bound_stream_count: 1,
              received_camera_count: 0,
              ingest_failed_stream_count: 0,
              sync_group_id: 'sync-group-01',
              device_time_utc: '2026-07-15T01:02:03Z',
              server_receive_time: '2026-07-15T01:03:03Z',
              assembly_status: 'ASSEMBLING',
              quality_status: 'PENDING',
              created_at: '2026-07-15T01:03:03Z',
            },
          ],
          next_cursor: null,
        }),
      ),
    )
    const router = testRouter(
      `/projects/${projectId}/sessions`,
      '/projects/:projectId/sessions',
      SessionListView,
    )
    await router.isReady()
    const wrapper = mount(SessionListView, { global: { plugins: [router, createPinia()] } })
    await flushPromises()

    expect(wrapper.text()).toContain('已绑定 1 / 2')
    expect(wrapper.text()).toContain('已入库 0')
    expect(wrapper.text()).toContain('待检测')
    expect(wrapper.text()).toContain('组装中')
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining(`/api/v1/projects/${projectId}/capture-sessions`),
      expect.any(Object),
    )
  }, 15_000)

  it('explains the pending media check without exposing storage or using an identifier as the page title', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          clip_asset_id: assetId,
          batch_id: '60000000-0000-4000-8000-000000000001',
          source_upload_file_id: '80000000-0000-4000-8000-000000000001',
          client_file_id: '90000000-0000-4000-8000-000000000001',
          content_type: 'video/mp4',
          size_bytes: 8_388_608,
          sha256: 'a'.repeat(64),
          status: 'UPLOADED',
          folder_id: null,
          session_binding: null,
          created_at: '2026-07-15T01:03:03Z',
          updated_at: '2026-07-15T01:03:03Z',
        }),
      ),
    )
    const router = testRouter(
      `/projects/${projectId}/assets/${assetId}`,
      '/projects/:projectId/assets/:id',
      AssetDetailView,
    )
    await router.isReady()
    const wrapper = mount(AssetDetailView, { global: { plugins: [router, createPinia()] } })
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('视频详情')
    expect(wrapper.text()).toContain('媒体检测尚未开始')
    expect(wrapper.text()).toContain('媒体检测完成后，项目成员即可播放并判断数据质量')
    expect(wrapper.text()).toContain('90000000-0000-4000-8000-000000000001')
    expect(wrapper.text()).toContain('尚未绑定到 CaptureSession')
    expect(wrapper.text()).toContain('未分类')
    expect(wrapper.find('video').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('object_key')
    expect(wrapper.html()).not.toContain('storage_bucket')
  })

  it('does not offer the destructive action to a project member', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((input: string | URL | Request) => {
        const url = String(input)
        if (url.endsWith(`/api/v1/projects/${projectId}`)) {
          return Promise.resolve(
            jsonResponse({
              project_id: projectId,
              display_name: '合成测试项目',
              status: 'ACTIVE',
              current_role: 'MEMBER',
              created_at: '2026-07-15T01:03:03Z',
            }),
          )
        }
        return Promise.resolve(
          jsonResponse({
            clip_asset_id: assetId,
            batch_id: '60000000-0000-4000-8000-000000000001',
            source_upload_file_id: '80000000-0000-4000-8000-000000000001',
            client_file_id: '90000000-0000-4000-8000-000000000001',
            content_type: 'video/mp4',
            size_bytes: 8_388_608,
            sha256: 'a'.repeat(64),
            status: 'UPLOADED',
            folder_id: null,
            session_binding: null,
            created_at: '2026-07-15T01:03:03Z',
            updated_at: '2026-07-15T01:03:03Z',
          }),
        )
      }),
    )
    const router = testRouter(
      `/projects/${projectId}/assets/${assetId}`,
      '/projects/:projectId/assets/:id',
      AssetDetailView,
    )
    await router.isReady()
    const wrapper = mount(AssetDetailView, { global: { plugins: [router, createPinia()] } })
    await flushPromises()

    expect(wrapper.findAll('button').some((button) => button.text() === '删除视频')).toBe(false)
  })

  it('shows the existing project folders to an administrator classifying a video', async () => {
    const folderId = 'a0000000-0000-4000-8000-000000000001'
    const fetchMock = vi.fn().mockImplementation((input: string | URL | Request) => {
      const url = String(input)
      if (url.endsWith(`/api/v1/projects/${projectId}`)) {
        return Promise.resolve(
          jsonResponse({
            project_id: projectId,
            display_name: '合成测试项目',
            status: 'ACTIVE',
            current_role: 'ADMIN',
            created_at: '2026-07-15T01:03:03Z',
          }),
        )
      }
      if (url.endsWith('/asset-folders')) {
        return Promise.resolve(
          jsonResponse({
            items: [
              {
                folder_id: folderId,
                project_id: projectId,
                display_name: '待标注',
                version: 0,
                created_at: '2026-07-15T01:03:03Z',
                updated_at: '2026-07-15T01:03:03Z',
              },
            ],
          }),
        )
      }
      return Promise.resolve(
        jsonResponse({
          clip_asset_id: assetId,
          batch_id: '60000000-0000-4000-8000-000000000001',
          source_upload_file_id: '80000000-0000-4000-8000-000000000001',
          client_file_id: '90000000-0000-4000-8000-000000000001',
          content_type: 'video/mp4',
          size_bytes: 8_388_608,
          sha256: 'a'.repeat(64),
          status: 'UPLOADED',
          folder_id: null,
          session_binding: null,
          created_at: '2026-07-15T01:03:03Z',
          updated_at: '2026-07-15T01:03:03Z',
        }),
      )
    })
    vi.stubGlobal(
      'fetch',
      fetchMock,
    )
    const router = testRouter(
      `/projects/${projectId}/assets/${assetId}`,
      '/projects/:projectId/assets/:id',
      AssetDetailView,
    )
    await router.isReady()
    const wrapper = mount(AssetDetailView, { global: { plugins: [router, createPinia()] } })
    await flushPromises()

    expect(wrapper.text()).toContain('视频分类')
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/asset-folders`,
      expect.any(Object),
    )
    expect(wrapper.findAll('button').some((button) => button.text() === '更新分类')).toBe(true)
  })

  it('lets a project member request playback only after an explicit click', async () => {
    const fetchMock = vi.fn().mockImplementation((input: string | URL | Request, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith(`/api/v1/projects/${projectId}`)) {
        return Promise.resolve(
          jsonResponse({
            project_id: projectId,
            display_name: '合成测试项目',
            status: 'ACTIVE',
            current_role: 'MEMBER',
            created_at: '2026-07-15T01:03:03Z',
          }),
        )
      }
      if (url.endsWith('/asset-folders')) return Promise.resolve(jsonResponse({ items: [] }))
      if (url.endsWith('/playback-grants')) {
        return Promise.resolve(
          jsonResponse({
            grant_id: 'b0000000-0000-4000-8000-000000000001',
            purpose: 'DATA_REVIEW',
            media_url: 'https://storage.invalid/review.mp4?signature=synthetic',
            expires_at: new Date(Date.now() + 300_000).toISOString(),
            content_type: 'video/mp4',
          }),
        )
      }
      if (url.endsWith('/quality-review')) {
        return Promise.resolve(
          jsonResponse(
            init?.method === 'PUT'
              ? {
                  ...unreviewedQuality(),
                  quality_review_id: 'c0000000-0000-4000-8000-000000000001',
                  decision: 'QUALIFIED',
                  reason_code: 'MEETS_REQUIREMENTS',
                  reviewed_by: 'reviewer',
                  reviewed_at: '2026-07-22T01:02:03Z',
                }
              : unreviewedQuality(),
          ),
        )
      }
      return Promise.resolve(
        jsonResponse({
          clip_asset_id: assetId,
          batch_id: '60000000-0000-4000-8000-000000000001',
          source_upload_file_id: '80000000-0000-4000-8000-000000000001',
          client_file_id: '90000000-0000-4000-8000-000000000001',
          content_type: 'video/mp4',
          size_bytes: 8_388_608,
          sha256: 'a'.repeat(64),
          status: 'INGESTED',
          folder_id: null,
          session_binding: null,
          created_at: '2026-07-15T01:03:03Z',
          updated_at: '2026-07-15T01:03:03Z',
        }),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = testRouter(
      `/projects/${projectId}/assets/${assetId}`,
      '/projects/:projectId/assets/:id',
      AssetDetailView,
    )
    await router.isReady()
    const pinia = createPinia()
    useAuthStore(pinia).session = { username: 'reviewer', role: 'MEMBER' }
    const wrapper = mount(AssetDetailView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    expect(wrapper.find('video').exists()).toBe(false)
    await wrapper.findAll('button').find((button) => button.text() === '播放视频')!.trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${assetId}/playback-grants`,
      expect.objectContaining({ method: 'POST' }),
    )
    expect(wrapper.find('video').attributes('src')).toContain('signature=synthetic')
    expect(wrapper.find('video').attributes('preload')).toBe('none')
    expect(wrapper.text()).toContain('训练数据 · 人工审核')
    expect(wrapper.text()).not.toContain('人工隐私评估')

    await wrapper.find('video').trigger('error')
    expect(wrapper.find('video').exists()).toBe(false)
    expect(wrapper.text()).toContain('视频加载失败')
    expect(wrapper.text()).toContain('重新获取播放授权')

    await wrapper.findAll('button').find((button) => button.text() === '保存质量结论')!.trigger('click')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${assetId}/quality-review`,
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({
          decision: 'QUALIFIED',
          reason_code: 'MEETS_REQUIREMENTS',
          expected_version: 0,
        }),
      }),
    )
    expect(wrapper.text()).toContain('符合训练数据要求')
  })

  it('lets a project member explicitly start media detection for an uploaded video', async () => {
    const fetchMock = vi.fn().mockImplementation((input: string | URL | Request) => {
      const url = String(input)
      if (url.endsWith(`/api/v1/projects/${projectId}`)) {
        return Promise.resolve(jsonResponse({
          project_id: projectId,
          display_name: '合成测试项目',
          status: 'ACTIVE',
          current_role: 'MEMBER',
          created_at: '2026-07-15T01:03:03Z',
        }))
      }
      if (url.endsWith('/media-probe-jobs')) {
        return Promise.resolve(jsonResponse({
          media_job_id: 'b0000000-0000-4000-8000-000000000001',
          project_id: projectId,
          clip_asset_id: assetId,
          job_type: 'MEDIA_PROBE',
          status: 'QUEUED',
        }, 202))
      }
      return Promise.resolve(jsonResponse({
        clip_asset_id: assetId,
        batch_id: '60000000-0000-4000-8000-000000000001',
        source_upload_file_id: '80000000-0000-4000-8000-000000000001',
        client_file_id: '90000000-0000-4000-8000-000000000001',
        content_type: 'video/mp4',
        size_bytes: 8_388_608,
        sha256: 'a'.repeat(64),
        status: 'UPLOADED',
        folder_id: null,
        session_binding: null,
        created_at: '2026-07-15T01:03:03Z',
        updated_at: '2026-07-15T01:03:03Z',
      }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = testRouter(
      `/projects/${projectId}/assets/${assetId}`,
      '/projects/:projectId/assets/:id',
      AssetDetailView,
    )
    await router.isReady()
    const pinia = createPinia()
    useAuthStore(pinia).session = { username: 'reviewer', role: 'MEMBER' }
    const wrapper = mount(AssetDetailView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === '开始媒体检测')!.trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${assetId}/media-probe-jobs`,
      expect.objectContaining({ method: 'POST' }),
    )
    expect(wrapper.text()).toContain('媒体检测已提交')
    expect(wrapper.findAll('button').some((button) => button.text() === '播放视频')).toBe(false)
  })

  it('lets an administrator create one task only after the video is human-qualified', async () => {
    const fetchMock = vi.fn().mockImplementation((input: string | URL | Request, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith(`/api/v1/projects/${projectId}`)) {
        return Promise.resolve(
          jsonResponse({
            project_id: projectId,
            display_name: '合成测试项目',
            status: 'ACTIVE',
            current_role: 'ADMIN',
            created_at: '2026-07-15T01:03:03Z',
          }),
        )
      }
      if (url.endsWith('/asset-folders')) return Promise.resolve(jsonResponse({ items: [] }))
      if (url.endsWith('/memberships')) {
        return Promise.resolve(
          jsonResponse({
            items: [
              {
                membership_id: 'd0000000-0000-4000-8000-000000000001',
                project_id: projectId,
                actor_id: 'local-synthetic-uploader',
                role: 'MEMBER',
                status: 'ACTIVE',
                version: 0,
                updated_at: '2026-07-23T01:00:00Z',
              },
            ],
          }),
        )
      }
      if (url.endsWith('/quality-review')) {
        return Promise.resolve(
          jsonResponse({
            ...unreviewedQuality(),
            quality_review_id: 'c0000000-0000-4000-8000-000000000001',
            decision: 'QUALIFIED',
            reason_code: 'MEETS_REQUIREMENTS',
            reviewed_by: 'local-synthetic-uploader',
            reviewed_at: '2026-07-23T01:00:00Z',
          }),
        )
      }
      if (url.endsWith('/annotation-tasks') && init?.method === 'POST') {
        return Promise.resolve(
          jsonResponse(
            {
              annotation_task_id: 'e0000000-0000-4000-8000-000000000001',
              project_id: projectId,
              clip_asset_id: assetId,
              assignee_actor_id: 'local-synthetic-uploader',
              created_by: 'local-synthetic-admin',
              status: 'ASSIGNED',
              created_at: '2026-07-23T01:00:00Z',
              updated_at: '2026-07-23T01:00:00Z',
              version: 0,
            },
            201,
          ),
        )
      }
      return Promise.resolve(
        jsonResponse({
          clip_asset_id: assetId,
          batch_id: '60000000-0000-4000-8000-000000000001',
          source_upload_file_id: '80000000-0000-4000-8000-000000000001',
          client_file_id: '90000000-0000-4000-8000-000000000001',
          content_type: 'video/mp4',
          size_bytes: 8_388_608,
          sha256: 'a'.repeat(64),
          status: 'INGESTED',
          folder_id: null,
          session_binding: null,
          created_at: '2026-07-15T01:03:03Z',
          updated_at: '2026-07-15T01:03:03Z',
        }),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = testRouter(
      `/projects/${projectId}/assets/${assetId}`,
      '/projects/:projectId/assets/:id',
      AssetDetailView,
    )
    await router.isReady()
    const wrapper = mount(AssetDetailView, { global: { plugins: [router, createPinia()] } })
    await flushPromises()

    expect(wrapper.text()).toContain('标注任务')
    await wrapper.findAll('button').find((button) => button.text() === '创建标注任务')!.trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${assetId}/annotation-tasks`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ assignee_actor_id: 'local-synthetic-uploader' }),
      }),
    )
    expect(wrapper.text()).toContain('标注任务已创建并分配')
  })

  it('loads project folders and renders the asset classification in the explicit project scope', async () => {
    const folderId = 'a0000000-0000-4000-8000-000000000001'
    const fetchMock = vi.fn().mockImplementation((input: string | URL | Request) => {
      const url = String(input)
      if (url.includes('/asset-folders')) {
        return Promise.resolve(
          jsonResponse({
            items: [
              {
                folder_id: folderId,
                project_id: projectId,
                display_name: '待标注',
                version: 0,
                created_at: '2026-07-15T01:03:03Z',
                updated_at: '2026-07-15T01:03:03Z',
              },
            ],
          }),
        )
      }
      return Promise.resolve(
        jsonResponse({
          items: [
            {
              clip_asset_id: assetId,
              batch_id: '60000000-0000-4000-8000-000000000001',
              source_upload_file_id: '80000000-0000-4000-8000-000000000001',
              client_file_id: '90000000-0000-4000-8000-000000000001',
              content_type: 'video/mp4',
              size_bytes: 8_388_608,
              sha256: 'a'.repeat(64),
              status: 'UPLOADED',
              folder_id: folderId,
              session_binding: null,
              created_at: '2026-07-15T01:03:03Z',
              updated_at: '2026-07-15T01:03:03Z',
            },
          ],
          next_cursor: null,
        }),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = testRouter(
      `/projects/${projectId}/assets`,
      '/projects/:projectId/assets',
      AssetListView,
    )
    await router.isReady()
    const wrapper = mount(AssetListView, { global: { plugins: [router, createPinia()] } })
    await flushPromises()

    expect(wrapper.text()).toContain('待标注')
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining(`/api/v1/projects/${projectId}/clip-assets`),
      expect.any(Object),
    )
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/asset-folders`,
      expect.any(Object),
    )
  })
})

function testRouter(
  path: string,
  routePath: string,
  component: typeof SessionListView | typeof AssetDetailView | typeof AssetListView,
) {
  const EmptyView = { template: '<div />' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/projects/:projectId/sessions',
        name: 'session-list',
        component: routePath === '/projects/:projectId/sessions' ? component : EmptyView,
      },
      {
        path: '/projects/:projectId/sessions/:id',
        name: 'session-detail',
        component: EmptyView,
      },
      {
        path: '/projects/:projectId/assets',
        name: 'asset-list',
        component: routePath === '/projects/:projectId/assets' ? component : EmptyView,
      },
      {
        path: '/projects/:projectId/assets/:id',
        name: 'asset-detail',
        component: routePath === '/projects/:projectId/assets/:id' ? component : EmptyView,
      },
      {
        path: '/projects/:projectId/annotation-tasks',
        name: 'annotation-task-list',
        component: EmptyView,
      },
    ],
  })
  void router.push(path)
  return router
}

function jsonResponse(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function unreviewedQuality() {
  return {
    quality_review_id: null,
    project_id: projectId,
    clip_asset_id: assetId,
    decision: 'UNREVIEWED',
    reason_code: null,
    reviewed_by: null,
    reviewed_at: null,
    version: 0,
  }
}
