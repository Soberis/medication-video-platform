import { screen, waitFor, within } from '@testing-library/react'
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
      '项目任务1待处理1待提交 1 · 待修改 0 · 待复核 0标注完成0',
    )
    expect(screen.getByText('已分配')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /提交视频/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '导出训练数据' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /视频 50000000.*0001/ })).toHaveAttribute(
      'href',
      `/projects/${projectId}/assets/${assetId}`,
    )
    expect(screen.getByRole('table').closest('.ant-table-wrapper')).toHaveClass(
      'annotation-task-table',
    )
    expect(screen.getByRole('columnheader', { name: '当前进度' })).toBeInTheDocument()
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
    await user.click(within(dialog).getByRole('combobox', { name: '标注时间段 1 的标签' }))
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
      { outcome: 'PASS', expected: '已通过清洗' },
      { outcome: 'WARNING', expected: '清洗待确认' },
      { outcome: 'REJECT', expected: '清洗未通过' },
      { outcome: null, expected: '尚未执行清洗' },
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
    const user = userEvent.setup()
    renderView()
    expect(await screen.findByText('已通过清洗')).toBeInTheDocument()
    for (const { expected } of outcomes) expect(screen.getByText(expected)).toBeInTheDocument()
    const evidenceDetails = screen.getAllByText('查看任务记录')
    expect(evidenceDetails).toHaveLength(4)
    await user.click(evidenceDetails[1]!)
    expect(evidenceDetails[1]?.closest('details')).toHaveAttribute('open')
    expect(screen.getAllByText(/创建者：local-synthetic-admin/)).toHaveLength(4)
    expect(await screen.findByText(/当前数据暂不可用于训练/)).toBeInTheDocument()
    expect(screen.getByText(/缺少已确认的视频时长/)).toBeInTheDocument()
    expect(screen.getByText(/其他清洗原因（FUTURE_RULE_CHECK）/)).toBeInTheDocument()
    await user.click(evidenceDetails[2]!)
    expect(
      await screen.findByText(/当前数据未通过清洗，暂不可用于训练；视频资产未被删除/),
    ).toBeInTheDocument()
    expect(screen.getByText(/主流程时间段存在重叠/)).toBeInTheDocument()
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

  it('marks an administrator review of their own revision as self-approved', async () => {
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
    expect(screen.getByText('管理员自审')).toBeInTheDocument()
    expect(screen.getByText('已通过清洗')).toBeInTheDocument()
  })

  it('loads a project export preview only after the administrator requests it', async () => {
    const preview = trainingDataPreview(12)
    const fetchMock = stubTrainingData([assignedTask], labelSchema, preview)
    const user = userEvent.setup()
    renderView()

    await user.click(await screen.findByRole('button', { name: '导出训练数据' }))
    const dialog = await screen.findByRole('dialog')

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/training-data/preview`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
    expect(dialog).toHaveTextContent('12 条')
    expect(dialog).toHaveTextContent('格式Parquet')
    expect(dialog).toHaveTextContent('包含手部关键点 + 人工动作标签')
    expect(dialog).toHaveTextContent('原始视频不包含')
    expect(dialog).toHaveTextContent(
      'MediaPipe 候选仅作为手部观测数据，不是人工真值；动作标签来自人工正式标注。',
    )
  })

  it('cancels the preview without requesting a download', async () => {
    const fetchMock = stubTrainingData([assignedTask], labelSchema, trainingDataPreview(3))
    const user = userEvent.setup()
    renderView()

    await user.click(await screen.findByRole('button', { name: '导出训练数据' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: /取消/ }))

    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).endsWith('/training-data/download')),
    ).toHaveLength(0)
  })

  it('downloads the confirmed Parquet blob with a fixed filename and revokes its object URL', async () => {
    const fetchMock = stubTrainingData([assignedTask], labelSchema, trainingDataPreview(3))
    const createObjectUrl = vi.fn(() => 'blob:training-data')
    const revokeObjectUrl = vi.fn()
    const NativeURL = globalThis.URL
    class TestURL extends NativeURL {}
    Object.assign(TestURL, { createObjectURL: createObjectUrl, revokeObjectURL: revokeObjectUrl })
    vi.stubGlobal('URL', TestURL)
    const anchorClick = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined)
    const user = userEvent.setup()
    renderView()

    await user.click(await screen.findByRole('button', { name: '导出训练数据' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '导出' }))

    await waitFor(() => expect(anchorClick).toHaveBeenCalledOnce())
    const link = anchorClick.mock.instances[0] as HTMLAnchorElement
    expect(link.download).toBe('training_data.parquet')
    expect(link.href).toBe('blob:training-data')
    expect(createObjectUrl).toHaveBeenCalledWith(expect.any(Blob))
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:training-data')
    expect(document.body.contains(link)).toBe(false)
    expect(await screen.findByText('已开始下载 training_data.parquet。')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/training-data/download`,
      expect.objectContaining({
        method: 'GET',
        cache: 'no-store',
        headers: { Accept: 'application/vnd.apache.parquet' },
      }),
    )
    anchorClick.mockRestore()
  })

  it('disables export confirmation and explains when no training rows are eligible', async () => {
    stubTrainingData([assignedTask], labelSchema, trainingDataPreview(0))
    const user = userEvent.setup()
    renderView()

    await user.click(await screen.findByRole('button', { name: '导出训练数据' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('0 条')
    expect(dialog).toHaveTextContent('当前没有符合条件的训练行，暂时无法导出。')
    expect(within(dialog).getByRole('button', { name: /导出/ })).toBeDisabled()
  })

  it('does not show the export action for an assignee-scoped member queue', async () => {
    stubTasks([assignedTask], labelSchema, 'ASSIGNEE')
    renderView()

    expect(await screen.findByText('我的任务')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '导出训练数据' })).not.toBeInTheDocument()
  })

  it('shows safe Chinese feedback when the preview request is rejected', async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/annotation-label-schema'))
        return Promise.resolve(jsonResponse(labelSchema))
      if (url.endsWith('/training-data/preview')) {
        return Promise.resolve(
          jsonResponse(
            {
              error_code: 'TRAINING_DATA_EXPORT_EVIDENCE_INVALID',
              error_message: 'java.lang.IllegalStateException https://internal.invalid/stack',
            },
            { status: 422 },
          ),
        )
      }
      return Promise.resolve(jsonResponse(taskList([assignedTask])))
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderView()

    await user.click(await screen.findByRole('button', { name: '导出训练数据' }))
    expect(await screen.findByText('当前可用数据证据不完整，暂时无法导出。')).toBeInTheDocument()
    expect(screen.queryByText(/java\.lang|internal\.invalid/)).not.toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
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
function stubTasks(tasks: object[], schema: object, scope: 'PROJECT' | 'ASSIGNEE' = 'PROJECT') {
  const fetchMock = vi
    .fn()
    .mockImplementation((input: RequestInfo | URL) =>
      Promise.resolve(
        String(input).endsWith('/annotation-label-schema')
          ? jsonResponse(schema)
          : jsonResponse(taskList(tasks, scope)),
      ),
    )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}
function stubTrainingData(
  tasks: object[],
  schema: object,
  preview: object,
  downloadBody = 'PAR1 synthetic parquet',
) {
  const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.endsWith('/annotation-label-schema')) return Promise.resolve(jsonResponse(schema))
    if (url.endsWith('/training-data/preview')) return Promise.resolve(jsonResponse(preview))
    if (url.endsWith('/training-data/download')) {
      return Promise.resolve({
        ok: true,
        blob: () => Promise.resolve(new Blob([downloadBody])),
      } as Response)
    }
    if (url.endsWith('/annotation-tasks') && init?.method === 'GET') {
      return Promise.resolve(jsonResponse(taskList(tasks)))
    }
    return Promise.reject(new Error(`Unexpected fetch: ${init?.method ?? 'GET'} ${url}`))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}
function taskList(tasks: object[], scope: 'PROJECT' | 'ASSIGNEE' = 'PROJECT') {
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
      scope,
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
function trainingDataPreview(trainingRowCount: number) {
  return {
    schema_version: 'training-data.v2',
    eligible_video_count: 2,
    candidate_observation_count: 14,
    training_row_count: trainingRowCount,
    unlabeled_candidate_observation_count: 2,
    training_row_definition:
      'one MediaPipe Candidate hand observation inside one APPROVED human action interval [start_ms, end_ms)',
    file_name: 'training_data.parquet',
    content_type: 'application/vnd.apache.parquet',
    includes_mp4: false,
  }
}
function jsonResponse(body: object, init?: ResponseInit) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
}
