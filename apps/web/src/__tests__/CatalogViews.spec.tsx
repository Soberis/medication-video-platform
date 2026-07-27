import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { renderRoute } from '@/test/render'
import AssetDetailView from '@/views/AssetDetailView'
import AssetListView from '@/views/AssetListView'
import SessionDetailView from '@/views/SessionDetailView'
import SessionListView from '@/views/SessionListView'

const projectId = '30000000-0000-4000-8000-000000000001'
const sessionId = '40000000-0000-4000-8000-000000000001'
const assetId = '50000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('catalog views', () => {
  it('distinguishes bound, ingested and pending quality facts in the session list', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({
            items: [
              {
                capture_session_id: sessionId,
                batch_id: '60000000-0000-4000-8000-000000000001',
                device_id: 'synthetic-edge-01',
                camera_set_id: '70000000-0000-4000-8000-000000000001',
                camera_set_key: 'dispensing-cabin-a',
                camera_set_configuration_version: 1,
                camera_layout_version: 'layout-v1',
                expected_camera_count: 2,
                bound_stream_count: 1,
                received_camera_count: 0,
                ingest_failed_stream_count: 0,
                sync_group_id: 'sync-group-01',
                device_time_utc: '2026-07-15T01:02:03Z',
                server_receive_time: '2026-07-15T01:03:03Z',
                assembly_status: 'ASSEMBLING',
                quality_status: 'PENDING',
                created_at: '2026-07-15T01:03:03Z',
              },
            ],
            next_cursor: null,
          }),
        ),
    )
    renderSessionList()
    expect(await screen.findByText('已绑定 1 / 2')).toBeInTheDocument()
    expect(screen.getByText('已入库 0')).toBeInTheDocument()
    expect(screen.getByText('待检测')).toBeInTheDocument()
    expect(screen.getByText('组装中')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining(`/api/v1/projects/${projectId}/capture-sessions`),
      expect.any(Object),
    )
  }, 15_000)

  it('shows a concise, evidence-based dataset readiness preview on the session detail page', async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation((input: RequestInfo | URL) =>
        Promise.resolve(
          String(input).endsWith('/dataset-admission-preview')
            ? jsonResponse({
                capture_session_id: sessionId,
                eligible: false,
                assembly_status: 'COMPLETE',
                quality_status: 'PENDING',
                expected_camera_count: 1,
                received_camera_count: 1,
                blocking_reasons: ['SESSION_QUALITY_NOT_PASS'],
                streams: [
                  {
                    camera_stream_id: '41000000-0000-4000-8000-000000000001',
                    clip_asset_id: assetId,
                    camera_index: 0,
                    eligible: false,
                    stream_status: 'INGESTED',
                    stream_quality_status: 'PENDING',
                    asset_status: 'INGESTED',
                    asset_deleted: false,
                    quality_review_decision: 'UNREVIEWED',
                    annotation_revision_id: null,
                    annotation_review_decision: 'UNREVIEWED',
                    post_cleaning_decision: 'MISSING',
                    annotation_document_sha256: null,
                    label_schema_digest: null,
                    blocking_reasons: [
                      'QUALITY_REVIEW_NOT_QUALIFIED',
                      'ANNOTATION_REVISION_MISSING',
                    ],
                  },
                ],
              })
            : jsonResponse(captureSessionDetail()),
        ),
      )
    vi.stubGlobal('fetch', fetchMock)
    renderSessionDetail()
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('配药操作详情')
    expect(screen.getAllByText('synthetic-edge-01').length).toBeGreaterThan(0)
    expect(screen.getByText('训练数据准备度')).toBeInTheDocument()
    expect(screen.getByText('尚未准备完成')).toBeInTheDocument()
    expect(screen.getByText('本次配药操作的技术检测尚未通过')).toBeInTheDocument()
    expect(screen.getByText('有视频尚未通过人工质量判断')).toBeInTheDocument()
    expect(screen.getByText('有视频尚未提交人工标注')).toBeInTheDocument()
    expect(document.querySelectorAll('.detail-card')).toHaveLength(3)
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/capture-sessions/${sessionId}`,
      expect.any(Object),
    )
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/capture-sessions/${sessionId}/dataset-admission-preview`,
      expect.any(Object),
    )
  })

  it('keeps the session facts available when the readiness preview temporarily fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation((input: RequestInfo | URL) =>
          Promise.resolve(
            String(input).endsWith('/dataset-admission-preview')
              ? jsonResponse(
                  {
                    error_code: 'DATASET_ADMISSION_PREVIEW_UNAVAILABLE',
                    error_message: 'Synthetic failure.',
                    retryable: true,
                  },
                  503,
                )
              : jsonResponse(captureSessionDetail()),
          ),
        ),
    )
    renderSessionDetail()
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('配药操作详情')
    expect(screen.getByText('采集概况')).toBeInTheDocument()
    expect(screen.getByText('暂时无法读取视频数据，请稍后重试。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeInTheDocument()
  })

  it('offers direct viewing for an uploaded video without exposing storage or using an identifier as the page title', async () => {
    stubAsset({ role: 'MEMBER', status: 'UPLOADED' })
    renderAssetDetail()
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('视频详情')
    expect(screen.getByText('可以直接查看视频')).toBeInTheDocument()
    expect(screen.getByText(/视频已完成上传完整性校验/)).toBeInTheDocument()
    expect(screen.getAllByText('90000000-0000-4000-8000-000000000001').length).toBeGreaterThan(0)
    expect(screen.getByText(/尚未绑定到 CaptureSession/)).toBeInTheDocument()
    expect(screen.getAllByText('未分类').length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: '播放视频' })).toBeInTheDocument()
    expect(screen.queryByLabelText('视频预览')).not.toBeInTheDocument()
    expect(document.body.innerHTML).not.toContain('object_key')
    expect(document.body.innerHTML).not.toContain('storage_bucket')
  })

  it('does not offer the destructive action to a project member', async () => {
    stubAsset({ role: 'MEMBER', status: 'UPLOADED' })
    renderAssetDetail()
    await screen.findByText('可以直接查看视频')
    expect(screen.queryByRole('button', { name: '删除视频' })).not.toBeInTheDocument()
  })

  it('shows the existing project folders to an administrator classifying a video', async () => {
    const folderId = 'a0000000-0000-4000-8000-000000000001'
    const fetchMock = stubAsset({
      role: 'ADMIN',
      status: 'UPLOADED',
      folders: [
        {
          folder_id: folderId,
          project_id: projectId,
          display_name: '待标注',
          version: 0,
          created_at: '2026-07-15T01:03:03Z',
          updated_at: '2026-07-15T01:03:03Z',
        },
      ],
    })
    renderAssetDetail()
    expect(await screen.findByText('视频分类')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/asset-folders`,
      expect.any(Object),
    )
    expect(screen.getByRole('button', { name: '更新分类' })).toBeInTheDocument()
  })

  it('lets a project member request playback only after an explicit click', async () => {
    const fetchMock = stubAsset({
      role: 'MEMBER',
      status: 'INGESTED',
      mediaReport: verifiedMediaReport(),
      quality: unreviewedQuality(),
      playback: true,
    })
    const user = userEvent.setup()
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })
    expect(await screen.findByRole('button', { name: '播放视频' })).toBeInTheDocument()
    expect(screen.queryByLabelText('视频预览')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '播放视频' }))
    const video = await screen.findByLabelText('视频预览')
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${assetId}/playback-grants`,
      expect.objectContaining({ method: 'POST' }),
    )
    expect(video).toHaveAttribute('src', expect.stringContaining('signature=synthetic'))
    expect(video).toHaveAttribute('preload', 'none')
    expect(screen.getByText('人工查看')).toBeInTheDocument()
    expect(screen.getByText('完整解码已验证')).toBeInTheDocument()
    expect(screen.getByText('0:02')).toBeInTheDocument()
    expect(screen.queryByText('人工隐私评估')).not.toBeInTheDocument()
    fireEvent.error(video)
    expect(await screen.findByText(/视频加载失败/)).toBeInTheDocument()
    expect(screen.queryByLabelText('视频预览')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新获取播放授权' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '保存质量结论' }))
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/clip-assets/${assetId}/quality-review`,
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({
            decision: 'QUALIFIED',
            reason_code: 'MEETS_REQUIREMENTS',
            expected_version: 0,
          }),
        }),
      ),
    )
    expect((await screen.findAllByText('符合训练数据要求')).length).toBeGreaterThan(0)
  })

  it('lets a project member play an uploaded video and separately start media detection', async () => {
    const fetchMock = stubAsset({
      role: 'MEMBER',
      status: 'UPLOADED',
      playback: true,
      mediaProbe: true,
    })
    const user = userEvent.setup()
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })
    expect(await screen.findByText('上传完成，待检测')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '播放视频' }))
    expect(await screen.findByLabelText('视频预览')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '检测媒体信息' }))
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${assetId}/media-probe-jobs`,
      expect.objectContaining({ method: 'POST' }),
    )
    expect(await screen.findByText(/媒体检测已排队/)).toBeInTheDocument()
    expect(screen.getByLabelText('视频预览')).toBeInTheDocument()
  })

  it('lets an administrator create one task only after the video is human-qualified', async () => {
    const membership = {
      membership_id: 'd0000000-0000-4000-8000-000000000001',
      project_id: projectId,
      actor_id: 'local-synthetic-uploader',
      role: 'MEMBER',
      status: 'ACTIVE',
      version: 0,
      updated_at: '2026-07-23T01:00:00Z',
    }
    const fetchMock = stubAsset({
      role: 'ADMIN',
      status: 'INGESTED',
      mediaReport: verifiedMediaReport(),
      quality: {
        ...unreviewedQuality(),
        quality_review_id: 'c0000000-0000-4000-8000-000000000001',
        decision: 'QUALIFIED',
        reason_code: 'MEETS_REQUIREMENTS',
        reviewed_by: 'local-synthetic-uploader',
        reviewed_at: '2026-07-23T01:00:00Z',
      },
      memberships: [membership],
      annotationTask: true,
    })
    const user = userEvent.setup()
    renderAssetDetail()
    expect(
      await screen.findByText('该视频已通过人工质量判断。请选择项目成员并创建标注任务。'),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '创建标注任务' }))
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/clip-assets/${assetId}/annotation-tasks`,
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ assignee_actor_id: 'local-synthetic-uploader' }),
        }),
      ),
    )
    expect(await screen.findByText('标注任务已创建并分配')).toBeInTheDocument()
  })

  it('loads project folders and renders the asset classification in the explicit project scope', async () => {
    const folderId = 'a0000000-0000-4000-8000-000000000001'
    const fetchMock = vi
      .fn()
      .mockImplementation((input: RequestInfo | URL) =>
        Promise.resolve(
          String(input).includes('/asset-folders')
            ? jsonResponse({
                items: [
                  {
                    folder_id: folderId,
                    project_id: projectId,
                    display_name: '待标注',
                    version: 0,
                    created_at: '2026-07-15T01:03:03Z',
                    updated_at: '2026-07-15T01:03:03Z',
                  },
                ],
              })
            : jsonResponse({
                items: [{ ...assetDetail('UPLOADED'), folder_id: folderId }],
                next_cursor: null,
              }),
        ),
      )
    vi.stubGlobal('fetch', fetchMock)
    renderAssetList()
    expect(await screen.findByText('待标注')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining(`/api/v1/projects/${projectId}/clip-assets`),
      expect.any(Object),
    )
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/asset-folders`,
      expect.any(Object),
    )
  })
})

