import { flushPromises, mount } from '@vue/test-utils'
import { ElForm, ElOption, ElSelect } from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import AnnotationTaskListView from '@/views/AnnotationTaskListView.vue'

const projectId = '30000000-0000-4000-8000-000000000001'
const assetId = '50000000-0000-4000-8000-000000000001'
const taskId = 'a0000000-0000-4000-8000-000000000001'
const labelSchemaId = '70000000-0000-4000-8000-000000000001'
const labelSchemaDigest = 'b'.repeat(64)
const completedRevision = {
  annotation_revision_id: '80000000-0000-4000-8000-000000000001',
  label_schema_id: labelSchemaId,
  label_schema_digest: labelSchemaDigest,
  revision_number: 1,
  annotation_document: {
    schema_version: 'annotation-document.v1',
    segments: [{ start_ms: 0, end_ms: 1000, label: 'prepare_workspace' }],
  },
  submitted_by: 'local-synthetic-uploader',
  submitted_at: '2026-07-26T01:00:00Z',
  review: {
    annotation_review_id: '81000000-0000-4000-8000-000000000001',
    project_id: projectId,
    annotation_revision_id: '80000000-0000-4000-8000-000000000001',
    decision: 'APPROVED',
    review_note: null,
    reviewed_by: 'local-synthetic-admin',
    reviewed_at: '2026-07-26T01:10:00Z',
  },
  post_cleaning_result: {
    cleaning_result_id: '82000000-0000-4000-8000-000000000001',
    cleaning_run_id: '83000000-0000-4000-8000-000000000001',
    rule_set_key: 'p0-post-annotation',
    rule_set_version: 'p0-post-annotation.v1',
    rule_set_digest: 'd'.repeat(64),
    outcome: 'PASS',
    reason_codes: [],
    executed_checks: ['PRIMARY_STEP_LABEL_ALLOWED', 'PRIMARY_STEP_NO_OVERLAP'],
    evaluated_at: '2026-07-26T01:10:00Z',
  },
}

const labelSchema = {
  label_schema_id: labelSchemaId,
  schema_key: 'medication-primary-step',
  schema_version: 'p0-primary-step.v1',
  schema_digest: labelSchemaDigest,
  layer: 'primary_step',
  labels: [
    {
      label_id: 'retrieve_medicine',
      display_name: '拿取药品',
      description: '从工作区取得本次配制所需药品。',
      sort_order: 20,
    },
    {
      label_id: 'prepare_workspace',
      display_name: '准备工作区',
      description: '准备本次配制所需的工作区域。',
      sort_order: 10,
    },
  ],
}

const assignedTask = {
  annotation_task_id: taskId,
  project_id: projectId,
  clip_asset_id: assetId,
  label_schema_id: labelSchemaId,
  label_schema_digest: labelSchemaDigest,
  assignee_actor_id: 'local-synthetic-uploader',
  created_by: 'local-synthetic-admin',
  status: 'ASSIGNED',
  can_submit: true,
  can_review: false,
  annotation_revision: null,
  created_at: '2026-07-23T01:00:00Z',
  updated_at: '2026-07-23T01:00:00Z',
  version: 0,
}

