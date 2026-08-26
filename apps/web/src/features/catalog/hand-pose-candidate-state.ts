import { CatalogApiError } from '@/api/catalog'

export type CandidateLoadState = 'idle' | 'loading' | 'success' | 'unavailable' | 'error'

export function classifyCandidateReadError(error: unknown): 'unavailable' | 'error' {
  return error instanceof CatalogApiError && error.errorCode === 'AI_HAND_POSE_JOB_NOT_FOUND'
    ? 'unavailable'
    : 'error'
}
