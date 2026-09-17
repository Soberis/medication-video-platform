import { describe, expect, it } from 'vitest'

import type { HandPoseCandidateResponse } from '@/api/catalog'
import { CatalogApiError } from '@/api/catalog'
import { classifyCandidateReadError } from '@/features/catalog/hand-pose-candidate-state'
import {
  candidateDescription,
  candidateLiveStatusLabel,
} from '@/features/capture-group/capture-group-detail-model'

function response(
  status: HandPoseCandidateResponse['job']['status'],
  handResult: HandPoseCandidateResponse['job']['hand_result'] = 'UNKNOWN',
  failureReason: string | null = null,
): HandPoseCandidateResponse {
  return {
    job: {
      ai_job_id: 'ai-job',
      project_id: 'project',
      clip_asset_id: 'clip-asset',
      algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
      algorithm_version: 'v1',
      model_sha256: 'a'.repeat(64),
      input_sha256: 'b'.repeat(64),
      input_size_bytes: 1,
      sampling_fps: 10,
      profile_sha256: 'c'.repeat(64),
      status,
      hand_result: handResult,
      run_summary: failureReason
        ? {
            analysis_complete: false,
            hand_result: 'UNKNOWN',
            total_video_frames: 0,
            inference_frame_count: 0,
            frames_with_hand: 0,
            frames_without_hand: 0,
            left_hand_detection_count: 0,
            right_hand_detection_count: 0,
            invalid_landmark_count: 0,
            processing_duration_ms: 0,
            started_at: '2026-01-01T00:00:00Z',
            completed_at: '2026-01-01T00:00:00Z',
            failure_reason: failureReason,
            retryable: true,
          }
        : null,
      requested_by: 'system',
      created_at: '2026-01-01T00:00:00Z',
      updated_at: '2026-01-01T00:00:00Z',
    },
    candidates: [],
  }
}

describe('capture group Candidate status', () => {
  it('keeps API read error separate from a failed MediaPipe job', () => {
    expect(candidateLiveStatusLabel('error', null)).toBe('读取失败')
    expect(candidateDescription('error', null)).toContain('检测结果读取失败')
    expect(candidateDescription('error', null)).not.toContain('检测任务失败')

    const failedJob = response('FAILED', 'UNKNOWN', 'AI_HAND_POSE_INFERENCE_FAILED')
    expect(candidateLiveStatusLabel('success', failedJob)).toBe('检测任务失败')
    expect(candidateDescription('success', failedJob)).toContain(
      '失败原因：AI_HAND_POSE_INFERENCE_FAILED',
    )
  })

  it('treats a missing job as unavailable, not failed', () => {
    const missingJob = new CatalogApiError(
      '没有检测任务',
      'AI_HAND_POSE_JOB_NOT_FOUND',
      false,
      null,
      404,
    )

    expect(classifyCandidateReadError(missingJob)).toBe('unavailable')
    expect(candidateLiveStatusLabel('unavailable', null)).toBe('暂不可用')
    expect(candidateDescription('unavailable', null)).toContain('暂无手部检测结果')
    expect(candidateDescription('unavailable', null)).not.toContain('失败')
  })

  it.each([
    ['idle', null, '未读取'],
    ['loading', null, '读取中'],
    ['success', response('QUEUED'), '等待检测'],
    ['success', response('RUNNING'), '检测中'],
    ['success', response('SUCCEEDED', 'HAND_DETECTED'), '已完成 · 检测到手部'],
    ['success', response('SUCCEEDED', 'NO_HAND_DETECTED'), '已完成 · 未检测到手部'],
    ['success', response('SUCCEEDED', 'UNKNOWN'), '已完成 · 结果未知'],
  ] as const)('maps %s to %s', (state, candidate, expected) => {
    expect(candidateLiveStatusLabel(state, candidate)).toBe(expected)
  })
})