afterEach(() => {
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

describe('annotation task list view', () => {
  it('loads the task queue and label schema together, then links back to the video', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ items: [assignedTask] }))
      .mockResolvedValueOnce(jsonResponse(labelSchema))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('标注任务')
    expect(wrapper.text()).toContain('已分配')
    expect(wrapper.text()).toContain('提交标注')
    expect(wrapper.text()).toContain('local-synthetic-uploader')
    expect(wrapper.find('a').attributes('href')).toBe(`/projects/${projectId}/assets/${assetId}`)
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-label-schema`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })

  it('shows schema display names and submits the selected label id', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/annotation-label-schema')) return jsonResponse(labelSchema)
      if (url.endsWith('/annotation-tasks') && init?.method === 'GET') {
        return jsonResponse({ items: [assignedTask] })
      }
      if (url.endsWith(`/annotation-tasks/${taskId}/revisions`) && init?.method === 'POST') {
        return jsonResponse({
          annotation_revision_id: '80000000-0000-4000-8000-000000000001',
          project_id: projectId,
          annotation_task_id: taskId,
          clip_asset_id: assetId,
          label_schema_id: labelSchemaId,
          label_schema_digest: labelSchemaDigest,
          revision_number: 1,
          annotation_schema_version: 'annotation-document.v1',
          document_sha256: 'c'.repeat(64),
          submitted_by: 'local-synthetic-uploader',
          submitted_at: '2026-07-26T01:00:00Z',
        })
      }
      throw new Error(`Unexpected fetch: ${init?.method ?? 'GET'} ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountView()

    const openButton = wrapper.findAll('button').find((button) => button.text() === '提交标注')
    expect(openButton).toBeDefined()
    await openButton!.trigger('click')
    await flushPromises()

    expect(wrapper.findAllComponents(ElOption).map((option) => option.props('label'))).toEqual([
      '准备工作区',
      '拿取药品',
    ])
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'retrieve_medicine')
    await wrapper.findComponent(ElForm).trigger('submit')
    await flushPromises()

    const revisionCall = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(revisionCall).toBeDefined()
    expect(JSON.parse(String(revisionCall?.[1]?.body))).toEqual({
      segments: [{ start_ms: 0, end_ms: 1000, label: 'retrieve_medicine' }],
    })
  })

  it('shows the completed state and each post-cleaning outcome without adding another page', async () => {
    const outcomes = [
      { outcome: 'PASS', expected: '当前规则检查通过' },
      { outcome: 'WARNING', expected: '后置清洗待确认' },
      { outcome: 'REJECT', expected: '后置清洗不通过' },
      { outcome: null, expected: '后置清洗未执行' },
    ] as const
    const tasks = outcomes.map(({ outcome }, index) => ({
      ...assignedTask,
      annotation_task_id: `a0000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
      status: 'COMPLETED',
      can_submit: false,
      annotation_revision: {
        ...completedRevision,
        annotation_revision_id: `80000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
        post_cleaning_result:
          outcome === null
            ? null
            : {
                ...completedRevision.post_cleaning_result,
                outcome,
                reason_codes:
                  outcome === 'WARNING'
                    ? ['VERIFIED_MEDIA_DURATION_UNAVAILABLE', 'FUTURE_RULE_CHECK']
                    : outcome === 'REJECT'
                      ? ['PRIMARY_STEP_OVERLAP']
                      : [],
              },
      },
    }))
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse({ items: tasks }))
        .mockResolvedValueOnce(jsonResponse(labelSchema)),
    )

    const wrapper = await mountView()

    expect(wrapper.text()).toContain('已完成')
    for (const { expected } of outcomes) expect(wrapper.text()).toContain(expected)
    expect(wrapper.text()).toContain('尚未进入训练数据集')
    expect(wrapper.text()).toContain('不会自动准入数据集')
    expect(wrapper.text()).toContain('缺少已验证的视频时长')
    expect(wrapper.text()).toContain('其他清洗原因（FUTURE_RULE_CHECK）')
    expect(wrapper.text()).toContain('当前版本默认不准入数据集')
    expect(wrapper.text()).toContain('视频资产未被删除')
    expect(wrapper.text()).toContain('主流程时间片段存在重叠')
    expect(wrapper.text()).not.toContain('提交标注')
  })

  it('shows the whole page as failed when the label schema cannot be loaded', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse({ items: [assignedTask] }))
        .mockResolvedValueOnce(
          jsonResponse({ error_code: 'ANNOTATION_LABEL_SCHEMA_NOT_FOUND' }, { status: 404 }),
        ),
    )
    const wrapper = await mountView()

    expect(wrapper.find('.catalog-error').exists()).toBe(true)
    expect(wrapper.text()).toContain('ANNOTATION_LABEL_SCHEMA_NOT_FOUND')
    expect(wrapper.find('.el-table').exists()).toBe(false)
  })
})

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/projects/:projectId/annotation-tasks', name: 'annotation-task-list', component: AnnotationTaskListView },
      { path: '/projects/:projectId/assets/:id', name: 'asset-detail', component: { template: '<div />' } },
    ],
  })
  await router.push(`/projects/${projectId}/annotation-tasks`)
  await router.isReady()
  const wrapper = mount(AnnotationTaskListView, {
    global: { plugins: [router] },
  })
  await flushPromises()
  return wrapper
}

function jsonResponse(body: object, init?: ResponseInit) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
}
