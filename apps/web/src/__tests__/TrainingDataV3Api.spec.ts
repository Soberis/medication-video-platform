import { afterEach, describe, expect, it, vi } from 'vitest'

import { downloadTrainingDataV3, getTrainingDataV3ExportPreview } from '@/api/training-data'

const projectId = '30000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('training-data.v3 export API', () => {
  it('reads the server-owned project manifest preview', async () => {
    const preview = {
      schema_version: 'training-data.v3',
      eligible_group_count: 2,
      event_count: 2,
      file_name: 'training_data_v3.json',
      content_type: 'application/vnd.medication.training-data.v3+json',
      split_unit: 'capture_group',
      includes_mp4: false,
    } as const
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(preview))
    vi.stubGlobal('fetch', fetchMock)

    await expect(getTrainingDataV3ExportPreview(projectId)).resolves.toEqual(preview)
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/training-data/v3/preview`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })

  it('downloads the project manifest without inventing a client-side snapshot id', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response('{"schema_version":"training-data.v3"}'))
    vi.stubGlobal('fetch', fetchMock)

    const blob = await downloadTrainingDataV3(projectId)
    expect(await blob.text()).toContain('training-data.v3')
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/training-data/v3/download`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })
})

function jsonResponse(body: object) {
  return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
}
