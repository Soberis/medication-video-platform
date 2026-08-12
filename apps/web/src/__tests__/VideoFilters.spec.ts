import { describe, expect, it } from 'vitest'

import {
  parseVideoFilters,
  replaceVideoFiltersInSearchParams,
  serializeVideoFilters,
} from '@/catalog-video-filters'

describe('video filter URL state', () => {
  it('round-trips supported enums, positive camera numbers, and ISO instants', () => {
    const filters = parseVideoFilters(
      new URLSearchParams(
        'source=HOSPITAL_REMOTE&cameraNumber=3&capturedFrom=2026-08-11T02%3A00%3A00Z&capturedBefore=2026-08-11T03%3A00%3A00%2B08%3A00&mediaStatus=INGESTED&handStatus=SUCCEEDED&handResult=HAND_DETECTED&annotationStatus=SUBMITTED&cleaningStatus=WARNING',
      ),
    )

    expect(filters).toEqual({
      source: 'HOSPITAL_REMOTE',
      cameraNumber: 3,
      capturedFrom: '2026-08-11T02:00:00Z',
      capturedBefore: '2026-08-11T03:00:00+08:00',
      mediaStatus: 'INGESTED',
      handStatus: 'SUCCEEDED',
      handResult: 'HAND_DETECTED',
      annotationStatus: 'SUBMITTED',
      cleaningStatus: 'WARNING',
    })

    expect(serializeVideoFilters(filters).toString()).toBe(
      'source=HOSPITAL_REMOTE&mediaStatus=INGESTED&handStatus=SUCCEEDED&handResult=HAND_DETECTED&annotationStatus=SUBMITTED&cleaningStatus=WARNING&cameraNumber=3&capturedFrom=2026-08-11T02%3A00%3A00Z&capturedBefore=2026-08-11T03%3A00%3A00%2B08%3A00',
    )
  })

  it('ignores unknown enums, non-positive/non-numeric cameras, and invalid instants', () => {
    for (const cameraNumber of ['0', '-1', 'abc']) {
      expect(parseVideoFilters(new URLSearchParams(`cameraNumber=${cameraNumber}`))).toEqual({})
    }

    expect(
      parseVideoFilters(
        new URLSearchParams(
          'source=UNKNOWN&handStatus=INVALID&capturedFrom=2026-08-11T02%3A00%3A00&capturedBefore=not-a-time',
        ),
      ),
    ).toEqual({})
  })

  it('removes only video filter params while preserving unrelated route params', () => {
    const next = replaceVideoFiltersInSearchParams(
      new URLSearchParams(
        'tab=quality&source=PLATFORM_LOCAL&cameraNumber=3&unknown=keep',
      ),
      {},
    )

    expect(next.toString()).toBe('tab=quality&unknown=keep')
  })
})
