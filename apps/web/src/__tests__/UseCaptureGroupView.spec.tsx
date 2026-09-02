import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  authorizeClipAssetPlayback,
  getClipAsset,
  getClipAssetMediaReport,
  getLatestHandPoseCandidates,
  type ClipAssetPlaybackGrant,
  type ClipAssetRead,
  type HandPoseCandidateResponse,
  type MediaReport,
} from '@/api/catalog'
import type { CaptureGroupStream } from '@/api/capture-groups'
import type { GroupViewRow } from '@/features/capture-group/capture-group-detail-model'
import { useCaptureGroupView } from '@/features/capture-group/use-capture-group-view'

vi.mock('@/api/catalog', async () => {
  const actual = await vi.importActual<typeof import('@/api/catalog')>('@/api/catalog')
  return {
    ...actual,
    authorizeClipAssetPlayback: vi.fn(),
    getClipAsset: vi.fn(),
    getClipAssetMediaReport: vi.fn(),
    getLatestHandPoseCandidates: vi.fn(),
  }
})

const projectId = 'project-1'
const firstClipAssetId = 'clip-1'
const secondClipAssetId = 'clip-2'

type CandidateJobStatus = HandPoseCandidateResponse['job']['status']
type HandResult = HandPoseCandidateResponse['job']['hand_result']

function view(clipAssetId: string, cameraNumber: number): GroupViewRow {
  const stream: CaptureGroupStream = {
    camera_stream_id: `stream-${cameraNumber}`,
    clip_asset_id: clipAssetId,
    client_file_id: `camera-${cameraNumber}.mp4`,
    camera_channel_id: `channel-${cameraNumber}`,
    camera_index: cameraNumber,
    camera_number: cameraNumber,
    camera_role: cameraNumber === 1 ? 'PRIMARY' : 'SUPPORTING',
    mount_position: 'front',
    record_start_utc: null,
    time_offset_ms: 0,
    sync_status: 'PASS',
    stream_status: 'INGESTED',
    quality_status: 'PENDING',
    enabled_for_hand_detection: true,
  }
  return { cameraNumber, member: null, stream }
}

function clipAsset(clipAssetId: string): ClipAssetRead {
  return {
    clip_asset_id: clipAssetId,
    batch_id: 'batch-1',
    source_upload_file_id: `source-${clipAssetId}`,
    client_file_id: `${clipAssetId}.mp4`,
    content_type: 'video/mp4',
    size_bytes: 1024,
    sha256: 'a'.repeat(64),
    status: 'INGESTED',
    session_binding: null,
    created_at: '2026-09-01T00:00:00Z',
    updated_at: '2026-09-01T00:00:00Z',
  }
}

function mediaReport(clipAssetId: string): MediaReport {
  return {
    media_report_id: `report-${clipAssetId}`,
    media_job_id: `media-job-${clipAssetId}`,
    project_id: projectId,
    clip_asset_id: clipAssetId,
    container_format: 'mp4',
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
    audio_stream_count: 0,
    decode_verified: true,
    input_digest_verified: true,
    warning_codes: [],
    created_at: '2026-09-01T00:00:00Z',
  }
}

function candidateResponse(
  clipAssetId: string,
  status: CandidateJobStatus,
  handResult: HandResult = 'UNKNOWN',
): HandPoseCandidateResponse {
  return {
    job: {
      ai_job_id: `ai-job-${clipAssetId}`,
      project_id: projectId,
      clip_asset_id: clipAssetId,
      algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
      algorithm_version: 'v1',
      model_sha256: 'b'.repeat(64),
      input_sha256: 'c'.repeat(64),
      input_size_bytes: 1024,
      sampling_fps: 10,
      profile_sha256: 'd'.repeat(64),
      status,
      hand_result: handResult,
      run_summary: null,
      requested_by: 'system',
      created_at: '2026-09-01T00:00:00Z',
      updated_at: '2026-09-01T00:00:00Z',
    },
    candidates: [],
  }
}

function playbackGrant(): ClipAssetPlaybackGrant {
  return {
    grant_id: 'grant-1',
    purpose: 'DATA_REVIEW',
    media_url: '/synthetic/video.mp4',
    expires_at: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
    content_type: 'video/mp4',
  }
}

