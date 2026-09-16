import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { PlatformSession } from '@/api/auth'
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
    segments: [{ start_ms: 0, end_ms: 1000, label: 'acquire_item' }],
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
  capture_group_cleaning: {
    capture_group_cleaning_id: '82000000-0000-4000-8000-000000000001',
    outcome: 'PASS',
    reason_codes: [],
    evaluated_at: '2026-07-26T01:10:00Z',
    participating_views: [],
    excluded_views: [],
  },
}
const labelSchema = {
  label_schema_id: labelSchemaId,
  schema_key: 'medication-primary-step',
  schema_version: 'p0-atomic-action.v4',
  schema_digest: labelSchemaDigest,
  layer: 'primary_step',
  labels: [
    {
      label_id: 'acquire_item',
      display_name: '拿取容器/工具',
      description:
        '手明确到达并取得将参与配药的容器或工具，物体离开原稳定位置并进入操作区；不标持续持有、调整位置或重复抓握',
      sort_order: 1,
    },
    {
      label_id: 'open_container',
      display_name: '开启容器',
      description:
        '明确打开药品主容器，如掰开安瓿或移除西林瓶/输液容器的保护部件；不包括拆注射器包装',
      sort_order: 2,
    },
    {
      label_id: 'disinfect_access',
      display_name: '消毒容器接口',
      description: '看到棉签或其他消毒用品实际擦拭瓶塞、安瓿颈或容器接口；仅出现用品不构成该动作',
      sort_order: 3,
    },
    {
      label_id: 'insert_needle',
      display_name: '针头进入容器',
      description: '清楚看到针头穿过瓶塞或接口并进入目标药品容器；仅靠近或被遮挡时不标注',
      sort_order: 4,
    },
    {
      label_id: 'draw_liquid',
      display_name: '抽取药液',
      description: '针头已进入容器且活塞向后移动，能看到药液进入注射器；仅进针没有抽液证据时不标注',
      sort_order: 5,
    },
    {
      label_id: 'transfer_liquid',
      display_name: '转移药液',
      description:
        '使用注射器把药液从一个容器推入另一个目标容器；要求能确认来源、目标和推注方向，不表示面向患者给药',
      sort_order: 6,
    },
    {
      label_id: 'agitate_solution',
      display_name: '混匀药液',
      description: '明确摇晃、翻转或振荡装有药液的容器以混匀；仅拿着或移动容器不标注',
      sort_order: 7,
    },
    {
      label_id: 'discard_sharps',
      display_name: '处置锐器',
      description: '明确把针头、安瓿或其他锐器放入锐器盒；仅出现锐器盒或丢弃普通耗材不标注',
      sort_order: 8,
    },
    {
      label_id: 'label_prepared_medication',
      display_name: '给成品贴签',
      description: '看到标签实际粘贴到注射器、药瓶或成品输液容器；仅查看、拿着标签或成品包装不标注',
      sort_order: 9,
    },
  ],
}
const assignedTask = {
  annotation_task_id: taskId,
  project_id: projectId,
  clip_asset_id: null,
  capture_group_id: captureGroupId,
  primary_clip_asset_id: assetId,
  primary_camera_number: 1,
  label_schema_id: labelSchemaId,
  label_schema_digest: labelSchemaDigest,
  assignee_actor_id: 'local-synthetic-uploader',
  created_by: 'local-synthetic-admin',
  status: 'ASSIGNED',
  can_submit: true,
  can_select_primary: false,
  can_review: false,
  can_recheck_capture_group_cleaning: false,
  annotation_revision: null,
  created_at: '2026-07-23T01:00:00Z',
  updated_at: '2026-07-23T01:00:00Z',
  version: 0,
}

const assignedGroupTask = {
  ...assignedTask,
  annotation_task_id: 'a0000000-0000-4000-8000-000000000010',
  clip_asset_id: null,
  can_select_primary: true,
}

afterEach(() => vi.unstubAllGlobals())

