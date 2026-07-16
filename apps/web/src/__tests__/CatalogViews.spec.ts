import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import AssetDetailView from '@/views/AssetDetailView.vue'
import SessionListView from '@/views/SessionListView.vue'

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
    const router = testRouter('/sessions', SessionListView)
    await router.isReady()
    const wrapper = mount(SessionListView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('已绑定 1 / 2')
    expect(wrapper.text()).toContain('已入库 0')
    expect(wrapper.text()).toContain('待检测')
    expect(wrapper.text()).toContain('组装中')
  })

  it('explains why preview is unavailable without rendering a player or storage location', async () => {
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
          session_binding: null,
          created_at: '2026-07-15T01:03:03Z',
          updated_at: '2026-07-15T01:03:03Z',
        }),
      ),
    )
    const router = testRouter(`/assets/${assetId}`, AssetDetailView)
    await router.isReady()
    const wrapper = mount(AssetDetailView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('视频预览尚未开放')
    expect(wrapper.text()).toContain('媒体处理、隐私评估和预览授权')
    expect(wrapper.text()).toContain('尚未绑定到 CaptureSession')
    expect(wrapper.find('video').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('object_key')
    expect(wrapper.html()).not.toContain('storage_bucket')
  })
})

function testRouter(path: string, component: typeof SessionListView | typeof AssetDetailView) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: path.includes(':') ? path : path.replace(assetId, ':id'), component }],
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
