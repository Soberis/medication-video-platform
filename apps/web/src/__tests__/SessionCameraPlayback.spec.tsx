import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { CameraSetSnapshotMember, CameraStream, HandPoseJob } from '@/api/catalog'
import { SessionCameraPlayback } from '@/components/SessionCameraPlayback'
import { renderRoute } from '@/test/render'

const projectId = '30000000-0000-4000-8000-000000000001'
const clipAssetId = '50000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('SessionCameraPlayback', () => {
  it('preserves sparse fixed-camera numbers and marks missing routes explicitly', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ error_code: 'AI_HAND_POSE_JOB_NOT_FOUND' }), {
          status: 404,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )

    renderRoute(
      <SessionCameraPlayback
        expectedCameras={[camera(0), camera(1), camera(2), camera(3), camera(4)]}
        projectId={projectId}
        streams={[stream('INGESTED', 0), stream('INGESTED', 2), stream('INGESTED', 4)]}
      />,
    )

    expect(await screen.findByRole('button', { name: '相机 1' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '相机 2 缺失' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '相机 3' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '相机 4 缺失' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '相机 5' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '相机 2' })).not.toBeInTheDocument()
  })

  it('does not allow hand detection before the selected camera video is ingested', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ error_code: 'AI_HAND_POSE_JOB_NOT_FOUND' }), {
        status: 404,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderRoute(<SessionCameraPlayback projectId={projectId} streams={[stream('BOUND')]} />)

    const button = await screen.findByRole('button', { name: '检测相机 1 的手部姿态' })
    expect(button).toBeDisabled()
    await user.click(button)

    expect(
      fetchMock.mock.calls.some(
        ([input, init]) =>
          String(input).includes('/hand-pose-detections') && init?.method === 'POST',
      ),
    ).toBe(false)
  })

  it('uses the retry endpoint for a retryable failed attempt and does not create a new job', async () => {
    const failedJob = handPoseJob({
      status: 'FAILED',
      hand_result: 'UNKNOWN',
      run_summary: {
        ...runSummary(),
        retryable: true,
        failure_reason: 'AI_HAND_POSE_INFERENCE_FAILED',
      },
    })
    const queuedJob = handPoseJob({ status: 'QUEUED', hand_result: 'UNKNOWN', run_summary: null })
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') return Promise.resolve(jsonResponse(queuedJob, 202))
      return Promise.resolve(jsonResponse({ job: failedJob, candidates: [] }))
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('retry-ui-key-0001') })
    const user = userEvent.setup()

    renderRoute(<SessionCameraPlayback projectId={projectId} streams={[stream('INGESTED')]} />)

    await user.click(await screen.findByRole('button', { name: '重试相机 1 的检测' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/videos/${clipAssetId}/hand-pose-detections/retry`,
        expect.objectContaining({
          method: 'POST',
          headers: expect.objectContaining({ 'Idempotency-Key': 'retry-ui-key-0001' }),
        }),
      ),
    )
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) =>
          String(input).endsWith(`/videos/${clipAssetId}/hand-pose-detections`) &&
          init?.method === 'POST',
      ),
    ).toBe(false)
    expect(await screen.findByText('手部检测：正在检测，页面会自动更新。')).toBeInTheDocument()
  })
})

function stream(streamStatus: CameraStream['stream_status'], cameraIndex = 0): CameraStream {
  return {
    camera_stream_id: `41000000-0000-4000-8000-${String(cameraIndex + 1).padStart(12, '0')}`,
    clip_asset_id: `50000000-0000-4000-8000-${String(cameraIndex + 1).padStart(12, '0')}`,
    client_file_id: `90000000-0000-4000-8000-${String(cameraIndex + 1).padStart(12, '0')}`,
    camera_channel_id: `72000000-0000-4000-8000-${String(cameraIndex + 1).padStart(12, '0')}`,
    camera_index: cameraIndex,
    camera_role: 'overview',
    mount_position: 'top',
    record_start_utc: '2026-08-11T02:00:00Z',
    time_offset_ms: 0,
    sync_status: 'PASS',
    stream_status: streamStatus,
    quality_status: 'PASS',
  }
}

function camera(cameraIndex: number): CameraSetSnapshotMember {
  return {
    camera_channel_id: `72000000-0000-4000-8000-${String(cameraIndex + 1).padStart(12, '0')}`,
    camera_index: cameraIndex,
    camera_role: 'overview',
    mount_position: `position-${cameraIndex + 1}`,
    enabled: true,
    enabled_for_hand_detection: true,
  }
}

function handPoseJob(overrides: Partial<HandPoseJob> = {}): HandPoseJob {
  return {
    ai_job_id: 'b1000000-0000-4000-8000-000000000001',
    project_id: projectId,
    clip_asset_id: clipAssetId,
    algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
    algorithm_version: 'v1',
    model_sha256: 'a'.repeat(64),
    input_sha256: 'b'.repeat(64),
    input_size_bytes: 8_388_608,
    sampling_fps: 10,
    profile_sha256: 'c'.repeat(64),
    status: 'QUEUED',
    hand_result: 'UNKNOWN',
    run_summary: null,
    requested_by: 'local-synthetic-admin',
    created_at: '2026-08-11T02:00:00Z',
    updated_at: '2026-08-11T02:00:00Z',
    ...overrides,
  }
}

function runSummary() {
  return {
    analysis_complete: false,
    hand_result: 'UNKNOWN' as const,
    total_video_frames: 10,
    inference_frame_count: 1,
    frames_with_hand: 0,
    frames_without_hand: 1,
    left_hand_detection_count: 0,
    right_hand_detection_count: 0,
    invalid_landmark_count: 0,
    processing_duration_ms: 10,
    started_at: '2026-08-11T02:00:00Z',
    completed_at: '2026-08-11T02:00:01Z',
    failure_reason: null as string | null,
    retryable: false,
  }
}

function jsonResponse(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