describe('annotation task list view', () => {
  it('loads the task queue and keeps annotation navigation as a top-level context', async () => {
    const fetchMock = stubTasks([assignedTask], labelSchema)
    const user = userEvent.setup()
    renderView()
    expect(await screen.findByText('尚未提交')).toBeInTheDocument()
    const progressSummary = await screen.findByLabelText('项目标注任务进度')
    expect(progressSummary).toHaveTextContent('1 个任务')
    expect(progressSummary).toHaveTextContent(/0 个已完成.*1 个待处理/)
    expect(screen.getByText('待标注')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /继续标注/ })).toBeInTheDocument()
    expect(screen.queryByText(/唯一采集记录任务/)).not.toBeInTheDocument()
    expect(
      screen.queryByText('查看当前项目的标注任务；提交后由有权限的人员复核。'),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '预览手部关键点导出' })).not.toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: '面包屑导航' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '任务队列' })).toBeInTheDocument()
    await user.click(screen.getByText('查看详情'))
    expect(screen.getByText('local-synthetic-uploader')).toBeInTheDocument()
    expect(screen.getByText(/a0000000.*0001/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /继续标注/ })).toBeInTheDocument()
    expect(screen.queryByText('更多项目操作')).not.toBeInTheDocument()
    expect(
      within(screen.getByRole('listitem', { name: /采集记录 .*待标注/ })).getByText(
        /^采集记录 60000000.*0001$/,
      ),
    ).toBeInTheDocument()
    expect(
      within(screen.getByRole('listitem', { name: /采集记录 .*待标注/ })).getAllByRole('link'),
    ).toHaveLength(1)
    expect(screen.getByRole('list', { name: '标注任务列表' })).toHaveClass(
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

    const emptyState = await screen.findByText('当前没有需要处理的标注任务。')
    expect(emptyState).toBeInTheDocument()
    expect(emptyState).not.toHaveTextContent(/管理员|创建并分配/)
  })

  it('keeps the compact summary shape when the queue is complete', async () => {
    const completedTask = {
      ...assignedTask,
      status: 'COMPLETED',
      can_submit: false,
      annotation_revision: completedRevision,
    }
    stubTasks([completedTask], labelSchema)
    renderView()

    const progressSummary = await screen.findByLabelText('项目标注任务进度')
    expect(progressSummary).toHaveTextContent('1 个任务')
    expect(progressSummary).toHaveTextContent(/1 个已完成.*0 个待处理/)
  })

  it('routes the only primary action for an assigned task into the CaptureGroup workbench', async () => {
    const fetchMock = stubTasks([assignedTask], labelSchema)
    const { router } = renderView()
    const user = userEvent.setup()
    const action = await screen.findByRole('link', {
      name: /继续标注/,
    })
    expect(action).toBeInTheDocument()
    await user.click(action)
    await waitFor(() =>
      expect(router.state.location.pathname).toBe(paths.captureGroup(projectId, captureGroupId)),
    )
    expect(router.state.location.search).toBe('?focus=annotation')
    expect(fetchMock).not.toHaveBeenCalledWith(
      expect.stringContaining('/revisions'),
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('routes an assigned CaptureGroup task to event annotation', async () => {
    stubTasks([assignedGroupTask], labelSchema)
    const { router } = renderView()
    const user = userEvent.setup()

    const taskRow = await screen.findByRole('listitem', { name: /采集记录 .*待标注/ })
    await user.click(within(taskRow).getByRole('link', { name: /继续标注/ }))

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(paths.captureGroup(projectId, captureGroupId))
      expect(router.state.location.search).toBe('?focus=annotation')
    })
    expect(screen.queryByRole('button', { name: /配药动作标注工作台/ })).not.toBeInTheDocument()
  })

  it('routes to task destination when clicking on the task card row directly', async () => {
    stubTasks([assignedGroupTask], labelSchema)
    const { router } = renderView()
    const user = userEvent.setup()

    const taskRow = await screen.findByRole('listitem', { name: /采集记录 .*待标注/ })
    await user.click(within(taskRow).getByText(/采集记录/))

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(paths.captureGroup(projectId, captureGroupId))
      expect(router.state.location.search).toBe('?focus=annotation')
    })
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

    const taskRow = await screen.findByRole('listitem', { name: /采集记录 .*待标注/ })
    await user.click(within(taskRow).getByRole('link', { name: /选择主视图/ }))

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
      annotation_revision: { ...completedRevision, review: null, capture_group_cleaning: null },
    }
    stubTasks([reviewableGroupTask], labelSchema)
    const { router } = renderView()
    const user = userEvent.setup()

    const taskRow = await screen.findByRole('listitem', { name: /采集记录 .*待复核/ })
    await user.click(within(taskRow).getByRole('link', { name: /复核标注/ }))

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
        capture_group_cleaning: null,
      },
    }
    stubTasks([returnedGroupTask], labelSchema)
    const { router } = renderView()
    const user = userEvent.setup()

    const taskRow = await screen.findByRole('listitem', { name: /采集记录 .*待返工/ })
    await user.click(within(taskRow).getByRole('link', { name: /修改标注/ }))

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

    const taskRow = await screen.findByRole('listitem', { name: /采集记录 .*已完成/ })
    expect(within(taskRow).queryByRole('button')).not.toBeInTheDocument()
    await user.click(within(taskRow).getByRole('link', { name: /查看标注结果/ }))

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(paths.captureGroup(projectId, captureGroupId))
      expect(router.state.location.search).toBe('?focus=review')
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

    expect(await screen.findByText('复核已通过 · 标注检查通过')).toBeInTheDocument()
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

    const taskRow = await screen.findByRole('listitem', { name: /采集记录 .*待标注/ })
    expect(within(taskRow).queryByRole('link', { name: /进入视频/ })).not.toBeInTheDocument()
    expect(within(taskRow).getByText(/^采集记录 60000000.*0001$/)).toBeInTheDocument()
    expect(within(taskRow).queryByRole('link')).not.toBeInTheDocument()
  })

  it('shows the completed state and each CaptureGroup cleaning outcome without adding another page', async () => {
    const outcomes = [
      { outcome: 'PASS', expected: '复核已通过 · 标注检查通过' },
      { outcome: 'WARNING', expected: '复核已通过 · 标注检查需处理' },
      { outcome: 'REJECT', expected: '复核已通过 · 标注检查未通过' },
      { outcome: null, expected: '复核已通过 · 标注检查待处理' },
    ] as const
    const tasks = outcomes.map(({ outcome }, index) => ({
      ...assignedTask,
      annotation_task_id: `a0000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
      status: 'COMPLETED',
      can_submit: false,
      can_recheck_capture_group_cleaning: true,
      annotation_revision: {
        ...completedRevision,
        annotation_revision_id: `80000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
        capture_group_cleaning:
          outcome === null
            ? null
            : {
                ...completedRevision.capture_group_cleaning,
                outcome,
                reason_codes:
                  outcome === 'WARNING'
                    ? ['PRIMARY_INTERVAL_MISSING', 'FUTURE_RULE_CHECK']
                    : outcome === 'REJECT'
                      ? ['PRIMARY_INTERVAL_OVERLAP']
                      : [],
              },
      },
    }))
    stubTasks(tasks, labelSchema)
    const user = userEvent.setup()
    renderView()
    expect(await screen.findByText('复核已通过 · 标注检查通过')).toBeInTheDocument()
    for (const { expected } of outcomes) expect(screen.getByText(expected)).toBeInTheDocument()
    const completedRow = screen.getAllByRole('listitem')[0]!
    const annotationDetails = within(completedRow).getByText('查看详情')
    await user.click(annotationDetails)
    expect(within(completedRow).getByText('00:00 → 00:01')).toBeInTheDocument()
    expect(within(completedRow).getByText('原始值：0–1000 毫秒')).toBeInTheDocument()
    expect(within(completedRow).getByText('拿取容器/工具')).toBeInTheDocument()
    const evidenceDetails = screen.getAllByText('查看详情')
    expect(evidenceDetails).toHaveLength(4)
    await user.click(evidenceDetails[1]!)
    expect(evidenceDetails[1]?.closest('details')).toHaveAttribute('open')
    expect(within(completedRow).getByText('提交人')).toBeInTheDocument()
    expect(within(completedRow).getByText('复核人')).toBeInTheDocument()
    expect(await screen.findByText('标注检查需处理，请补充事实后重试。')).toBeInTheDocument()
    expect(screen.getByText(/缺少主视图正式时间段/)).toBeInTheDocument()
    expect(screen.getByText(/其他标注检查原因（FUTURE_RULE_CHECK）/)).toBeInTheDocument()
    await user.click(evidenceDetails[2]!)
    expect(await screen.findByText(/标注检查未通过；视频未删除/)).toBeInTheDocument()
    expect(screen.getByText(/主视图正式时间段存在重叠/)).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: /查看标注结果/ })).toHaveLength(4)
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
      can_recheck_capture_group_cleaning: true,
      annotation_revision: {
        ...completedRevision,
        capture_group_cleaning: {
          ...completedRevision.capture_group_cleaning,
          outcome: 'WARNING',
          reason_codes: ['PRIMARY_INTERVAL_MISSING'],
        },
      },
    }
    stubTasks([completedTask], labelSchema)
    const user = userEvent.setup()
    const { router } = renderView()

    const taskRow = await screen.findByRole('listitem', { name: /已完成/ })
    const action = within(taskRow).getByRole('link', { name: /查看标注结果/ })
    expect(within(taskRow).queryByRole('button', { name: /重新核对视频/ })).not.toBeInTheDocument()
    await user.click(action)
    await waitFor(() =>
      expect(router.state.location.pathname).toBe(paths.captureGroup(projectId, captureGroupId)),
    )
    expect(router.state.location.search).toBe('?focus=review')
  })

  it('reloads the current task state from the server when the page is re-entered', async () => {
    const submittedTask = {
      ...assignedTask,
      status: 'SUBMITTED',
      can_submit: false,
      annotation_revision: { ...completedRevision, review: null, capture_group_cleaning: null },
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
      const taskRow = screen.getAllByRole('listitem')[0]
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
          can_recheck_capture_group_cleaning: true,
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

    expect(await screen.findByText('复核已通过 · 标注检查通过')).toBeInTheDocument()
    expect(screen.getByText('管理员自审')).toBeInTheDocument()
    const taskRow = screen.getByRole('listitem', { name: /已完成/ })
    expect(within(taskRow).getByText('复核已通过 · 标注检查通过')).toBeInTheDocument()
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
          review_note: '请补充完整的拿取容器/工具时间段。',
        },
        capture_group_cleaning: null,
      },
    }
    stubTasks([rejectedTask], labelSchema)
    const { router } = renderView()
    const user = userEvent.setup()

    const taskRow = await screen.findByRole('listitem', { name: /待返工/ })
    expect(taskRow).toHaveTextContent('复核未通过')
    expect(screen.queryByText('请补充完整的拿取容器/工具时间段。')).not.toBeVisible()
    await user.click(within(taskRow).getByText('查看详情'))
    expect(screen.getByText('请补充完整的拿取容器/工具时间段。')).toBeVisible()
    await user.click(within(taskRow).getByRole('link', { name: /修改标注/ }))
    await waitFor(() =>
      expect(router.state.location.pathname).toBe(paths.captureGroup(projectId, captureGroupId)),
    )
    expect(router.state.location.search).toBe('?focus=annotation')
  })

  it('routes a reviewable submitted task to the workbench without direct review controls', async () => {
    const reviewableTask = {
      ...assignedTask,
      status: 'SUBMITTED',
      can_submit: false,
      can_review: true,
      annotation_revision: { ...completedRevision, review: null, capture_group_cleaning: null },
    }
    stubTasks([reviewableTask], labelSchema)
    const { router } = renderView()
    const user = userEvent.setup()

    const taskRow = await screen.findByRole('listitem', { name: /待复核/ })
    expect(within(taskRow).getByRole('link', { name: /复核标注/ })).toBeInTheDocument()
    expect(within(taskRow).queryByRole('button', { name: /通过复核/ })).not.toBeInTheDocument()
    expect(within(taskRow).queryByRole('button', { name: /退回修改/ })).not.toBeInTheDocument()
    await user.click(within(taskRow).getByRole('link', { name: /复核标注/ }))
    await waitFor(() =>
      expect(router.state.location.pathname).toBe(paths.captureGroup(projectId, captureGroupId)),
    )
    expect(router.state.location.search).toBe('?focus=review')
  })

  it('does not show the export action for an assignee-scoped member queue', async () => {
    stubTasks([assignedTask], labelSchema, 'ASSIGNEE')
    renderView()

    expect(await screen.findByLabelText('我的标注任务进度')).toHaveTextContent('1 个任务')
    expect(screen.queryByText('更多项目操作')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '预览手部关键点导出' })).not.toBeInTheDocument()
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

function renderView(session: PlatformSession | null = null) {
  return renderRouter(
    [
      {
        path: '/projects/:projectId/annotation-tasks',
        element: <AnnotationTaskListView />,
      },
      {
        path: '/projects/:projectId/capture-groups/:captureGroupId',
        element: <div aria-label="CaptureGroup 路由占位" />,
      },
    ],
    [`/projects/${projectId}/annotation-tasks`],
    session,
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
function jsonResponse(body: object, init?: ResponseInit) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
}
