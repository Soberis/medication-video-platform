import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  assignCaptureGroupHumanLabelTask,
  createAnnotationLabel,
  downloadHumanDemonstrationHdf5,
  getAnnotationLabelSchema,
  listAnnotationTasks,
  recheckCaptureGroupCleaning,
  reviewAnnotationRevision,
  submitAnnotationRevision,
} from '@/api/annotation'

const projectId = '30000000-0000-4000-8000-000000000001'
const assetId = '50000000-0000-4000-8000-000000000001'
const captureGroupId = '60000000-0000-4000-8000-000000000001'
const labelSchemaId = '70000000-0000-4000-8000-000000000001'
const labelSchemaDigest = 'b'.repeat(64)
const groupInputVersion = 'capture-group-view-set.v1:synthetic'
const confirmedViewSet = {
  schema_version: 'capture-group-view-set.v1',
  capture_group_id: captureGroupId,
  capture_group_version: 1,
  camera_set_snapshot_id: '43000000-0000-4000-8000-000000000001',
  receipt_state: 'COMPLETE',
  views: [],
}
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
  it('downloads the approved HDF5 demonstration with the server filename', async () => {
    const fileName = 'human_demonstration_hdf5_v1_event_60000000_20260905T010203Z.hdf5'
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(new Uint8Array([0x89, 0x48, 0x44, 0x46]), {
        headers: { 'Content-Disposition': `attachment; filename="${fileName}"` },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      downloadHumanDemonstrationHdf5(projectId, [captureGroupId]),
    ).resolves.toMatchObject({ fileName })
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/human-demonstrations/hdf5/v1/download?capture_group_id=${captureGroupId}`,
      expect.objectContaining({
        method: 'GET',
        headers: { Accept: 'application/x-hdf5' },
        cache: 'no-store',
      }),
    )
  })

  it('assigns an optional CaptureGroup human-label task with an idempotency key', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ annotation_task_id: 'a0000000-0000-4000-8000-000000000001' }),
      )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn().mockReturnValue('human-label-assignment-0001'),
    })

    await expect(
      assignCaptureGroupHumanLabelTask(projectId, captureGroupId, 'local-synthetic-uploader'),
    ).resolves.toMatchObject({ annotation_task_id: 'a0000000-0000-4000-8000-000000000001' })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/capture-groups/${captureGroupId}/annotation-tasks`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ assignee_actor_id: 'local-synthetic-uploader' }),
        headers: expect.objectContaining({ 'Idempotency-Key': 'human-label-assignment-0001' }),
      }),
    )
  })

  it('lists the visible CaptureGroup task queue and explains a qualified-state rejection', async () => {
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
    await expect(listAnnotationTasks(projectId)).rejects.toMatchObject({
      errorCode: 'ANNOTATION_TASK_QUALITY_NOT_QUALIFIED',
      message: '采集记录整体质量为“合格”后才能开始标注。',
    })
  })

  it('keeps the completed CaptureGroup task and cleaning result in the list response', async () => {
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
              capture_group_id: captureGroupId,
              primary_clip_asset_id: assetId,
              primary_camera_number: 1,
              capture_group_quality_review_id: '45000000-0000-4000-8000-000000000001',
              group_quality_revision: 1,
              confirmed_view_set: confirmedViewSet,
              group_input_version: groupInputVersion,
              label_schema_id: labelSchemaId,
              label_schema_digest: labelSchemaDigest,
              assignee_actor_id: 'local-synthetic-uploader',
              created_by: 'local-synthetic-admin',
              status: 'COMPLETED',
              can_submit: false,
              can_review: false,
              can_recheck_capture_group_cleaning: true,
              annotation_revision: {
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
                capture_group_id: captureGroupId,
                primary_clip_asset_id: assetId,
                primary_camera_number: 1,
                confirmed_view_set: confirmedViewSet,
                input_version: groupInputVersion,
                review: {
                  annotation_review_id: '81000000-0000-4000-8000-000000000001',
                  project_id: projectId,
                  annotation_revision_id: '80000000-0000-4000-8000-000000000001',
                  decision: 'APPROVED',
                  review_note: null,
                  reviewed_by: 'local-synthetic-admin',
                  reviewed_at: '2026-07-26T01:10:00Z',
                  capture_group_id: captureGroupId,
                  revision: 1,
                  primary_clip_asset_id: assetId,
                  primary_camera_number: 1,
                  confirmed_view_set: confirmedViewSet,
                  input_version: groupInputVersion,
                  qualified_views: [],
                  rejected_missing_views: [],
                  candidate_evidence: [],
                },
                capture_group_cleaning: {
                  capture_group_cleaning_id: '82000000-0000-4000-8000-000000000001',
                  project_id: projectId,
                  capture_group_id: '60000000-0000-4000-8000-000000000001',
                  annotation_revision_id: '80000000-0000-4000-8000-000000000001',
                  annotation_review_id: '81000000-0000-4000-8000-000000000001',
                  annotation_review_decision: 'APPROVED',
                  group_quality_result: 'QUALIFIED',
                  group_quality_revision: 1,
                  primary_clip_asset_id: assetId,
                  primary_camera_number: 1,
                  participating_views: [],
                  excluded_views: [],
                  outcome: 'PASS',
                  reason_codes: [],
                  executed_checks: ['REVIEW_APPROVED'],
                  evaluated_at: '2026-07-26T01:10:00Z',
                },
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
          annotation_revision: { capture_group_cleaning: { outcome: 'PASS' } },
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
        ],
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(getAnnotationLabelSchema(projectId, signal)).resolves.toMatchObject({
      label_schema_id: labelSchemaId,
      layer: 'primary_step',
      labels: [{ label_id: 'acquire_item', display_name: '拿取容器/工具' }],
    })
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-label-schema`,
      expect.objectContaining({ method: 'GET', cache: 'no-store', signal }),
    )
  })

  it('publishes a new immutable label schema', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ label_schema_id: labelSchemaId, labels: [] }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      createAnnotationLabel(projectId, {
        label_id: 'verify_dose',
        display_name: '核对剂量',
        description: '核对本次已配制剂量。',
      }),
    ).resolves.toMatchObject({ label_schema_id: labelSchemaId })
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-label-schema/labels`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          label_id: 'verify_dose',
          display_name: '核对剂量',
          description: '核对本次已配制剂量。',
        }),
      }),
    )
  })

  it('reads and publishes the global library without project context', async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation(() => Promise.resolve(jsonResponse({ labels: [] })))
    vi.stubGlobal('fetch', fetchMock)
    await getAnnotationLabelSchema(null)
    await createAnnotationLabel(null, {
      label_id: 'verify_dose',
      display_name: '核对剂量',
      description: '核对剂量。',
    })
    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/annotation-label-schema',
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/annotation-label-schema/labels',
      expect.objectContaining({ method: 'POST' }),
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
        capture_group_id: captureGroupId,
        primary_clip_asset_id: assetId,
        primary_camera_number: 1,
        confirmed_view_set: confirmedViewSet,
        input_version: groupInputVersion,
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
        [{ start_ms: 100, end_ms: 900, label: 'acquire_item' }],
        '合成验证',
      ),
    ).resolves.toMatchObject({ annotation_task_id: taskId, revision_number: 1 })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-tasks/${taskId}/revisions`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          segments: [{ start_ms: 100, end_ms: 900, label: 'acquire_item' }],
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
        capture_group_id: captureGroupId,
        revision: 1,
        primary_clip_asset_id: assetId,
        primary_camera_number: 1,
        confirmed_view_set: confirmedViewSet,
        input_version: groupInputVersion,
        qualified_views: [],
        rejected_missing_views: [],
        candidate_evidence: [],
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
        capture_group_id: captureGroupId,
        revision: 1,
        primary_clip_asset_id: assetId,
        primary_camera_number: 1,
        confirmed_view_set: confirmedViewSet,
        input_version: groupInputVersion,
        qualified_views: [],
        rejected_missing_views: [],
        candidate_evidence: [],
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

  it('rechecks CaptureGroup cleaning through the existing server operation', async () => {
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
        primary_interval: [{ start_ms: 100, end_ms: 900, label: 'acquire_item' }],
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
