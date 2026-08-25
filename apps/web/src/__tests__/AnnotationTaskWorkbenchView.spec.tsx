import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'

import { paths } from '@/app/paths'
import { AppShell } from '@/components/layout/AppShell'
import { renderRoute } from '@/test/render'
import AnnotationTaskWorkbenchView from '@/views/AnnotationTaskWorkbenchView'

const projectId = '30000000-0000-4000-8000-000000000001'
const assetId = '50000000-0000-4000-8000-000000000001'
const taskId = 'a0000000-0000-4000-8000-000000000001'
const revisionId = '80000000-0000-4000-8000-000000000001'
const labelSchemaId = '70000000-0000-4000-8000-000000000001'
const labelSchemaDigest = 'b'.repeat(64)

const labelSchema = {
  label_schema_id: labelSchemaId,
  schema_key: 'medication-primary-step',
  schema_version: 'p0-primary-step.v1',
  schema_digest: labelSchemaDigest,
  layer: 'primary_step',
  labels: [
    {
      label_id: 'prepare_workspace',
      display_name: '准备工作区',
      description: '准备本次配制所需的工作区域。',
      sort_order: 10,
    },
    {
      label_id: 'retrieve_medicine',
      display_name: '拿取药品',
      description: '从工作区取得本次配制所需药品。',
      sort_order: 20,
    },
  ],
}

const detail = {
  clip_asset_id: assetId,
  batch_id: '60000000-0000-4000-8000-000000000001',
  source_upload_file_id: '90000000-0000-4000-8000-000000000001',
  client_file_id: 'approved-synthetic-hand.mp4',
  content_type: 'video/mp4',
  size_bytes: 1024,
  sha256: 'a'.repeat(64),
  status: 'INGESTED',
  session_binding: null,
  created_at: '2026-08-20T01:00:00Z',
  updated_at: '2026-08-20T01:10:00Z',
}