async function settleInitialView() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
}

function latestCandidateMock() {
  return vi.mocked(getLatestHandPoseCandidates)
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  vi.mocked(getClipAsset).mockImplementation(async (_projectId, clipAssetId) =>
    clipAsset(clipAssetId),
  )
  vi.mocked(getClipAssetMediaReport).mockImplementation(async (_projectId, clipAssetId) =>
    mediaReport(clipAssetId),
  )
  vi.mocked(authorizeClipAssetPlayback).mockResolvedValue(playbackGrant())
})

afterEach(() => {
  vi.useRealTimers()
})

describe('useCaptureGroupView MediaPipe refresh', () => {
  it('polls a queued job and continues while it is running', async () => {
    latestCandidateMock()
      .mockResolvedValueOnce(candidateResponse(firstClipAssetId, 'QUEUED'))
      .mockResolvedValueOnce(candidateResponse(firstClipAssetId, 'RUNNING'))
      .mockResolvedValueOnce(candidateResponse(firstClipAssetId, 'RUNNING'))

    const { result } = renderHook(() => useCaptureGroupView(projectId, view(firstClipAssetId, 1)))
    await settleInitialView()

    expect(result.current.candidateLiveStatus).toBe('处理中')
    expect(latestCandidateMock()).toHaveBeenCalledTimes(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(4_000)
    })
    expect(latestCandidateMock()).toHaveBeenCalledTimes(2)
    expect(result.current.candidateResponse?.job.status).toBe('RUNNING')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(4_000)
    })
    expect(latestCandidateMock()).toHaveBeenCalledTimes(3)
  })

  it('does not start another poll until the previous request settles', async () => {
    let resolveSecond: (response: HandPoseCandidateResponse) => void = () => undefined
    const secondResponse = new Promise<HandPoseCandidateResponse>((resolve) => {
      resolveSecond = resolve
    })
    latestCandidateMock()
      .mockResolvedValueOnce(candidateResponse(firstClipAssetId, 'RUNNING'))
      .mockReturnValueOnce(secondResponse)
      .mockResolvedValueOnce(candidateResponse(firstClipAssetId, 'SUCCEEDED', 'HAND_DETECTED'))

    const { result } = renderHook(() => useCaptureGroupView(projectId, view(firstClipAssetId, 1)))
    await settleInitialView()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(4_000)
    })
    expect(latestCandidateMock()).toHaveBeenCalledTimes(2)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(8_000)
    })
    expect(latestCandidateMock()).toHaveBeenCalledTimes(2)

    resolveSecond(candidateResponse(firstClipAssetId, 'RUNNING'))
    await act(async () => {
      await Promise.resolve()
    })
    expect(result.current.candidateLiveStatus).toBe('处理中')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(4_000)
    })
    expect(latestCandidateMock()).toHaveBeenCalledTimes(3)
  })

  it.each([
    ['HAND_DETECTED', '已完成 · 检测到手部'],
    ['NO_HAND_DETECTED', '已完成 · 未检测到手部'],
  ] as const)(
    'updates the visible status and stops after %s succeeds',
    async (handResult, label) => {
      latestCandidateMock()
        .mockResolvedValueOnce(candidateResponse(firstClipAssetId, 'RUNNING'))
        .mockResolvedValueOnce(candidateResponse(firstClipAssetId, 'SUCCEEDED', handResult))

      const { result } = renderHook(() => useCaptureGroupView(projectId, view(firstClipAssetId, 1)))
      await settleInitialView()
      expect(result.current.candidateLiveStatus).toBe('处理中')

      await act(async () => {
        await vi.advanceTimersByTimeAsync(4_000)
      })

      expect(result.current.candidateLiveStatus).toBe(label)
      expect(latestCandidateMock()).toHaveBeenCalledTimes(2)

      await act(async () => {
        await vi.advanceTimersByTimeAsync(8_000)
      })
      expect(latestCandidateMock()).toHaveBeenCalledTimes(2)
    },
  )

  it('updates the visible status and stops after a job fails', async () => {
    latestCandidateMock()
      .mockResolvedValueOnce(candidateResponse(firstClipAssetId, 'RUNNING'))
      .mockResolvedValueOnce(candidateResponse(firstClipAssetId, 'FAILED'))

    const { result } = renderHook(() => useCaptureGroupView(projectId, view(firstClipAssetId, 1)))
    await settleInitialView()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(4_000)
    })

    expect(result.current.candidateLiveStatus).toBe('失败')
    expect(latestCandidateMock()).toHaveBeenCalledTimes(2)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(8_000)
    })
    expect(latestCandidateMock()).toHaveBeenCalledTimes(2)
  })

  it('aborts the old request and ignores its response when the view changes', async () => {
    let resolveFirst: (response: HandPoseCandidateResponse) => void = () => undefined
    let firstSignal: AbortSignal | undefined
    const firstResponse = new Promise<HandPoseCandidateResponse>((resolve) => {
      resolveFirst = resolve
    })
    latestCandidateMock().mockImplementation((_projectId, clipAssetId, signal) => {
      if (clipAssetId === firstClipAssetId) {
        firstSignal = signal
        return firstResponse
      }
      return Promise.resolve(candidateResponse(secondClipAssetId, 'SUCCEEDED', 'HAND_DETECTED'))
    })

    const { result, rerender } = renderHook(
      ({ selectedView }: { selectedView: GroupViewRow }) =>
        useCaptureGroupView(projectId, selectedView),
      { initialProps: { selectedView: view(firstClipAssetId, 1) } },
    )
    await settleInitialView()
    expect(firstSignal).toBeDefined()

    await act(async () => {
      rerender({ selectedView: view(secondClipAssetId, 2) })
    })
    await settleInitialView()

    expect(firstSignal?.aborted).toBe(true)
    expect(result.current.selectedDetail?.clip_asset_id).toBe(secondClipAssetId)
    expect(result.current.candidateResponse?.job.clip_asset_id).toBe(secondClipAssetId)

    resolveFirst(candidateResponse(firstClipAssetId, 'SUCCEEDED', 'NO_HAND_DETECTED'))
    await act(async () => {
      await Promise.resolve()
    })
    expect(result.current.candidateResponse?.job.clip_asset_id).toBe(secondClipAssetId)
  })

  it('keeps playback state unchanged while polling', async () => {
    const grant = playbackGrant()
    latestCandidateMock()
      .mockResolvedValueOnce(candidateResponse(firstClipAssetId, 'RUNNING'))
      .mockResolvedValueOnce(candidateResponse(firstClipAssetId, 'SUCCEEDED', 'HAND_DETECTED'))
    vi.mocked(authorizeClipAssetPlayback).mockResolvedValue(grant)

    const { result } = renderHook(() => useCaptureGroupView(projectId, view(firstClipAssetId, 1)))
    await settleInitialView()

    await act(async () => {
      await result.current.startPlayback()
    })
    expect(result.current.playbackGrant).toBe(grant)
    expect(result.current.playbackState).toBe('waiting')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(4_000)
    })

    expect(result.current.playbackGrant).toBe(grant)
    expect(result.current.playbackState).toBe('waiting')
  })

  it('cleans up the polling timer and request on unmount', async () => {
    latestCandidateMock().mockResolvedValue(candidateResponse(firstClipAssetId, 'RUNNING'))
    const { unmount } = renderHook(() => useCaptureGroupView(projectId, view(firstClipAssetId, 1)))
    await settleInitialView()
    expect(latestCandidateMock()).toHaveBeenCalledTimes(1)

    unmount()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4_000)
    })

    expect(latestCandidateMock()).toHaveBeenCalledTimes(1)
  })

  it('aborts an in-flight candidate request on unmount', async () => {
    let requestSignal: AbortSignal | undefined
    const pending = new Promise<HandPoseCandidateResponse>(() => undefined)
    latestCandidateMock().mockImplementation((_projectId, _clipAssetId, signal) => {
      requestSignal = signal
      return pending
    })

    const { unmount } = renderHook(() => useCaptureGroupView(projectId, view(firstClipAssetId, 1)))
    await settleInitialView()
    expect(requestSignal).toBeDefined()

    unmount()

    expect(requestSignal?.aborted).toBe(true)
  })
})
