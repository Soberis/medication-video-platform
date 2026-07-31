import { afterEach, describe, expect, it, vi } from 'vitest'

import { clearDatasetDraftIntent, getDatasetDraftIntent } from '@/app/dataset-draft-intent'

const projectId = '30000000-0000-4000-8000-000000000001'
const firstSessionId = '40000000-0000-4000-8000-000000000001'
const secondSessionId = '40000000-0000-4000-8000-000000000002'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

describe('dataset draft intent', () => {
  it('reuses a pending key for the exact ordered draft request and clears it only after settlement', () => {
    vi.stubGlobal('crypto', {
      randomUUID: vi
        .fn()
        .mockReturnValueOnce('draft-key-0001')
        .mockReturnValueOnce('draft-key-0002'),
    })

    const first = getDatasetDraftIntent(projectId, '候选草稿', [firstSessionId, secondSessionId])
    const retry = getDatasetDraftIntent(projectId, '候选草稿', [firstSessionId, secondSessionId])

    expect(retry.idempotencyKey).toBe('draft-key-0001')
    clearDatasetDraftIntent(first)

    expect(
      getDatasetDraftIntent(projectId, '候选草稿', [firstSessionId, secondSessionId])
        .idempotencyKey,
    ).toBe('draft-key-0002')
  })

  it('uses a different key when ordered candidates change', () => {
    vi.stubGlobal('crypto', {
      randomUUID: vi
        .fn()
        .mockReturnValueOnce('draft-key-0001')
        .mockReturnValueOnce('draft-key-0002'),
    })

    const original = getDatasetDraftIntent(projectId, '候选草稿', [firstSessionId, secondSessionId])
    const reordered = getDatasetDraftIntent(projectId, '候选草稿', [
      secondSessionId,
      firstSessionId,
    ])

    expect(original.idempotencyKey).toBe('draft-key-0001')
    expect(reordered.idempotencyKey).toBe('draft-key-0002')
  })
})
