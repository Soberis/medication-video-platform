import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  authorizeClipAssetPlayback,
  deleteClipAsset,
  createDatasetVersionDraft,
  getDatasetVersion,
  getCaptureSessionDatasetAdmissionPreview,
  listDatasetVersions,
  requestClipAssetMediaProbe,
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

  it('creates a dataset draft with CSRF protection and a fresh idempotency key', async () => {
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
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('dataset-version-idempotency-0001') })
    Object.defineProperty(document, 'cookie', { configurable: true, value: 'XSRF-TOKEN=csrf-test-token' })

    await expect(
      createDatasetVersionDraft(projectId, '配药操作训练候选', [captureSessionId]),
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
})