function renderSessionList() {
  return renderRoute(<SessionListView />, {
    route: `/projects/${projectId}/sessions`,
    path: '/projects/:projectId/sessions',
  })
}
function renderSessionDetail() {
  return renderRoute(<SessionDetailView />, {
    route: `/projects/${projectId}/sessions/${sessionId}`,
    path: '/projects/:projectId/sessions/:id',
  })
}
function renderAssetDetail(session?: { username: string; role: 'ADMIN' | 'MEMBER' }) {
  return renderRoute(<AssetDetailView />, {
    route: `/projects/${projectId}/assets/${assetId}`,
    path: '/projects/:projectId/assets/:id',
    session,
  })
}
function renderAssetList() {
  return renderRoute(<AssetListView />, {
    route: `/projects/${projectId}/assets`,
    path: '/projects/:projectId/assets',
  })
}
function stubAsset(options: {
  role: 'ADMIN' | 'MEMBER'
  status: 'UPLOADED' | 'INGESTED'
  folders?: object[]
  memberships?: object[]
  mediaReport?: object
  quality?: object
  playback?: boolean
  mediaProbe?: boolean
  annotationTask?: boolean
}) {
  const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.endsWith(`/api/v1/projects/${projectId}`))
      return Promise.resolve(
        jsonResponse({
          project_id: projectId,
          display_name: '合成测试项目',
          status: 'ACTIVE',
          current_role: options.role,
          created_at: '2026-07-15T01:03:03Z',
        }),
      )
    if (url.endsWith('/asset-folders'))
      return Promise.resolve(jsonResponse({ items: options.folders ?? [] }))
    if (url.endsWith('/memberships'))
      return Promise.resolve(jsonResponse({ items: options.memberships ?? [] }))
    if (url.endsWith('/media-report'))
      return Promise.resolve(jsonResponse(options.mediaReport ?? verifiedMediaReport()))
    if (url.endsWith('/playback-grants') && options.playback)
      return Promise.resolve(
        jsonResponse({
          grant_id: 'b0000000-0000-4000-8000-000000000001',
          purpose: 'DATA_REVIEW',
          media_url: 'https://storage.invalid/review.mp4?signature=synthetic',
          expires_at: new Date(Date.now() + 300_000).toISOString(),
          content_type: 'video/mp4',
        }),
      )
    if (url.endsWith('/media-probe-jobs') && options.mediaProbe)
      return Promise.resolve(
        jsonResponse(
          {
            media_job_id: 'b0000000-0000-4000-8000-000000000001',
            project_id: projectId,
            clip_asset_id: assetId,
            job_type: 'MEDIA_PROBE',
            status: 'QUEUED',
          },
          202,
        ),
      )
    if (url.endsWith('/quality-review')) {
      const current = options.quality ?? unreviewedQuality()
      return Promise.resolve(
        jsonResponse(
          init?.method === 'PUT'
            ? {
                ...current,
                quality_review_id: 'c0000000-0000-4000-8000-000000000001',
                decision: 'QUALIFIED',
                reason_code: 'MEETS_REQUIREMENTS',
                reviewed_by: 'reviewer',
                reviewed_at: '2026-07-22T01:02:03Z',
              }
            : current,
        ),
      )
    }
    if (url.endsWith('/annotation-tasks') && init?.method === 'POST' && options.annotationTask)
      return Promise.resolve(
        jsonResponse(
          {
            annotation_task_id: 'e0000000-0000-4000-8000-000000000001',
            project_id: projectId,
            clip_asset_id: assetId,
            assignee_actor_id: 'local-synthetic-uploader',
            created_by: 'local-synthetic-admin',
            status: 'ASSIGNED',
            created_at: '2026-07-23T01:00:00Z',
            updated_at: '2026-07-23T01:00:00Z',
            version: 0,
          },
          201,
        ),
      )
    return Promise.resolve(jsonResponse(assetDetail(options.status)))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}
