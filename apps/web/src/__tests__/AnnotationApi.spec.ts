import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  assignCaptureGroupHumanLabelTask,
  createAnnotationLabel,
  downloadHumanDemonstrationHdf5,
  getAnnotationLabelSchema,
  listAnnotationTasks,
  reviewAnnotationRevision,
  updateAnnotationCameraView,
  submitAnnotationRevision,
} from '@/api/annotation'
import { cameraViewFixture } from './capture-group-fixtures'

const projectId = '30000000-0000-4000-8000-000000000001'
const taskId = '70000000-0000-4000-8000-000000000001'
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
  it.each([
    [
      'ANNOTATION_CANDIDATE_SOURCE_INVALID',
      'AI 候选来源与当前机位或标签规范不一致，请刷新候选后重试。',
    ],
    ['ANNOTATION_CAMERA_VIEW_VERSION_CONFLICT', '此机位已被更新，请刷新后重新提交。'],
    ['ANNOTATION_CAMERA_VIEW_SEGMENT_OVERLAP', '本机位的动作时间段存在重叠，请调整后提交。'],
  ])('explains camera submission failures: %s', async (errorCode, message) => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ error_code: errorCode }, { status: 409 })),
    )
    await expect(
      updateAnnotationCameraView(projectId, taskId, 1, {
        expected_version: 0,
        expected_input_version: groupInputVersion,
        state: 'SUBMITTED',
        segments: [],
        note: '',
        exclusion_reason: null,
      }),
    ).rejects.toMatchObject({ message, errorCode })
  })

  it('downloads the approved HDF5 demonstration with the server filename', async () => {
    const fileName = 'human_demonstration_hdf5_v2_event_60000000_20260905T010203Z.hdf5'
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
      `/api/v1/projects/${projectId}/human-demonstrations/hdf5/v2/download?capture_group_id=${captureGroupId}`,
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
              camera_views: [
                cameraViewFixture(1, {
                  version: 2,
                  state: 'SUBMITTED',
                  segments: [
                    {
                      segment_id: 'segment-camera-1',
                      start_ms: 100,
                      end_ms: 900,
                      label: 'acquire_item',
                      source_candidate_ids: ['candidate-camera-1'],
                    },
                  ],
                }),
                cameraViewFixture(2, {
                  version: 1,
                  state: 'DRAFT',
                  segments: [
                    {
                      segment_id: 'segment-camera-2',
                      start_ms: 250,
                      end_ms: 1100,
                      label: 'open_container',
                      source_candidate_ids: [],
                    },
                  ],
                  note: 'camera-local timing',
                }),
              ],
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
          camera_views: [
            {
              camera_number: 1,
              clip_asset_id: assetId,
              version: 2,
              state: 'SUBMITTED',
              segments: [{ segment_id: 'segment-camera-1', start_ms: 100, end_ms: 900 }],
            },
            {
              camera_number: 2,
              clip_asset_id: '50000000-0000-4000-8000-000000000002',
              version: 1,
              state: 'DRAFT',
              segments: [{ segment_id: 'segment-camera-2', start_ms: 250, end_ms: 1100 }],
            },
          ],
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

  it('reads and publishes the global library without project context', async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation(() => Promise.resolve(jsonResponse({ labels: [] })))
    vi.stubGlobal('fetch', fetchMock)
    await getAnnotationLabelSchema(null)
    await createAnnotationLabel({
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

  it('saves one camera-local draft with optimistic versions and an idempotency key', async () => {
    const cameraView = {
      camera_number: 2,
      camera_stream_id: '41000000-0000-4000-8000-000000000002',
      clip_asset_id: '50000000-0000-4000-8000-000000000002',
      source_asset_sha256: 'd'.repeat(64),
      media_status: 'INGESTED',
      version: 5,
      state: 'DRAFT' as const,
      segments: [
        {
          segment_id: 'segment-camera-2',
          start_ms: 250,
          end_ms: 1100,
          label: 'open_container',
          source_candidate_ids: ['candidate-camera-2'],
        },
      ],
      note: 'camera-local note',
      exclusion_reason: null,
    }
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        annotation_task_id: taskId,
        project_id: projectId,
        capture_group_id: captureGroupId,
        version: 8,
        camera_views: [cameraViewFixture(1), cameraView],
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn().mockReturnValue('camera-view-idempotency-0001'),
    })

    const savedTask = await updateAnnotationCameraView(projectId, taskId, 2, {
      expected_version: 4,
      expected_input_version: groupInputVersion,
      state: 'DRAFT',
      segments: cameraView.segments,
      note: 'camera-local note',
      exclusion_reason: null,
    })
    expect(savedTask).toMatchObject({
      annotation_task_id: taskId,
      version: 8,
    })
    expect(savedTask.camera_views).toHaveLength(2)
    expect(savedTask.camera_views?.[0]).toMatchObject({ camera_number: 1, state: 'PENDING' })
    expect(savedTask.camera_views?.[1]).toMatchObject({
      camera_number: 2,
      version: 5,
      state: 'DRAFT',
      segments: [{ segment_id: 'segment-camera-2', start_ms: 250, end_ms: 1100 }],
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-tasks/${taskId}/camera-views/2`,
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({
          expected_version: 4,
          expected_input_version: groupInputVersion,
          state: 'DRAFT',
          segments: cameraView.segments,
          note: 'camera-local note',
          exclusion_reason: null,
        }),
        headers: expect.objectContaining({
          'Idempotency-Key': 'camera-view-idempotency-0001',
        }),
      }),
    )
  })

  it('submits multi-camera correspondences with task and input versions', async () => {
    const taskId = '30000000-0000-4000-8000-000000000003'
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        annotation_revision_id: '30000000-0000-4000-8000-000000000004',
        project_id: projectId,
        annotation_task_id: taskId,
        capture_group_id: captureGroupId,
        confirmed_view_set: confirmedViewSet,
        input_version: groupInputVersion,
        label_schema_id: labelSchemaId,
        label_schema_digest: labelSchemaDigest,
        revision_number: 1,
        annotation_schema_version: 'annotation-document.v2',
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
      submitAnnotationRevision(projectId, taskId, {
        expectedTaskVersion: 7,
        inputVersion: groupInputVersion,
        correspondences: [
          {
            correspondence_id: 'correspondence-1',
            status: 'CONFIRMED',
            segments: [
              { camera_number: 1, segment_id: 'segment-camera-1' },
              { camera_number: 2, segment_id: 'segment-camera-2' },
            ],
          },
          {
            correspondence_id: 'correspondence-2',
            status: 'UNCERTAIN',
            segments: [{ camera_number: 2, segment_id: 'segment-camera-3' }],
            reason: '机位 1 遮挡，无法确认对应动作',
          },
        ],
        note: '  合成验证  ',
      }),
    ).resolves.toMatchObject({ annotation_task_id: taskId, revision_number: 1 })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-tasks/${taskId}/revisions`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          expected_task_version: 7,
          expected_input_version: groupInputVersion,
          correspondences: [
            {
              correspondence_id: 'correspondence-1',
              status: 'CONFIRMED',
              segments: [
                { camera_number: 1, segment_id: 'segment-camera-1' },
                { camera_number: 2, segment_id: 'segment-camera-2' },
              ],
            },
            {
              correspondence_id: 'correspondence-2',
              status: 'UNCERTAIN',
              segments: [{ camera_number: 2, segment_id: 'segment-camera-3' }],
              reason: '机位 1 遮挡，无法确认对应动作',
            },
          ],
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

  it('records the reviewed and returned camera set with a review decision', async () => {
    const revisionId = '30000000-0000-4000-8000-000000000004'
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        annotation_review_id: '30000000-0000-4000-8000-000000000005',
        project_id: projectId,
        annotation_revision_id: revisionId,
        decision: 'REJECTED',
        review_note: '请复核机位 2。',
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
      randomUUID: vi.fn().mockReturnValue('annotation-review-idempotency-0003'),
    })

    await reviewAnnotationRevision(projectId, revisionId, 'REJECTED', '请复核机位 2。', {
      reviewedCameraNumbers: [1, 2],
      returnCameraNumbers: [2],
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/annotation-revisions/${revisionId}/review`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          decision: 'REJECTED',
          review_note: '请复核机位 2。',
          reviewed_camera_numbers: [1, 2],
          return_camera_numbers: [2],
        }),
        headers: expect.objectContaining({
          'Idempotency-Key': 'annotation-review-idempotency-0003',
        }),
      }),
    )
  })
})

function jsonResponse(body: object, init?: ResponseInit) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
}
