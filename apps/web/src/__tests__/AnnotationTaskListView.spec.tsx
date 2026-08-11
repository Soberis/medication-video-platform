import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { renderRoute } from '@/test/render'
import AnnotationTaskListView from '@/views/AnnotationTaskListView'

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
  can_recheck_post_cleaning: false,
  annotation_revision: null,
  created_at: '2026-07-23T01:00:00Z',
  updated_at: '2026-07-23T01:00:00Z',
  version: 0,
}

afterEach(() => vi.unstubAllGlobals())

describe('annotation task list view', () => {
  it('loads the task queue and label schema together, then links back to the video', async () => {
    const fetchMock = stubTasks([assignedTask], labelSchema)
    renderView()
    expect(await screen.findByText('local-synthetic-uploader')).toBeInTheDocument()
    expect(await screen.findByLabelText('项目配药动作标注进度')).toHaveTextContent(
      '项目任务1待提交1待修改0待复核0已完成0',
    )
    expect(screen.getByText('已分配')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /提交视频/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: assetId })).toHaveAttribute(
      'href',
      `/projects/${projectId}/assets/${assetId}`,
    )
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-label-schema`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })

  it('shows schema display names and submits the selected label id', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/annotation-label-schema')) return jsonResponse(labelSchema)
      if (url.endsWith('/annotation-tasks') && init?.method === 'GET')
        return jsonResponse(taskList([assignedTask]))
      if (url.endsWith(`/annotation-tasks/${taskId}/revisions`) && init?.method === 'POST')
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
      throw new Error(`Unexpected fetch: ${init?.method ?? 'GET'} ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderView()
    await user.click(await screen.findByRole('button', { name: /提交视频/ }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('combobox', { name: '标注片段 1 的标签' }))
    expect(await screen.findByText('准备工作区')).toBeInTheDocument()
    await user.click(screen.getByText('拿取药品'))
    await user.click(within(dialog).getByRole('button', { name: '提交配药动作标注' }))
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
      can_recheck_post_cleaning: true,
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
    stubTasks(tasks, labelSchema)
    renderView()
    expect(await screen.findByText('当前规则检查通过')).toBeInTheDocument()
    for (const { expected } of outcomes) expect(screen.getByText(expected)).toBeInTheDocument()
    expect(screen.getByText(/尚未进入训练数据集/)).toBeInTheDocument()
    expect(screen.getByText(/不会自动准入数据集/)).toBeInTheDocument()
    expect(screen.getByText(/缺少已验证的视频时长/)).toBeInTheDocument()
    expect(screen.getByText(/其他清洗原因（FUTURE_RULE_CHECK）/)).toBeInTheDocument()
    expect(screen.getByText(/当前版本默认不准入数据集/)).toBeInTheDocument()
    expect(screen.getByText(/主流程时间片段存在重叠/)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /重新核对视频/ })).toHaveLength(4)
    expect(screen.queryByRole('button', { name: /提交视频/ })).not.toBeInTheDocument()
  }, 20_000)

  it('reloads the current task state from the server when the page is re-entered', async () => {
    const submittedTask = {
      ...assignedTask,
      status: 'SUBMITTED',
      can_submit: false,
      annotation_revision: { ...completedRevision, review: null, post_cleaning_result: null },
    }
    const responses = [taskList([assignedTask]), taskList([submittedTask])]
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/annotation-label-schema')) return jsonResponse(labelSchema)
      if (url.endsWith('/annotation-tasks')) {
        return jsonResponse(responses.shift() ?? taskList([submittedTask]))
      }
      throw new Error(`Unexpected fetch: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const firstRender = renderView()
    expect(await screen.findByText('已分配')).toBeInTheDocument()
    firstRender.unmount()

    renderView()
    expect(await screen.findByText('已提交')).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).endsWith('/annotation-tasks')),
    ).toHaveLength(2)
  })

  it('marks an administrator review of their own revision as SELF_ADMIN_APPROVAL', async () => {
    stubTasks(
      [
        {
          ...assignedTask,
          assignee_actor_id: 'local-synthetic-admin',
          status: 'COMPLETED',
          can_submit: false,
          can_recheck_post_cleaning: true,
          annotation_revision: {
            ...completedRevision,
            submitted_by: 'local-synthetic-admin',
            review: { ...completedRevision.review, reviewed_by: 'local-synthetic-admin' },
          },
        },
      ],
      labelSchema,
    )
    renderView()

    expect(await screen.findByText('复核通过')).toBeInTheDocument()
    expect(screen.getByText('管理员自审（SELF_ADMIN_APPROVAL）')).toBeInTheDocument()
    expect(screen.getByText('当前规则检查通过')).toBeInTheDocument()
  })

  it('shows the whole page as failed when the label schema cannot be loaded', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation((input: RequestInfo | URL) =>
          Promise.resolve(
            String(input).endsWith('/annotation-label-schema')
              ? jsonResponse({ error_code: 'ANNOTATION_LABEL_SCHEMA_NOT_FOUND' }, { status: 404 })
              : jsonResponse(taskList([assignedTask])),
          ),
        ),
    )
    renderView()
    expect(await screen.findByText('ANNOTATION_LABEL_SCHEMA_NOT_FOUND')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })
})

function renderView() {
  return renderRoute(<AnnotationTaskListView />, {
    route: `/projects/${projectId}/annotation-tasks`,
    path: '/projects/:projectId/annotation-tasks',
  })
}
function stubTasks(tasks: object[], schema: object) {
  const fetchMock = vi
    .fn()
    .mockImplementation((input: RequestInfo | URL) =>
      Promise.resolve(
        String(input).endsWith('/annotation-label-schema')
          ? jsonResponse(schema)
          : jsonResponse(taskList(tasks)),
      ),
    )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}
function taskList(tasks: object[]) {
  const queue = tasks as Array<{
    status?: string
    annotation_revision?: { review?: { decision?: string } | null } | null
  }>
  const reworkRequired = queue.filter(
    (task) =>
      task.status === 'ASSIGNED' && task.annotation_revision?.review?.decision === 'REJECTED',
  ).length
  return {
    items: tasks,
    progress: {
      scope: 'PROJECT',
      total: tasks.length,
      awaiting_submission: queue.filter(
        (task) =>
          task.status === 'ASSIGNED' && task.annotation_revision?.review?.decision !== 'REJECTED',
      ).length,
      rework_required: reworkRequired,
      awaiting_review: queue.filter((task) => task.status === 'SUBMITTED').length,
      completed: queue.filter((task) => task.status === 'COMPLETED').length,
    },
  }
}
function jsonResponse(body: object, init?: ResponseInit) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
}
