import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  createAnnotationTask,
  getAnnotationLabelSchema,
  listAnnotationTasks,
  recheckCaptureGroupCleaning,
  recheckAnnotationPostCleaning,
  reviewAnnotationRevision,
  submitAnnotationRevision,
} from '@/api/annotation'

const projectId = '30000000-0000-4000-8000-000000000001'
const assetId = '50000000-0000-4000-8000-000000000001'
const labelSchemaId = '70000000-0000-4000-8000-000000000001'
const labelSchemaDigest = 'b'.repeat(64)
const emptyProgress = {
  scope: 'ASSIGNEE',
  total: 0,
  awaiting_submission: 0,
  rework_required: 0,
  awaiting_review: 0,
  completed: 0,
} as const

afterEach(() => vi.unstubAllGlobals())

describe('annotation task API', () => {
  it('creates an assigned task with an idempotency key and CSRF header', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          {
            annotation_task_id: 'a0000000-0000-4000-8000-000000000001',
            project_id: projectId,
            clip_asset_id: assetId,
            label_schema_id: labelSchemaId,
            label_schema_digest: labelSchemaDigest,
            assignee_actor_id: 'local-synthetic-uploader',
            created_by: 'local-synthetic-admin',
            status: 'ASSIGNED',
            created_at: '2026-07-23T01:00:00Z',
            updated_at: '2026-07-23T01:00:00Z',
            version: 0,
          },
          { status: 201 },
        ),
      ),
    )

    const task = await createAnnotationTask(projectId, assetId, 'local-synthetic-uploader')

    expect(task.status).toBe('ASSIGNED')
    expect(fetch).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${assetId}/annotation-tasks`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ assignee_actor_id: 'local-synthetic-uploader' }),
        headers: expect.objectContaining({ 'Idempotency-Key': expect.any(String) }),
      }),
    )
  })

  it('lists the visible task queue and explains a qualified-state rejection', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ items: [], progress: emptyProgress }))
      .mockResolvedValueOnce(
        jsonResponse({ error_code: 'ANNOTATION_TASK_QUALITY_NOT_QUALIFIED' }, { status: 409 }),
      )
    vi.stubGlobal('fetch', fetchMock)

    await expect(listAnnotationTasks(projectId)).resolves.toEqual({
      items: [],
      progress: emptyProgress,
    })
    await expect(
      createAnnotationTask(projectId, assetId, 'local-synthetic-uploader'),
    ).rejects.toMatchObject({
      errorCode: 'ANNOTATION_TASK_QUALITY_NOT_QUALIFIED',
      message: '只有质量结论为“合格”的视频才能创建配药动作标注。',
    })
  })

  it('keeps the completed task and immutable post-cleaning result in the list response', async () => {
    const cleaningResult = {
      cleaning_result_id: '82000000-0000-4000-8000-000000000001',
      cleaning_run_id: '83000000-0000-4000-8000-000000000001',
      rule_set_key: 'p0-post-annotation',
      rule_set_version: 'p0-post-annotation.v1',
      rule_set_digest: 'd'.repeat(64),
      outcome: 'PASS',
      reason_codes: [],
      executed_checks: ['PRIMARY_STEP_LABEL_ALLOWED', 'PRIMARY_STEP_NO_OVERLAP'],
      evaluated_at: '2026-07-26T01:10:00Z',
    }
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          progress: {
            ...emptyProgress,
            scope: 'PROJECT',
            total: 1,
            completed: 1,
          },
          items: [
            {
              annotation_task_id: 'a0000000-0000-4000-8000-000000000001',
              project_id: projectId,
              clip_asset_id: assetId,
              label_schema_id: labelSchemaId,
              label_schema_digest: labelSchemaDigest,
              assignee_actor_id: 'local-synthetic-uploader',
              created_by: 'local-synthetic-admin',
              status: 'COMPLETED',
              can_submit: false,
              can_review: false,
              can_recheck_post_cleaning: true,
              annotation_revision: {
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
                post_cleaning_result: cleaningResult,
              },
              created_at: '2026-07-26T00:00:00Z',
              updated_at: '2026-07-26T01:10:00Z',
              version: 2,
            },
          ],
        }),
      ),
    )

    await expect(listAnnotationTasks(projectId)).resolves.toMatchObject({
      progress: { scope: 'PROJECT', total: 1, completed: 1 },
      items: [
        {
          status: 'COMPLETED',
          annotation_revision: { post_cleaning_result: cleaningResult },
        },
      ],
    })
  })

  it('loads the project primary-step label schema without caching', async () => {
    const signal = new AbortController().signal
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
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
        ],
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(getAnnotationLabelSchema(projectId, signal)).resolves.toMatchObject({
      label_schema_id: labelSchemaId,
      layer: 'primary_step',
      labels: [{ label_id: 'retrieve_medicine', display_name: '拿取药品' }],
    })
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-label-schema`,
      expect.objectContaining({ method: 'GET', cache: 'no-store', signal }),
    )
  })

  it('submits an immutable time-range annotation revision with a fresh idempotency key', async () => {
    const taskId = '30000000-0000-4000-8000-000000000003'
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        annotation_revision_id: '30000000-0000-4000-8000-000000000004',
        project_id: projectId,
        annotation_task_id: taskId,
        clip_asset_id: assetId,
        label_schema_id: labelSchemaId,
        label_schema_digest: labelSchemaDigest,
        revision_number: 1,
        annotation_schema_version: 'annotation-document.v1',
        document_sha256: 'a'.repeat(64),
        submitted_by: 'local-synthetic-uploader',
        submitted_at: '2026-07-24T00:00:00Z',
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn().mockReturnValue('annotation-revision-idempotency-0001'),
    })

    await expect(
      submitAnnotationRevision(
        projectId,
        taskId,
        [{ start_ms: 100, end_ms: 900, label: 'retrieve_medicine' }],
        '合成验证',
      ),
    ).resolves.toMatchObject({ annotation_task_id: taskId, revision_number: 1 })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-tasks/${taskId}/revisions`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          segments: [{ start_ms: 100, end_ms: 900, label: 'retrieve_medicine' }],
          note: '合成验证',
        }),
        headers: expect.objectContaining({
          'Idempotency-Key': 'annotation-revision-idempotency-0001',
        }),
      }),
    )
  })

  it('records an administrator review with a fresh idempotency key', async () => {
    const revisionId = '30000000-0000-4000-8000-000000000004'
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        annotation_review_id: '30000000-0000-4000-8000-000000000005',
        project_id: projectId,
        annotation_revision_id: revisionId,
        decision: 'APPROVED',
        review_note: null,
        reviewed_by: 'local-synthetic-admin',
        reviewed_at: '2026-07-24T00:00:00Z',
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn().mockReturnValue('annotation-review-idempotency-0001'),
    })

    await expect(
      reviewAnnotationRevision(projectId, revisionId, 'APPROVED'),
    ).resolves.toMatchObject({
      annotation_revision_id: revisionId,
      decision: 'APPROVED',
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-revisions/${revisionId}/review`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ decision: 'APPROVED' }),
        headers: expect.objectContaining({
          'Idempotency-Key': 'annotation-review-idempotency-0001',
        }),
      }),
    )
  })

  it('includes an optional review note when returning a revision for rework', async () => {
    const revisionId = '30000000-0000-4000-8000-000000000004'
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        annotation_review_id: '30000000-0000-4000-8000-000000000005',
        project_id: projectId,
        annotation_revision_id: revisionId,
        decision: 'REJECTED',
        review_note: '请补充时间段。',
        reviewed_by: 'local-synthetic-admin',
        reviewed_at: '2026-07-24T00:00:00Z',
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn().mockReturnValue('annotation-review-idempotency-0002'),
    })

    await expect(
      reviewAnnotationRevision(projectId, revisionId, 'REJECTED', '  请补充时间段。  '),
    ).resolves.toMatchObject({
      annotation_revision_id: revisionId,
      decision: 'REJECTED',
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-revisions/${revisionId}/review`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ decision: 'REJECTED', review_note: '请补充时间段。' }),
      }),
    )
  })

  it('rechecks post-cleaning with the current facts without a browser-side idempotency model', async () => {
    const revisionId = '30000000-0000-4000-8000-000000000004'
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        cleaning_result_id: '82000000-0000-4000-8000-000000000001',
        cleaning_run_id: '83000000-0000-4000-8000-000000000001',
        rule_set_key: 'p0-post-annotation',
        rule_set_version: 'p0-post-annotation.v1',
        rule_set_digest: 'd'.repeat(64),
        outcome: 'PASS',
        reason_codes: [],
        executed_checks: ['PRIMARY_STEP_NO_OVERLAP'],
        evaluated_at: '2026-07-27T01:00:00Z',
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(recheckAnnotationPostCleaning(projectId, revisionId)).resolves.toMatchObject({
      outcome: 'PASS',
    })
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-revisions/${revisionId}/post-cleaning-rechecks`,
      expect.objectContaining({ method: 'POST', cache: 'no-store' }),
    )
  })

  it('rechecks CaptureGroup training eligibility through the existing server operation', async () => {
    const revisionId = '30000000-0000-4000-8000-000000000004'
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        capture_group_cleaning_id: '82000000-0000-4000-8000-000000000002',
        project_id: projectId,
        capture_group_id: '60000000-0000-4000-8000-000000000001',
        annotation_revision_id: revisionId,
        annotation_review_id: '83000000-0000-4000-8000-000000000002',
        annotation_review_decision: 'APPROVED',
        group_quality_result: 'QUALIFIED',
        group_quality_revision: 1,
        primary_clip_asset_id: assetId,
        primary_camera_number: 1,
        primary_interval: [{ start_ms: 100, end_ms: 900, label: 'PREPARE' }],
        view_evidence: [],
        participating_views: [],
        excluded_views: [],
        rule_set_key: 'capture-group-event-eligibility',
        rule_set_version: 'v1',
        rule_set_digest: 'd'.repeat(64),
        outcome: 'PASS',
        reason_codes: [],
        executed_checks: ['REVIEW_APPROVED'],
        evaluated_at: '2026-07-27T01:00:00Z',
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(recheckCaptureGroupCleaning(projectId, revisionId)).resolves.toMatchObject({
      outcome: 'PASS',
      rule_set_key: 'capture-group-event-eligibility',
    })
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-revisions/${revisionId}/capture-group-cleaning-rechecks`,
      expect.objectContaining({ method: 'POST', cache: 'no-store' }),
    )
  })
})

function jsonResponse(body: object, init?: ResponseInit) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
}
