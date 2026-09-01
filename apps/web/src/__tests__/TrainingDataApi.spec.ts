import { afterEach, describe, expect, it, vi } from 'vitest'

import { downloadTrainingDataV3, getTrainingDataV3ExportPreview } from '@/api/training-data'

const projectId = '30000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('training data export API', () => {
  it('reads the V1.27 training-data.v3 preview from the existing project endpoint', async () => {
    const preview = {
      schema_version: 'training-data.v3',
      eligible_group_count: 2,
      event_count: 2,
      file_name: 'training_data_v3.json',
      content_type: 'application/vnd.medication.training-data.v3+json',
      split_unit: 'capture_group',
      includes_mp4: false,
    }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(preview))
    vi.stubGlobal('fetch', fetchMock)

    await expect(getTrainingDataV3ExportPreview(projectId)).resolves.toEqual(preview)

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/training-data/v3/preview`,
      expect.objectContaining({
        method: 'GET',
        headers: { Accept: 'application/json' },
        cache: 'no-store',
      }),
    )
  })

  it('downloads the V1.27 manifest through the v3 endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('v3-json'))
    vi.stubGlobal('fetch', fetchMock)

    const v3Blob = await downloadTrainingDataV3(projectId)
    expect(v3Blob.size).toBe(7)

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/training-data/v3/download`,
      expect.objectContaining({
        method: 'GET',
        headers: { Accept: 'application/vnd.medication.training-data.v3+json' },
        cache: 'no-store',
      }),
    )
  })

  it('maps a rejected v3 preview to safe Chinese feedback', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          error_code: 'TRAINING_DATA_V3_EVIDENCE_INVALID',
          error_message: 'internal stack and storage://secret',
        },
        { status: 422 },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(getTrainingDataV3ExportPreview(projectId)).rejects.toMatchObject({
      errorCode: 'TRAINING_DATA_V3_EVIDENCE_INVALID',
      message: '当前配药事件导出证据不完整，暂时无法导出。',
    })
  })
})

function jsonResponse(body: object, init?: ResponseInit) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
}