function assetDetail(status: 'UPLOADED' | 'INGESTED') {
  return {
    clip_asset_id: assetId,
    batch_id: '60000000-0000-4000-8000-000000000001',
    source_upload_file_id: '80000000-0000-4000-8000-000000000001',
    client_file_id: '90000000-0000-4000-8000-000000000001',
    content_type: 'video/mp4',
    size_bytes: 8_388_608,
    sha256: 'a'.repeat(64),
    status,
    folder_id: null,
    session_binding: null,
    created_at: '2026-07-15T01:03:03Z',
    updated_at: '2026-07-15T01:03:03Z',
  }
}
function jsonResponse(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
function unreviewedQuality() {
  return {
    quality_review_id: null,
    project_id: projectId,
    clip_asset_id: assetId,
    decision: 'UNREVIEWED',
    reason_code: null,
    reviewed_by: null,
    reviewed_at: null,
    version: 0,
  }
}
function verifiedMediaReport() {
  return {
    media_report_id: 'a0000000-0000-4000-8000-000000000001',
    media_job_id: 'b0000000-0000-4000-8000-000000000001',
    project_id: projectId,
    clip_asset_id: assetId,
    container_format: 'mov',
    duration_microseconds: 2_000_000,
    video_codec: 'h264',
    video_profile: 'High',
    width: 1280,
    height: 720,
    pixel_format: 'yuv420p',
    primary_video_bitrate_bps: 2_000_000,
    average_frame_rate_numerator: 30,
    average_frame_rate_denominator: 1,
    frame_count: 60,
    variable_frame_rate: false,
    audio_stream_count: 1,
    decode_verified: true,
    input_digest_verified: true,
    warning_codes: ['AUDIO_PRESENT'],
    created_at: '2026-07-22T01:02:03Z',
  }
}
function captureSessionDetail() {
  return {
    capture_session_id: sessionId,
    project_id: projectId,
    batch_id: '60000000-0000-4000-8000-000000000001',
    device_id: 'synthetic-edge-01',
    metadata_upload_file_id: null,
    metadata_schema_version: '1.0',
    metadata_sha256: null,
    expected_camera_count: 1,
    received_camera_count: 1,
    sync_group_id: 'sync-group-01',
    device_time_utc: '2026-07-15T01:02:03Z',
    device_timezone: 'Asia/Shanghai',
    server_receive_time: '2026-07-15T01:03:03Z',
    assembly_status: 'COMPLETE',
    quality_status: 'PENDING',
    camera_set_snapshot: {
      camera_set_snapshot_id: '70000000-0000-4000-8000-000000000001',
      source_camera_set_id: '71000000-0000-4000-8000-000000000001',
      camera_set_key: 'dispensing-cabin-a',
      configuration_version: 1,
      layout_version: 'layout-v1',
      expected_camera_count: 1,
      members: [
        {
          camera_channel_id: '72000000-0000-4000-8000-000000000001',
          camera_index: 0,
          camera_role: 'overview',
          mount_position: 'top',
          enabled: true,
        },
      ],
    },
    streams: [
      {
        camera_stream_id: '41000000-0000-4000-8000-000000000001',
        clip_asset_id: assetId,
        client_file_id: '90000000-0000-4000-8000-000000000001',
        camera_channel_id: '72000000-0000-4000-8000-000000000001',
        camera_index: 0,
        camera_role: 'overview',
        mount_position: 'top',
        record_start_utc: '2026-07-15T01:02:03Z',
        time_offset_ms: 0,
        sync_status: 'PASS',
        stream_status: 'INGESTED',
        quality_status: 'PENDING',
      },
    ],
    created_at: '2026-07-15T01:03:03Z',
  }
}