const candidateResponse = {
  job: {
    ai_job_id: '91000000-0000-4000-8000-000000000001',
    project_id: projectId,
    clip_asset_id: assetId,
    algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
    algorithm_version: 'v1',
    model_sha256: 'c'.repeat(64),
    input_sha256: detail.sha256,
    input_size_bytes: detail.size_bytes,
    sampling_fps: 5,
    profile_sha256: 'd'.repeat(64),
    status: 'SUCCEEDED',
    hand_result: 'HAND_DETECTED',
    run_summary: null,
    requested_by: 'system',
    created_at: '2026-08-20T01:12:00Z',
    updated_at: '2026-08-20T01:13:00Z',
  },
  candidates: [
    {
      frame_timestamp_ms: 0,
      image_width: 1280,
      image_height: 720,
      hand_index: 0,
      handedness: 'LEFT',
      handedness_score: 0.91,
      bounding_box: { x: 0.1, y: 0.2, width: 0.3, height: 0.4 },
      landmarks: Array.from({ length: 21 }, (_, index) => ({
        x: index / 21,
        y: index / 21,
        z: 0,
      })),
      valid_landmark_count: 21,
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
  created_at: '2026-08-20T01:00:00Z',
  updated_at: '2026-08-20T01:10:00Z',
  version: 0,
}

const submittedTask = {
  ...assignedTask,
  status: 'SUBMITTED',
  can_submit: false,
  can_review: true,
  annotation_revision: {
    annotation_revision_id: revisionId,
    label_schema_id: labelSchemaId,
    label_schema_digest: labelSchemaDigest,
    revision_number: 1,
    annotation_document: {
      schema_version: 'annotation-document.v1',
      segments: [{ start_ms: 100, end_ms: 800, label: 'prepare_workspace' }],
      note: '首版人工标注。',
    },
    submitted_by: 'local-synthetic-uploader',
    submitted_at: '2026-08-20T01:20:00Z',
    review: null,
    post_cleaning_result: null,
  },
}

const completedTask = {
  ...submittedTask,
  status: 'COMPLETED',
  can_review: false,
  annotation_revision: {
    ...submittedTask.annotation_revision,
    review: {
      annotation_review_id: '82000000-0000-4000-8000-000000000001',
      project_id: projectId,
      annotation_revision_id: revisionId,
      decision: 'APPROVED',
      review_note: null,
      reviewed_by: 'local-synthetic-admin',
      reviewed_at: '2026-08-20T01:30:00Z',
    },
  },
}

const recheckableTask = {
  ...completedTask,
  can_recheck_post_cleaning: true,
  annotation_revision: {
    ...completedTask.annotation_revision,
    post_cleaning_result: {
      cleaning_result_id: '85000000-0000-4000-8000-000000000001',
      cleaning_run_id: '86000000-0000-4000-8000-000000000001',
      rule_set_key: 'p0-post-annotation',
      rule_set_version: 'p0-post-annotation.v1',
      rule_set_digest: 'f'.repeat(64),
      outcome: 'WARNING',
      reason_codes: ['VERIFIED_MEDIA_DURATION_UNAVAILABLE'],
      executed_checks: [],
      evaluated_at: '2026-08-20T01:35:00Z',
    },
  },
}

afterEach(() => vi.unstubAllGlobals())

describe('annotation task workbench', () => {
  it('loads directly from the route and uses authoritative task state', async () => {
    const fetchMock = stubWorkbench([assignedTask])
    renderWorkbench()

    expect(await screen.findByRole('heading', { name: '配药动作标注工作台' })).toBeInTheDocument()
    expect(await screen.findByText('local-synthetic-uploader')).toBeInTheDocument()
    expect(screen.queryByText('approved-synthetic-hand.mp4')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-tasks`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${assetId}`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })

  it('keeps AppShell as the only main landmark', async () => {
    stubWorkbench([assignedTask])
    renderRoute(
      <AppShell sidebar={<aside aria-label="测试导航" />}>
        <AnnotationTaskWorkbenchView />
      </AppShell>,
      {
        route: paths.annotationTask(projectId, taskId),
        path: '/projects/:projectId/annotation-tasks/:taskId',
      },
    )

    expect(await screen.findByRole('heading', { name: '配药动作标注工作台' })).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: '提交标注' })).toBeInTheDocument()
    expect(screen.getAllByRole('main')).toHaveLength(1)
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content')
    expect(document.querySelector('.workbench-primary')).toHaveProperty('tagName', 'SECTION')
  })

  it('does not autoplay and uses the existing authorized playback path', async () => {
    const fetchMock = stubWorkbench([assignedTask])
    const user = userEvent.setup()
    renderWorkbench()

    expect(screen.queryByLabelText('视频预览')).not.toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: '播放视频' }))
    const video = await screen.findByLabelText('视频预览')
    expect(video).not.toHaveAttribute('autoplay')
    expect(video).toHaveAttribute('controls')
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${assetId}/playback-grants`,
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('keeps candidate overlay auxiliary and in the same fullscreen stage', async () => {
    stubWorkbench([assignedTask])
    const user = userEvent.setup()
    renderWorkbench()

    expect(await screen.findByText(/1 组手部关键点候选/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '播放视频' }))
    const video = await screen.findByLabelText('视频预览')
    const stage = video.closest('.video-stage')
    expect(stage?.querySelector('.hand-pose-overlay')).toBeInTheDocument()
    expect(screen.getByText(/仅作辅助，不是正式人工标注/)).toBeInTheDocument()
  })

  it('submits a valid human annotation and reloads server state', async () => {
    const fetchMock = stubWorkbench([assignedTask, submittedTask])
    const user = userEvent.setup()
    renderWorkbench()

    await user.click(await screen.findByRole('combobox', { name: '标注时间段 1 的标签' }))
    await user.click(
      await screen.findByText('准备工作区', { selector: '.ant-select-item-option-content' }),
    )
    await user.click(await screen.findByRole('button', { name: '提交标注' }))

    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([input, init]) =>
            String(input).endsWith(`/annotation-tasks/${taskId}/revisions`) &&
            init?.method === 'POST',
        ),
      ).toBe(true)
    })
    const revisionCall = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith(`/annotation-tasks/${taskId}/revisions`) && init?.method === 'POST',
    )
    expect(JSON.parse(String(revisionCall?.[1]?.body))).toEqual({
      segments: [{ start_ms: 0, end_ms: 1000, label: 'prepare_workspace' }],
    })
    expect(await screen.findByText('正式人工标注已提交，等待管理员复核。')).toBeInTheDocument()
  })

  it('blocks invalid ranges or missing labels before making a submit request', async () => {
    const fetchMock = stubWorkbench([assignedTask])
    const user = userEvent.setup()
    renderWorkbench()

    await user.click(await screen.findByRole('button', { name: '提交标注' }))
    expect(
      await screen.findByText('请填写标签，并确保每个时间段的结束时间晚于开始时间。'),
    ).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) =>
          String(input).endsWith(`/annotation-tasks/${taskId}/revisions`) &&
          init?.method === 'POST',
      ),
    ).toBe(false)
  })

  it('shows rejected review guidance and starts rework from the previous segments', async () => {
    const rejectedTask = {
      ...assignedTask,
      annotation_revision: {
        ...submittedTask.annotation_revision,
        review: {
          annotation_review_id: '83000000-0000-4000-8000-000000000001',
          project_id: projectId,
          annotation_revision_id: revisionId,
          decision: 'REJECTED',
          review_note: '请补充完整的拿取药品时间段。',
          reviewed_by: 'local-synthetic-admin',
          reviewed_at: '2026-08-20T01:30:00Z',
        },
      },
    }
    stubWorkbench([rejectedTask])
    renderWorkbench()

    expect((await screen.findAllByText('请补充完整的拿取药品时间段。')).length).toBe(2)
    expect(screen.getByRole('spinbutton', { name: '标注时间段 1 的开始时间' })).toHaveValue('100')
    expect(screen.getByRole('spinbutton', { name: '标注时间段 1 的结束时间' })).toHaveValue('800')
  })

  it('lets an administrator approve a submitted revision in the workbench', async () => {
    const fetchMock = stubWorkbench([submittedTask, completedTask])
    const user = userEvent.setup()
    renderWorkbench()

    await user.click(await screen.findByRole('button', { name: '通过复核' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '通过复核' }))

    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([input, init]) =>
            String(input).endsWith(`/annotation-revisions/${revisionId}/review`) &&
            init?.method === 'POST',
        ),
      ).toBe(true)
    })
    const reviewCall = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith(`/annotation-revisions/${revisionId}/review`) &&
        init?.method === 'POST',
    )
    expect(JSON.parse(String(reviewCall?.[1]?.body))).toEqual({ decision: 'APPROVED' })
  })

  it('lets an administrator reject with an optional review note', async () => {
    const fetchMock = stubWorkbench([submittedTask, assignedTask])
    const user = userEvent.setup()
    renderWorkbench()

    await user.click(await screen.findByRole('button', { name: '退回修改' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(
      within(dialog).getByRole('textbox', { name: '退回修改说明（可选）' }),
      '请补充动作结束位置。',
    )
    await user.click(within(dialog).getByRole('button', { name: '退回修改' }))

    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([input, init]) =>
            String(input).endsWith(`/annotation-revisions/${revisionId}/review`) &&
            init?.method === 'POST',
        ),
      ).toBe(true)
    })
    const reviewCall = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith(`/annotation-revisions/${revisionId}/review`) &&
        init?.method === 'POST',
    )
    expect(JSON.parse(String(reviewCall?.[1]?.body))).toEqual({
      decision: 'REJECTED',
      review_note: '请补充动作结束位置。',
    })
  })

  it('keeps post-cleaning recheck available when the server authorizes it', async () => {
    const fetchMock = stubWorkbench([recheckableTask])
    const user = userEvent.setup()
    renderWorkbench()

    await user.click(await screen.findByRole('button', { name: '重新核对清洗' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('历史结果会保留')
    await user.click(within(dialog).getByRole('button', { name: '重新核对' }))

    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([input, init]) =>
            String(input).endsWith('/post-cleaning-rechecks') && init?.method === 'POST',
        ),
      ).toBe(true)
    })
  })

  it('hides submit and review actions when the server denies them', async () => {
    const deniedTask = { ...assignedTask, can_submit: false }
    stubWorkbench([deniedTask])
    renderWorkbench()

    expect(await screen.findByText(/当前账号不能提交这个任务/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '提交标注' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '通过复核' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '退回修改' })).not.toBeInTheDocument()
  })

  it('resets a local draft after an authoritative refresh and keeps the narrow layout scoped', async () => {
    const fetchMock = stubWorkbench([assignedTask, assignedTask])
    const user = userEvent.setup()
    renderWorkbench()

    const startInput = await screen.findByRole('spinbutton', { name: '标注时间段 1 的开始时间' })
    fireEvent.change(startInput, { target: { value: '250' } })
    await user.click(screen.getByRole('button', { name: '刷新' }))
    await waitFor(() =>
      expect(screen.getByRole('spinbutton', { name: '标注时间段 1 的开始时间' })).toHaveValue('0'),
    )
    expect(document.querySelector('.annotation-workbench-layout')).toHaveClass('workbench-grid')
    expect(
      fetchMock.mock.calls.filter(
        ([input, init]) => String(input).endsWith('/annotation-tasks') && init?.method === 'GET',
      ).length,
    ).toBeGreaterThanOrEqual(2)
  })
})

function renderWorkbench() {
  return renderRoute(<AnnotationTaskWorkbenchView />, {
    route: paths.annotationTask(projectId, taskId),
    path: '/projects/:projectId/annotation-tasks/:taskId',
  })
}

function stubWorkbench(taskResponses: object[], candidate = candidateResponse) {
  let taskResponseIndex = 0
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (url.endsWith('/annotation-label-schema')) return Promise.resolve(jsonResponse(labelSchema))
    if (url.endsWith('/annotation-tasks') && method === 'GET') {
      const tasks =
        taskResponses[Math.min(taskResponseIndex++, taskResponses.length - 1)] ??
        taskResponses[0] ??
        {}
      return Promise.resolve(jsonResponse(taskList(tasks)))
    }
    if (url.endsWith(`/clip-assets/${assetId}`)) return Promise.resolve(jsonResponse(detail))
    if (url.endsWith(`/hand-pose-candidates`)) return Promise.resolve(jsonResponse(candidate))
    if (url.endsWith(`/clip-assets/${assetId}/playback-grants`) && method === 'POST') {
      return Promise.resolve(
        jsonResponse({
          grant_id: `grant-${fetchMock.mock.calls.length}`,
          purpose: 'DATA_REVIEW',
          media_url: '/synthetic/authorized-hand.mp4',
          expires_at: new Date(Date.now() + 5_000).toISOString(),
          content_type: 'video/mp4',
        }),
      )
    }
    if (url.endsWith(`/annotation-tasks/${taskId}/revisions`) && method === 'POST') {
      return Promise.resolve(
        jsonResponse({
          annotation_revision_id: revisionId,
          project_id: projectId,
          annotation_task_id: taskId,
          clip_asset_id: assetId,
          label_schema_id: labelSchemaId,
          label_schema_digest: labelSchemaDigest,
          revision_number: 1,
          annotation_schema_version: 'annotation-document.v1',
          document_sha256: 'e'.repeat(64),
          submitted_by: 'local-synthetic-uploader',
          submitted_at: '2026-08-20T01:20:00Z',
        }),
      )
    }
    if (url.endsWith(`/annotation-revisions/${revisionId}/review`) && method === 'POST') {
      return Promise.resolve(
        jsonResponse({
          annotation_review_id: '84000000-0000-4000-8000-000000000001',
          project_id: projectId,
          annotation_revision_id: revisionId,
          decision: JSON.parse(String(init?.body)).decision,
          review_note: JSON.parse(String(init?.body)).review_note ?? null,
          reviewed_by: 'local-synthetic-admin',
          reviewed_at: '2026-08-20T01:30:00Z',
        }),
      )
    }
    if (url.endsWith('/post-cleaning-rechecks') && method === 'POST') {
      return Promise.resolve(
        jsonResponse({
          cleaning_result_id: '85000000-0000-4000-8000-000000000001',
          cleaning_run_id: '86000000-0000-4000-8000-000000000001',
          rule_set_key: 'p0-post-annotation',
          rule_set_version: 'p0-post-annotation.v1',
          rule_set_digest: 'f'.repeat(64),
          outcome: 'PASS',
          reason_codes: [],
          executed_checks: [],
          evaluated_at: '2026-08-20T01:35:00Z',
        }),
      )
    }
    return Promise.reject(new Error(`Unexpected fetch: ${method} ${url}`))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function taskList(task: object) {
  const typedTask = task as { status?: string }
  return {
    items: [task],
    progress: {
      scope: 'PROJECT',
      total: 1,
      awaiting_submission: typedTask.status === 'ASSIGNED' ? 1 : 0,
      rework_required: 0,
      awaiting_review: typedTask.status === 'SUBMITTED' ? 1 : 0,
      completed: typedTask.status === 'COMPLETED' ? 1 : 0,
    },
  }
}

function jsonResponse(body: object, init?: ResponseInit) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
}
