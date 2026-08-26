import { describe, expect, it } from 'vitest'

import { CatalogApiError } from '@/api/catalog'
import { classifyCandidateReadError } from '@/features/catalog/hand-pose-candidate-state'

describe('hand-pose candidate read state', () => {
  it('treats only the authoritative missing-job error as unavailable', () => {
    expect(
      classifyCandidateReadError(
        new CatalogApiError('没有检测任务', 'AI_HAND_POSE_JOB_NOT_FOUND', false, null, 404),
      ),
    ).toBe('unavailable')
    expect(
      classifyCandidateReadError(
        new CatalogApiError('视频不存在', 'CLIP_ASSET_NOT_FOUND', false, null, 404),
      ),
    ).toBe('error')
  })

  it('treats server and network failures as read errors', () => {
    expect(
      classifyCandidateReadError(
        new CatalogApiError('服务暂时不可用', 'CATALOG_REQUEST_FAILED', true, null, 503),
      ),
    ).toBe('error')
    expect(classifyCandidateReadError(new TypeError('Failed to fetch'))).toBe('error')
  })
})
