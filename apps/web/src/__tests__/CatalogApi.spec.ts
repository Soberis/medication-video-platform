import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  authorizeClipAssetPlayback,
  createDatasetBuildSnapshot,
  deleteClipAsset,
  createDatasetVersionDraft,
  createDatasetSplitPlan,
  freezeDatasetVersion,
  getDatasetVersion,
  getDatasetVersionCurrentAdmission,
  getCaptureSessionDatasetAdmissionPreview,
  listClipAssetActivity,
  listCaptureEventLogs,
  listDatasetVersions,
  requestDatasetExport,
  requestClipAssetMediaProbe,
  startDatasetVersionBuild,
  validateDatasetVersion,
} from '@/api/catalog'

const projectId = '30000000-0000-4000-8000-000000000001'
const clipAssetId = '50000000-0000-4000-8000-000000000001'
const captureSessionId = '40000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('catalog API', () => {
  it('soft deletes a clip asset with a fresh idempotency key and a reason', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('delete-idempotency-key-0001') })

    await expect(deleteClipAsset(projectId, clipAssetId, '上传错误')).resolves.toBeUndefined()

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${clipAssetId}`,
      expect.objectContaining({
        method: 'DELETE',
        body: JSON.stringify({ reason: '上传错误' }),
        headers: expect.objectContaining({
          'Idempotency-Key': 'delete-idempotency-key-0001',
        }),
      }),
    )
  })

  it('requests a no-store data-review playback grant without persisting the URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          grant_id: '70000000-0000-4000-8000-000000000001',
          purpose: 'DATA_REVIEW',
          media_url: 'https://storage.invalid/review.mp4?signature=synthetic',
          expires_at: '2026-07-21T01:08:03Z',
          content_type: 'video/mp4',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(authorizeClipAssetPlayback(projectId, clipAssetId)).resolves.toMatchObject({
      purpose: 'DATA_REVIEW',
      content_type: 'video/mp4',
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${clipAssetId}/playback-grants`,
      expect.objectContaining({ method: 'POST', cache: 'no-store' }),
    )
  })

  it('explicitly queues a media probe before the user asks to play an uploaded video', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          media_job_id: '80000000-0000-4000-8000-000000000001',
          project_id: projectId,
          clip_asset_id: clipAssetId,
          job_type: 'MEDIA_PROBE',
          status: 'QUEUED',
        }),
        { status: 202, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('media-probe-idempotency-0001') })

    await expect(requestClipAssetMediaProbe(projectId, clipAssetId)).resolves.toMatchObject({
      job_type: 'MEDIA_PROBE',
      status: 'QUEUED',
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${clipAssetId}/media-probe-jobs`,
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'Idempotency-Key': 'media-probe-idempotency-0001' }),
      }),
    )
  })

  it('reads the current dataset-admission preview without caching it', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          capture_session_id: captureSessionId,
          eligible: false,
          assembly_status: 'COMPLETE',
          quality_status: 'PASS',
          expected_camera_count: 1,
          received_camera_count: 1,
          blocking_reasons: [],
          streams: [],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      getCaptureSessionDatasetAdmissionPreview(projectId, captureSessionId),
    ).resolves.toMatchObject({
      capture_session_id: captureSessionId,
      eligible: false,
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/capture-sessions/${captureSessionId}/dataset-admission-preview`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })

  it('reads only project-scoped event-log object indexes with opaque pagination', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          items: [
            {
              capture_event_log_id: '71000000-0000-4000-8000-000000000001',
              batch_id: '60000000-0000-4000-8000-000000000001',
              source_upload_file_id: '50000000-0000-4000-8000-000000000001',
              client_file_id: '40000000-0000-4000-8000-000000000001',
              device_id: 'synthetic-edge-01',
              content_type: 'application/json',
              size_bytes: 2048,
              sha256: 'a'.repeat(64),
              indexed_at: '2026-07-28T04:00:00Z',
            },
          ],
          next_cursor: 'opaque-next-cursor',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()

    await expect(
      listCaptureEventLogs(
        projectId,
        {
          batchId: '60000000-0000-4000-8000-000000000001',
          deviceId: 'synthetic-edge-01',
          indexedFrom: '2026-07-28T00:00:00Z',
          indexedBefore: '2026-07-29T00:00:00Z',
        },
        'opaque-previous-cursor',
        controller.signal,
      ),
    ).resolves.toMatchObject({
      items: [{ device_id: 'synthetic-edge-01' }],
      next_cursor: 'opaque-next-cursor',
    })

    const [url, options] = fetchMock.mock.calls[0] as [string, RequestInit]
    const requestUrl = new URL(url, 'http://localhost')
    expect(requestUrl.pathname).toBe(`/api/v1/projects/${projectId}/capture-event-logs`)
    expect(requestUrl.searchParams.get('limit')).toBe('20')
    expect(requestUrl.searchParams.get('batch_id')).toBe('60000000-0000-4000-8000-000000000001')
    expect(requestUrl.searchParams.get('device_id')).toBe('synthetic-edge-01')
    expect(requestUrl.searchParams.get('indexed_from')).toBe('2026-07-28T00:00:00Z')
    expect(requestUrl.searchParams.get('indexed_before')).toBe('2026-07-29T00:00:00Z')
    expect(requestUrl.searchParams.get('cursor')).toBe('opaque-previous-cursor')
    expect(options).toMatchObject({ method: 'GET', cache: 'no-store', signal: controller.signal })
  })

  it('reads minimized asset activity with an opaque cursor and no-store semantics', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          items: [
            {
              event_id: '71000000-0000-4000-8000-000000000001',
              occurred_at_utc: '2026-07-29T02:00:00Z',
              actor_type: 'USER',
              actor_id: 'reviewer',
              action: 'CLIP_ASSET_PLAYBACK_GRANTED',
              object_type: 'ClipAsset',
              object_id: clipAssetId,
              object_version: '1',
              outcome: 'SUCCEEDED',
              reason_code: 'DATA_REVIEW',
              request_id: 'playback-request-0001',
            },
          ],
          next_cursor: 'opaque-next-cursor',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()

    await expect(
      listClipAssetActivity(projectId, clipAssetId, 'opaque-previous-cursor', controller.signal),
    ).resolves.toMatchObject({
      items: [{ action: 'CLIP_ASSET_PLAYBACK_GRANTED' }],
      next_cursor: 'opaque-next-cursor',
    })

    const [url, options] = fetchMock.mock.calls[0] as [string, RequestInit]
    const requestUrl = new URL(url, 'http://localhost')
    expect(requestUrl.pathname).toBe(
      `/api/v1/projects/${projectId}/clip-assets/${clipAssetId}/activity`,
    )
    expect(requestUrl.searchParams.get('limit')).toBe('20')
    expect(requestUrl.searchParams.get('cursor')).toBe('opaque-previous-cursor')
    expect(options).toMatchObject({ method: 'GET', cache: 'no-store', signal: controller.signal })
  })

  it('creates a dataset draft with the caller-owned idempotency key and CSRF protection', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          dataset_version_id: '90000000-0000-4000-8000-000000000001',
          project_id: projectId,
          display_name: '配药操作训练候选',
          status: 'DRAFT',
          created_by: 'local-admin',
          created_at: '2026-07-28T03:00:00Z',
        }),
        { status: 201, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    Object.defineProperty(document, 'cookie', { configurable: true, value: 'XSRF-TOKEN=csrf-test-token' })

    await expect(
      createDatasetVersionDraft(
        projectId,
        '配药操作训练候选',
        [captureSessionId],
        'dataset-version-idempotency-0001',
      ),
    ).resolves.toMatchObject({ status: 'DRAFT' })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/dataset-versions`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          display_name: '配药操作训练候选',
          capture_session_ids: [captureSessionId],
        }),
        headers: expect.objectContaining({
          'Idempotency-Key': 'dataset-version-idempotency-0001',
          'X-XSRF-TOKEN': 'csrf-test-token',
        }),
      }),
    )
  })

  it('keeps an unreadable gateway failure retryable so a draft request can reuse its key', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('upstream unavailable', { status: 503 })))

    await expect(
      createDatasetVersionDraft(
        projectId,
        '配药操作训练候选',
        [captureSessionId],
        'dataset-version-idempotency-uncertain-0001',
      ),
    ).rejects.toMatchObject({ retryable: true, status: 503 })
  })

  it('starts a dataset build with CSRF protection and the caller-owned idempotency key', async () => {
    const datasetVersionId = '90000000-0000-4000-8000-000000000001'
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          dataset_version_id: datasetVersionId,
          status: 'BUILDING',
          started_by: 'local-admin',
          started_at: '2026-07-29T05:00:00Z',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    Object.defineProperty(document, 'cookie', { configurable: true, value: 'XSRF-TOKEN=csrf-test-token' })

    await expect(
      startDatasetVersionBuild(projectId, datasetVersionId, 'dataset-build-start-0001'),
    ).resolves.toMatchObject({ status: 'BUILDING' })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/build`,
      expect.objectContaining({
        method: 'POST',
        cache: 'no-store',
        headers: expect.objectContaining({
          'Idempotency-Key': 'dataset-build-start-0001',
          'X-XSRF-TOKEN': 'csrf-test-token',
        }),
      }),
    )
  })

  it('creates a dataset build snapshot with CSRF protection and the caller-owned idempotency key', async () => {
    const datasetVersionId = '90000000-0000-4000-8000-000000000001'
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          dataset_build_snapshot_id: '91000000-0000-4000-8000-000000000001',
          dataset_version_id: datasetVersionId,
          dataset_build_specification_id: '92000000-0000-4000-8000-000000000001',
          dataset_split_plan_id: '93000000-0000-4000-8000-000000000001',
          snapshot_schema_version: 'DATASET_RELATIONAL_SNAPSHOT_V1',
          input_variant_policy: 'RAW_CLIP_ASSET_V1',
          member_count: 2,
          stream_count: 2,
          snapshot_digest: 'a'.repeat(64),
          created_by: 'local-admin',
          created_at: '2026-07-29T06:00:00Z',
        }),
        { status: 201, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    Object.defineProperty(document, 'cookie', { configurable: true, value: 'XSRF-TOKEN=csrf-test-token' })

    await expect(
      createDatasetBuildSnapshot(projectId, datasetVersionId, 'dataset-build-snapshot-0001'),
    ).resolves.toMatchObject({ snapshot_schema_version: 'DATASET_RELATIONAL_SNAPSHOT_V1' })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/build-snapshots`,
      expect.objectContaining({
        method: 'POST',
        cache: 'no-store',
        headers: expect.objectContaining({
          'Idempotency-Key': 'dataset-build-snapshot-0001',
          'X-XSRF-TOKEN': 'csrf-test-token',
        }),
      }),
    )
  })

  it('records dataset freeze validation with CSRF protection and the caller-owned idempotency key', async () => {
    const datasetVersionId = '90000000-0000-4000-8000-000000000001'
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          dataset_version_validation_id: '94000000-0000-4000-8000-000000000001',
          dataset_version_id: datasetVersionId,
          dataset_build_snapshot_id: '91000000-0000-4000-8000-000000000001',
          validation_profile_version: 'DATASET_FREEZE_VALIDATION_V1',
          outcome: 'PASS',
          reason_codes: [],
          executed_checks: [
            'CURRENT_ADMISSION',
            'REFERENCE_INTEGRITY',
            'SNAPSHOT_DIGEST',
            'SPLIT_ISOLATION',
            'BUILD_CONTRACT',
          ],
          validated_snapshot_digest: 'a'.repeat(64),
          validated_by: 'local-admin',
          validated_at: '2026-07-30T01:00:00Z',
        }),
        { status: 201, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    Object.defineProperty(document, 'cookie', { configurable: true, value: 'XSRF-TOKEN=csrf-test-token' })

    await expect(
      validateDatasetVersion(projectId, datasetVersionId, 'dataset-validation-0001'),
    ).resolves.toMatchObject({ outcome: 'PASS' })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/validations`,
      expect.objectContaining({
        method: 'POST',
        cache: 'no-store',
        headers: expect.objectContaining({
          'Idempotency-Key': 'dataset-validation-0001',
          'X-XSRF-TOKEN': 'csrf-test-token',
        }),
      }),
    )
  })

  it('independently approves and freezes a dataset version with CSRF and idempotency', async () => {
    const datasetVersionId = '90000000-0000-4000-8000-000000000001'
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          dataset_version_id: datasetVersionId,
          project_id: projectId,
          status: 'FROZEN',
          validation_id: '94000000-0000-4000-8000-000000000001',
          approval_request_id: '95000000-0000-4000-8000-000000000001',
          approval_decision_id: '96000000-0000-4000-8000-000000000001',
          frozen_by: 'independent-admin',
          frozen_at: '2026-07-30T02:00:00Z',
        }),
        { status: 201, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    Object.defineProperty(document, 'cookie', { configurable: true, value: 'XSRF-TOKEN=csrf-test-token' })

    await expect(
      freezeDatasetVersion(projectId, datasetVersionId, 'dataset-freeze-0001'),
    ).resolves.toMatchObject({ status: 'FROZEN' })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/freezes`,
      expect.objectContaining({
        method: 'POST',
        cache: 'no-store',
        headers: expect.objectContaining({
          'Idempotency-Key': 'dataset-freeze-0001',
          'X-XSRF-TOKEN': 'csrf-test-token',
        }),
      }),
    )
  })

  it('requests a frozen dataset export with an explicit purpose expiry and idempotency key', async () => {
    const datasetVersionId = '90000000-0000-4000-8000-000000000001'
    const expiresAt = '2026-08-06T02:00:00.000Z'
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          dataset_export_id: '97000000-0000-4000-8000-000000000001',
          project_id: projectId,
          dataset_version_id: datasetVersionId,
          status: 'REQUESTED',
          purpose: 'MODEL_TRAINING',
          expires_at: expiresAt,
        }),
        { status: 201, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    Object.defineProperty(document, 'cookie', { configurable: true, value: 'XSRF-TOKEN=csrf-test-token' })

    await expect(
      requestDatasetExport(
        projectId,
        datasetVersionId,
        expiresAt,
        'dataset-export-request-0001',
      ),
    ).resolves.toMatchObject({ status: 'REQUESTED', purpose: 'MODEL_TRAINING' })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/exports`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ purpose: 'MODEL_TRAINING', expires_at: expiresAt }),
        cache: 'no-store',
        headers: expect.objectContaining({
          'Content-Type': 'application/json',
          'Idempotency-Key': 'dataset-export-request-0001',
          'X-XSRF-TOKEN': 'csrf-test-token',
        }),
      }),
    )
  })

  it('reads dataset-draft metadata and its immutable evidence snapshot without caching', async () => {
    const datasetVersionId = '90000000-0000-4000-8000-000000000001'
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) =>
      Promise.resolve(
        String(input).includes(`/${datasetVersionId}`)
          ? new Response(
              JSON.stringify({
                dataset_version_id: datasetVersionId,
                project_id: projectId,
                display_name: '配药操作训练候选',
                status: 'DRAFT',
                created_by: 'local-admin',
                created_at: '2026-07-28T03:00:00Z',
                members: [
                  {
                    capture_session_id: captureSessionId,
                    candidate_index: 0,
                    expected_camera_count: 1,
                    received_camera_count: 1,
                    assembly_status: 'COMPLETE',
                    quality_status: 'PASS',
                    streams: [],
                  },
                ],
              }),
              { status: 200, headers: { 'Content-Type': 'application/json' } },
            )
          : new Response(
              JSON.stringify({
                items: [
                  {
                    dataset_version_id: datasetVersionId,
                    project_id: projectId,
                    display_name: '配药操作训练候选',
                    status: 'DRAFT',
                    created_by: 'local-admin',
                    created_at: '2026-07-28T03:00:00Z',
                  },
                ],
                next_cursor: null,
              }),
              { status: 200, headers: { 'Content-Type': 'application/json' } },
            ),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(listDatasetVersions(projectId, null)).resolves.toMatchObject({
      items: [{ dataset_version_id: datasetVersionId }],
    })
    await expect(getDatasetVersion(projectId, datasetVersionId)).resolves.toMatchObject({
      members: [{ capture_session_id: captureSessionId }],
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/dataset-versions?limit=20`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })

  it('reads current dataset-admission facts only through a no-store abortable request', async () => {
    const datasetVersionId = '90000000-0000-4000-8000-000000000001'
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          dataset_version_id: datasetVersionId,
          all_members_currently_admission_eligible: false,
          members: [],
          build_readiness: {
            ready_for_build: false,
            blocking_reasons: ['SPLIT_PLAN_NOT_FIXED'],
            immutable_evidence_still_current: true,
            uniform_label_schema_digest: 'a'.repeat(64),
            uniform_cleaning_rule_set_digest: 'b'.repeat(64),
            dataset_split_plan_id: null,
          },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()

    await expect(
      getDatasetVersionCurrentAdmission(projectId, datasetVersionId, controller.signal),
    ).resolves.toMatchObject({
      dataset_version_id: datasetVersionId,
      all_members_currently_admission_eligible: false,
      build_readiness: {
        ready_for_build: false,
        blocking_reasons: ['SPLIT_PLAN_NOT_FIXED'],
      },
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/current-admission`,
      expect.objectContaining({ method: 'GET', cache: 'no-store', signal: controller.signal }),
    )
  })

  it('creates one idempotent pilot/holdout split plan for a dataset draft', async () => {
    const datasetVersionId = '90000000-0000-4000-8000-000000000001'
    const idempotencyKey = 'dataset-split-plan-0001'
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          dataset_split_plan_id: '91000000-0000-4000-8000-000000000001',
          dataset_version_id: datasetVersionId,
          policy_version: 'CAPTURE_SESSION_HASH_RANK_V1',
          split_seed: 20260729,
          pilot_count: 1,
          holdout_count: 1,
          created_by: 'local-admin',
          created_at: '2026-07-29T03:00:00Z',
          assignments: [],
        }),
        { status: 201, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(createDatasetSplitPlan(projectId, datasetVersionId, 1, idempotencyKey)).resolves.toMatchObject({
      policy_version: 'CAPTURE_SESSION_HASH_RANK_V1',
      holdout_count: 1,
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/dataset-versions/${datasetVersionId}/split-plans`,
      expect.objectContaining({
        method: 'POST',
        cache: 'no-store',
        body: JSON.stringify({ holdout_count: 1 }),
        headers: expect.objectContaining({ 'Idempotency-Key': idempotencyKey }),
      }),
    )
  })
})
