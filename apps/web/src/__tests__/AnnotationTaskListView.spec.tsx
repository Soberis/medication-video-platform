import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { paths } from '@/app/paths'
import { renderRouter } from '@/test/render'
import AnnotationTaskListView from '@/views/AnnotationTaskListView'

const projectId = '30000000-0000-4000-8000-000000000001'
const assetId = '50000000-0000-4000-8000-000000000001'
const taskId = 'a0000000-0000-4000-8000-000000000001'
const captureGroupId = '60000000-0000-4000-8000-000000000001'
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
  can_select_primary: false,
  can_review: false,
  can_recheck_post_cleaning: false,
  annotation_revision: null,
  created_at: '2026-07-23T01:00:00Z',
  updated_at: '2026-07-23T01:00:00Z',
  version: 0,
}

const assignedGroupTask = {
  ...assignedTask,
  annotation_task_id: 'a0000000-0000-4000-8000-000000000010',
  clip_asset_id: null,
  capture_group_id: captureGroupId,
  primary_clip_asset_id: assetId,
  can_select_primary: true,
}

afterEach(() => vi.unstubAllGlobals())

describe('annotation task list view', () => {
  it('loads the task queue and keeps annotation navigation as a top-level context', async () => {
    const fetchMock = stubTasks([assignedTask], labelSchema)
    const user = userEvent.setup()
    renderView()
    expect(await screen.findByText('local-synthetic-uploader')).toBeInTheDocument()
    const progressSummary = await screen.findByLabelText('项目配药动作标注进度')
    expect(progressSummary).toHaveTextContent('1 个任务')
    expect(progressSummary).toHaveTextContent('0 个已完成 · 1 个待处理')
    expect(screen.getByText('待标注')).toBeInTheDocument()
    expect(screen.getByText('下一步')).toBeInTheDocument()
    expect(
      screen.getByText('处理已分配的配药动作标注任务；提交后由管理员复核。'),
    ).toBeInTheDocument()
    const breadcrumb = screen.getByRole('navigation', { name: '面包屑导航' })
    expect(breadcrumb).toHaveTextContent('配药动作标注')
    expect(within(breadcrumb).queryByRole('link', { name: '视频管理' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '任务队列' })).toBeInTheDocument()
    await user.click(screen.getByText('查看详情'))
    expect(screen.getByText(/a0000000.*0001/)).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /进入视频 .*配药动作标注工作台/ }),
    ).toBeInTheDocument()
    const projectTools = screen.getByText('更多项目操作').closest('details')
    if (!projectTools) throw new Error('Project tools disclosure was not mounted.')
    expect(projectTools).not.toHaveAttribute('open')
    await user.click(within(projectTools).getByText('更多项目操作'))
    expect(projectTools).toHaveAttribute('open')
    expect(screen.getByRole('button', { name: '导出训练数据' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '预览 training-data.v3' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /视频 50000000.*0001/ })).toHaveAttribute(
      'href',
      `/projects/${projectId}/assets/${assetId}`,
    )
    expect(screen.getByRole('list', { name: '配药动作标注任务列表' })).toHaveClass(
      'annotation-task-list-surface',
    )
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-label-schema`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })

  it('explains that qualified events enter the annotation flow automatically when the queue is empty', async () => {
    stubTasks([], labelSchema)
    renderView()

    const emptyState = await screen.findByText(
      '当前没有待处理的配药动作标注任务。符合质量准入条件的配药事件会自动生成标注任务。',
    )
    expect(emptyState).toBeInTheDocument()
    expect(emptyState).not.toHaveTextContent(/管理员|创建并分配/)
  })

  it('makes the workbench the only primary action for an assigned task', async () => {
    const fetchMock = stubTasks([assignedTask], labelSchema)
    const { router } = renderView()
    const user = userEvent.setup()
    const action = await screen.findByRole('button', {
      name: /进入视频 .*配药动作标注工作台/,
    })
    expect(action).toBeInTheDocument()
    await user.click(action)
    await waitFor(() =>
      expect(router.state.location.pathname).toBe(paths.annotationTask(projectId, taskId)),
    )
    expect(fetchMock).not.toHaveBeenCalledWith(
      expect.stringContaining('/revisions'),
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('routes an assigned CaptureGroup task to event annotation', async () => {
    stubTasks([assignedGroupTask], labelSchema)
    const { router } = renderView()
    const user = userEvent.setup()

    const taskRow = await screen.findByRole('article', { name: /配药事件 .*待标注/ })
    await user.click(within(taskRow).getByRole('button', { name: /进入配药动作标注/ }))

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(paths.captureGroup(projectId, captureGroupId))
      expect(router.state.location.search).toBe('?focus=annotation')
    })
    expect(screen.queryByRole('button', { name: /配药动作标注工作台/ })).not.toBeInTheDocument()
  })

  it('routes an assigned CaptureGroup task without a primary view to primary selection', async () => {
    const task = {
      ...assignedGroupTask,
      annotation_task_id: 'a0000000-0000-4000-8000-000000000015',
      primary_clip_asset_id: null,
      can_submit: false,
      can_select_primary: true,
    }
    stubTasks([task], labelSchema)
    const { router } = renderView()
    const user = userEvent.setup()

    const taskRow = await screen.findByRole('article', { name: /配药事件 .*待标注/ })
    await user.click(within(taskRow).getByRole('button', { name: /选择主视图/ }))

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(paths.captureGroup(projectId, captureGroupId))
      expect(router.state.location.search).toBe('?focus=annotation')
    })
  })

  it('routes a submitted CaptureGroup task to Review only when the server grants review', async () => {
    const reviewableGroupTask = {
      ...assignedGroupTask,
      annotation_task_id: 'a0000000-0000-4000-8000-000000000011',
      status: 'SUBMITTED',
      can_submit: false,
      can_review: true,
      annotation_revision: { ...completedRevision, review: null, post_cleaning_result: null },
    }
    stubTasks([reviewableGroupTask], labelSchema)
    const { router } = renderView()
    const user = userEvent.setup()

    const taskRow = await screen.findByRole('article', { name: /配药事件 .*待复核/ })
    await user.click(within(taskRow).getByRole('button', { name: /进入人工复核/ }))

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(paths.captureGroup(projectId, captureGroupId))
      expect(router.state.location.search).toBe('?focus=review')
    })
  })

  it('routes a returned CaptureGroup revision back to annotation rework', async () => {
    const returnedGroupTask = {
      ...assignedGroupTask,
      annotation_task_id: 'a0000000-0000-4000-8000-000000000012',
      annotation_revision: {
        ...completedRevision,
        review: {
          ...completedRevision.review,
          decision: 'REJECTED',
          review_note: '请补充动作时间段。',
        },
        post_cleaning_result: null,
      },
    }
    stubTasks([returnedGroupTask], labelSchema)
    const { router } = renderView()
    const user = userEvent.setup()

    const taskRow = await screen.findByRole('article', { name: /配药事件 .*待修改/ })
    await user.click(within(taskRow).getByRole('button', { name: /进入标注返工/ }))

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(paths.captureGroup(projectId, captureGroupId))
      expect(router.state.location.search).toBe('?focus=annotation')
    })
  })

  it('routes a completed CaptureGroup task to its Review / Cleaning result context', async () => {
    const completedGroupTask = {
      ...assignedGroupTask,
      annotation_task_id: 'a0000000-0000-4000-8000-000000000013',
      status: 'COMPLETED',
      can_submit: false,
      annotation_revision: completedRevision,
    }
    stubTasks([completedGroupTask], labelSchema)
    const { router } = renderView()
    const user = userEvent.setup()

    const taskRow = await screen.findByRole('article', { name: /配药事件 .*标注完成/ })
    await user.click(within(taskRow).getByRole('button', { name: /查看复核 \/ 训练资格检查结果/ }))

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(paths.captureGroup(projectId, captureGroupId))
      expect(router.state.location.search).toBe('?focus=cleaning')
    })
  })

  it('uses the CaptureGroup cleaning fact for completed task status', async () => {
    const completedGroupTask = {
      ...assignedGroupTask,
      annotation_task_id: 'a0000000-0000-4000-8000-000000000016',
      status: 'COMPLETED',
      can_submit: false,
      can_select_primary: false,
      annotation_revision: {
        ...completedRevision,
        capture_group_id: captureGroupId,
        post_cleaning_result: null,
        capture_group_cleaning: {
          capture_group_cleaning_id: '82000000-0000-4000-8000-000000000016',
          outcome: 'PASS',
          reason_codes: [],
          evaluated_at: '2026-07-26T01:10:00Z',
          participating_views: [],
          excluded_views: [],
        },
      },
    }
    stubTasks([completedGroupTask], labelSchema)
    renderView()

    expect(await screen.findByText('训练资格检查 · 当前规则检查通过')).toBeInTheDocument()
  })

  it('does not invent a CaptureGroup action when the server limits task permission', async () => {
    const deniedGroupTask = {
      ...assignedGroupTask,
      annotation_task_id: 'a0000000-0000-4000-8000-000000000014',
      can_submit: false,
      can_select_primary: false,
    }
    stubTasks([deniedGroupTask], labelSchema)
    renderView()

    const taskRow = await screen.findByRole('article', { name: /配药事件 .*待标注/ })
    expect(within(taskRow).queryByRole('button', { name: /进入配药事件/ })).not.toBeInTheDocument()
    expect(within(taskRow).getByRole('link', { name: /配药事件/ })).toHaveAttribute(
      'href',
      paths.captureGroup(projectId, captureGroupId),
    )
  })

  it('shows the completed state and each post-cleaning outcome without adding another page', async () => {
    const outcomes = [
      { outcome: 'PASS', expected: '清洗 · 当前规则检查通过' },
      { outcome: 'WARNING', expected: '清洗 · 需处理' },
      { outcome: 'REJECT', expected: '清洗 · 清洗未通过' },
      { outcome: null, expected: '清洗 · 待清洗' },
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
    expect(await screen.findByText('清洗 · 当前规则检查通过')).toBeInTheDocument()
    for (const { expected } of outcomes) expect(screen.getByText(expected)).toBeInTheDocument()
    const completedRow = screen.getAllByRole('article')[0]!
    const annotationDetails = within(completedRow).getByText('查看详情')
    await user.click(annotationDetails)
    expect(within(completedRow).getByText(/时间段 1.*0.*1000.*毫秒/)).toBeInTheDocument()
    expect(within(completedRow).getByText('准备工作区')).toBeInTheDocument()
    const evidenceDetails = screen.getAllByText('查看详情')
    expect(evidenceDetails).toHaveLength(4)
    await user.click(evidenceDetails[1]!)
    expect(evidenceDetails[1]?.closest('details')).toHaveAttribute('open')
    expect(within(completedRow).getByText('提交人')).toBeInTheDocument()
    expect(within(completedRow).getByText('复核人')).toBeInTheDocument()
    expect(await screen.findByText(/当前数据暂不可用于训练/)).toBeInTheDocument()
    expect(screen.getByText(/缺少已确认的视频时长/)).toBeInTheDocument()
    expect(screen.getByText(/其他清洗原因（FUTURE_RULE_CHECK）/)).toBeInTheDocument()
    await user.click(evidenceDetails[2]!)
    expect(
      await screen.findByText(/当前数据未通过清洗，暂不可用于训练；视频资产未被删除/),
    ).toBeInTheDocument()
    expect(screen.getByText(/主流程时间段存在重叠/)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /进入视频 .*配药动作标注工作台/ })).toHaveLength(2)
    expect(screen.queryByRole('button', { name: /通过复核/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /退回修改/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /重新核对视频/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /提交视频/ })).not.toBeInTheDocument()
  }, 20_000)

  it('routes a completed warning or rejected cleaning result to the workbench', async () => {
    const completedTask = {
      ...assignedTask,
      status: 'COMPLETED',
      can_submit: false,
      can_recheck_post_cleaning: true,
      annotation_revision: {
        ...completedRevision,
        post_cleaning_result: {
          ...completedRevision.post_cleaning_result,
          outcome: 'WARNING',
          reason_codes: ['VERIFIED_MEDIA_DURATION_UNAVAILABLE'],
        },
      },
    }
    stubTasks([completedTask], labelSchema)
    const user = userEvent.setup()
    const { router } = renderView()

    const taskRow = await screen.findByRole('article', { name: /标注完成/ })
    const action = within(taskRow).getByRole('button', {
      name: /进入视频 .*配药动作标注工作台/,
    })
    expect(within(taskRow).queryByRole('button', { name: /重新核对视频/ })).not.toBeInTheDocument()
    await user.click(action)
    await waitFor(() =>
      expect(router.state.location.pathname).toBe(paths.annotationTask(projectId, taskId)),
    )
  })

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
    expect(await screen.findByText('待标注')).toBeInTheDocument()
    firstRender.unmount()

    renderView()
    await waitFor(() => {
      const taskRow = screen.getAllByRole('article')[0]
      expect(taskRow).toHaveTextContent('待复核')
    })
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

    expect(await screen.findByText('复核 · 已通过')).toBeInTheDocument()
    expect(screen.getByText('管理员自审')).toBeInTheDocument()
    expect(screen.getByText('清洗 · 当前规则检查通过')).toBeInTheDocument()
    const taskRow = screen.getByRole('article', { name: /标注完成/ })
    const user = userEvent.setup()
    await user.click(within(taskRow).getByText('查看详情'))
    expect(within(taskRow).getByText('执行人')).toBeInTheDocument()
    expect(within(taskRow).getByText('提交人')).toBeInTheDocument()
    expect(within(taskRow).getByText('复核人')).toBeInTheDocument()
    expect(within(taskRow).getAllByText('local-synthetic-admin')).toHaveLength(4)
    expect(taskRow).toHaveTextContent('（管理员自审）')
  })

  it('makes rejected revisions and their rework guidance explicit', async () => {
    const rejectedTask = {
      ...assignedTask,
      annotation_revision: {
        ...completedRevision,
        review: {
          ...completedRevision.review,
          decision: 'REJECTED',
          review_note: '请补充完整的拿取药品时间段。',
        },
        post_cleaning_result: null,
      },
    }
    stubTasks([rejectedTask], labelSchema)
    const { router } = renderView()
    const user = userEvent.setup()

    const taskRow = await screen.findByRole('article', { name: /待修改/ })
    expect(taskRow).toHaveTextContent('复核意见：请补充完整的拿取药品时间段。')
    await user.click(within(taskRow).getByRole('button', { name: /进入视频 .*配药动作标注工作台/ }))
    await waitFor(() =>
      expect(router.state.location.pathname).toBe(paths.annotationTask(projectId, taskId)),
    )
  })

  it('routes a reviewable submitted task to the workbench without direct review controls', async () => {
    const reviewableTask = {
      ...assignedTask,
      status: 'SUBMITTED',
      can_submit: false,
      can_review: true,
      annotation_revision: { ...completedRevision, review: null, post_cleaning_result: null },
    }
    stubTasks([reviewableTask], labelSchema)
    const { router } = renderView()
    const user = userEvent.setup()

    const taskRow = await screen.findByRole('article', { name: /待复核/ })
    expect(
      within(taskRow).getByRole('button', { name: /进入视频 .*配药动作标注工作台/ }),
    ).toBeInTheDocument()
    expect(within(taskRow).queryByRole('button', { name: /通过复核/ })).not.toBeInTheDocument()
    expect(within(taskRow).queryByRole('button', { name: /退回修改/ })).not.toBeInTheDocument()
    await user.click(within(taskRow).getByRole('button', { name: /进入视频 .*配药动作标注工作台/ }))
    await waitFor(() =>
      expect(router.state.location.pathname).toBe(paths.annotationTask(projectId, taskId)),
    )
  })

  it('loads a project export preview only after the administrator requests it', async () => {
    const preview = trainingDataPreview(12)
    const fetchMock = stubTrainingData([assignedTask], labelSchema, preview)
    const user = userEvent.setup()
    renderView()

    await user.click(await screen.findByText('更多项目操作'))
    await user.click(await screen.findByRole('button', { name: '导出训练数据' }))
    const dialog = await screen.findByRole('dialog')

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/training-data/preview`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
    expect(dialog).toHaveTextContent('training-data.v2')
    expect(dialog).toHaveTextContent('12 条')
    expect(dialog).toHaveTextContent('格式Parquet')
    expect(dialog).toHaveTextContent('包含手部关键点 + 人工动作标签')
    expect(dialog).toHaveTextContent('原始视频不包含')
    expect(dialog).toHaveTextContent(
      '手部检测结果仅供人工参考，不作为正式标注或质量结论。动作标签来自人工正式标注。',
    )
  })

  it('cancels the preview without requesting a download', async () => {
    const fetchMock = stubTrainingData([assignedTask], labelSchema, trainingDataPreview(3))
    const user = userEvent.setup()
    renderView()

    await user.click(await screen.findByText('更多项目操作'))
    await user.click(await screen.findByRole('button', { name: '导出训练数据' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: /取消/ }))

    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).endsWith('/training-data/download')),
    ).toHaveLength(0)
  })

  it('previews the V1.25 group manifest and shows its server-owned provenance boundaries', async () => {
    const preview = trainingDataV3Preview(2)
    const fetchMock = stubTrainingData(
      [assignedTask],
      labelSchema,
      trainingDataPreview(3),
      'PAR1 synthetic parquet',
      preview,
    )
    const user = userEvent.setup()
    renderView()

    await user.click(await screen.findByText('更多项目操作'))
    await user.click(await screen.findByRole('button', { name: '预览 training-data.v3' }))
    const dialog = await screen.findByRole('dialog')

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/training-data/v3/preview`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
    expect(dialog).toHaveTextContent('2 组')
    const productSummary = within(dialog).getByRole('region', {
      name: 'training-data.v3 产品摘要',
    })
    expect(productSummary).toHaveTextContent('参与机位')
    expect(productSummary).toHaveTextContent('随事件保留')
    expect(productSummary).toHaveTextContent('排除机位')
    expect(productSummary).toHaveTextContent('保留审计记录')
    expect(productSummary).toHaveTextContent('正式标注')
    expect(productSummary).toHaveTextContent('已通过人工复核')
    expect(productSummary).toHaveTextContent('训练资格')
    expect(productSummary).toHaveTextContent('已通过当前规则')
    const technicalDetails = within(dialog).getByText('查看技术与追溯信息').closest('details')
    if (!technicalDetails) throw new Error('V3 technical details were not mounted.')
    expect(technicalDetails).not.toHaveAttribute('open')
    await user.click(within(technicalDetails).getByText('查看技术与追溯信息'))
    expect(technicalDetails).toHaveAttribute('open')
    expect(technicalDetails).toHaveTextContent('2 个')
    expect(technicalDetails).toHaveTextContent('participating_views')
    expect(technicalDetails).toHaveTextContent('excluded_views')
    expect(technicalDetails).toHaveTextContent('annotation_revision_id + revision')
    expect(technicalDetails).toHaveTextContent(
      'capture_group_cleaning_id + rule set + input fingerprint + cleaning_evaluated_by；PASS',
    )
    expect(technicalDetails).toHaveTextContent(
      'primary_clip_asset_id + primary_camera_number + primary_interval；参考视图不生成动作对齐时间戳',
    )
    expect(technicalDetails).toHaveTextContent('training-data.v3')
    expect(technicalDetails).toHaveTextContent('application/vnd.medication.training-data.v3+json')
    expect(technicalDetails).toHaveTextContent(
      '配药事件（同一事件的机位不跨 train / validation / test）',
    )
    expect(technicalDetails).toHaveTextContent('training_data_v3.json')
    expect(technicalDetails).toHaveTextContent('不包含')
  })

  it('downloads the confirmed V1.25 manifest through the v3 endpoint', async () => {
    const fetchMock = stubTrainingData(
      [assignedTask],
      labelSchema,
      trainingDataPreview(3),
      '{"schema_version":"training-data.v3"}',
      trainingDataV3Preview(1),
    )
    const createObjectUrl = vi.fn(() => 'blob:training-data-v3')
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

    await user.click(await screen.findByText('更多项目操作'))
    await user.click(await screen.findByRole('button', { name: '预览 training-data.v3' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '导出 training-data.v3' }))

    await waitFor(() => expect(anchorClick).toHaveBeenCalledOnce())
    const link = anchorClick.mock.instances[0] as HTMLAnchorElement
    expect(link.download).toBe('training_data_v3.json')
    expect(link.href).toBe('blob:training-data-v3')
    expect(createObjectUrl).toHaveBeenCalledWith(expect.any(Blob))
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:training-data-v3')
    expect(document.body.contains(link)).toBe(false)
    expect(await screen.findByText('已开始下载 training_data_v3.json。')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/training-data/v3/download`,
      expect.objectContaining({
        method: 'GET',
        cache: 'no-store',
        headers: { Accept: 'application/vnd.medication.training-data.v3+json' },
      }),
    )
    anchorClick.mockRestore()
  })

  it('disables V1.25 export when the server preview has no eligible events', async () => {
    stubTrainingData(
      [assignedTask],
      labelSchema,
      trainingDataPreview(3),
      'PAR1 synthetic parquet',
      trainingDataV3Preview(0),
    )
    const user = userEvent.setup()
    renderView()

    await user.click(await screen.findByText('更多项目操作'))
    await user.click(await screen.findByRole('button', { name: '预览 training-data.v3' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('0 个')
    expect(dialog).toHaveTextContent('当前没有符合条件的配药事件，暂时无法导出。')
    expect(within(dialog).getByRole('button', { name: '导出 training-data.v3' })).toBeDisabled()
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

    await user.click(await screen.findByText('更多项目操作'))
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

    await user.click(await screen.findByText('更多项目操作'))
    await user.click(await screen.findByRole('button', { name: '导出训练数据' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('0 条')
    expect(dialog).toHaveTextContent('当前没有符合条件的训练行，暂时无法导出。')
    expect(within(dialog).getByRole('button', { name: /导出/ })).toBeDisabled()
  })

  it('does not show the export action for an assignee-scoped member queue', async () => {
    stubTasks([assignedTask], labelSchema, 'ASSIGNEE')
    renderView()

    expect(await screen.findByLabelText('我的配药动作标注进度')).toHaveTextContent('1 个任务')
    expect(screen.queryByText('更多项目操作')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '导出训练数据' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '预览 training-data.v3' })).not.toBeInTheDocument()
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

    await user.click(await screen.findByText('更多项目操作'))
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
  return renderRouter(
    [
      {
        path: '/projects/:projectId/annotation-tasks',
        element: <AnnotationTaskListView />,
      },
      {
        path: '/projects/:projectId/annotation-tasks/:taskId',
        element: <div aria-label="标注工作台路由占位" />,
      },
      {
        path: '/projects/:projectId/capture-groups/:captureGroupId',
        element: <div aria-label="CaptureGroup 路由占位" />,
      },
    ],
    [`/projects/${projectId}/annotation-tasks`],
  )
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
  v3Preview: object = trainingDataV3Preview(1),
) {
  const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.endsWith('/annotation-label-schema')) return Promise.resolve(jsonResponse(schema))
    if (url.endsWith('/training-data/v3/preview')) return Promise.resolve(jsonResponse(v3Preview))
    if (url.endsWith('/training-data/v3/download')) {
      return Promise.resolve({
        ok: true,
        blob: () => Promise.resolve(new Blob([downloadBody])),
      } as Response)
    }
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
function trainingDataV3Preview(eventCount: number) {
  return {
    schema_version: 'training-data.v3',
    eligible_group_count: eventCount,
    event_count: eventCount,
    file_name: 'training_data_v3.json',
    content_type: 'application/vnd.medication.training-data.v3+json',
    split_unit: 'capture_group',
    includes_mp4: false,
  }
}
function jsonResponse(body: object, init?: ResponseInit) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
}
