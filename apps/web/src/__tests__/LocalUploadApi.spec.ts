import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/upload/hash-file', () => ({
  hashFileInWorker: vi.fn(),
}))

import { calculateManifestSha256, uploadPlatformLocalVideo } from '@/api/local-upload'
import { hashFileInWorker } from '@/upload/hash-file'

const projectId = '30000000-0000-4000-8000-000000000001'
const batchId = '40000000-0000-4000-8000-000000000001'
const clientFileId = '50000000-0000-4000-8000-000000000001'

class FakeXmlHttpRequest {
  static instances: FakeXmlHttpRequest[] = []

  status = 200
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  onabort: (() => void) | null = null
  readonly upload: { onprogress: ((event: ProgressEvent) => void) | null } = { onprogress: null }
  method = ''
  url = ''

  constructor() {
    FakeXmlHttpRequest.instances.push(this)
  }

  open(method: string, url: string) {
    this.method = method
    this.url = url
  }

  send(body: Blob) {
    this.upload.onprogress?.({ lengthComputable: true, loaded: body.size } as ProgressEvent)
    this.onload?.()
  }

  abort() {
    this.onabort?.()
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
  FakeXmlHttpRequest.instances = []
})

describe('platform-local upload API', () => {
  it('uses one platform-local batch and the existing multipart protocol for one video', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ batch_id: batchId }))
      .mockResolvedValueOnce(jsonResponse({}))
      .mockResolvedValueOnce(jsonResponse({ total_parts: 1 }))
      .mockResolvedValueOnce(jsonResponse({ upload_url: 'http://127.0.0.1:9000/signed-upload-url' }))
      .mockResolvedValueOnce(jsonResponse({}))
      .mockResolvedValueOnce(jsonResponse({}))
      .mockResolvedValueOnce(jsonResponse({ clip_assets: [{ clip_asset_id: '80000000-0000-4000-8000-000000000001' }] }))
      .mockResolvedValueOnce(jsonResponse({ capture_session_id: '90000000-0000-4000-8000-000000000001' }))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('XMLHttpRequest', FakeXmlHttpRequest)
    const identifiers = [
      clientFileId,
      '60000000-0000-4000-8000-000000000001',
      '70000000-0000-4000-8000-000000000001',
    ]
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn().mockImplementation(() => identifiers.shift() ?? 'a0000000-0000-4000-8000-000000000001'),
    })
    vi.mocked(hashFileInWorker).mockImplementation(async (_file, _partSize, onProgress) => {
      onProgress(12, 12)
      return { sha256: 'a'.repeat(64), partSha256: ['b'.repeat(64)] }
    })
    const progress = vi.fn()
    const file = new File(['synthetic-v'], 'synthetic.mp4', { type: 'video/mp4' })

    const result = await uploadPlatformLocalVideo({ projectId, assetFolderId: null, file }, progress)

    expect(result).toEqual({
      batchId,
      captureSessionId: '90000000-0000-4000-8000-000000000001',
      clipAssetId: '80000000-0000-4000-8000-000000000001',
    })
    expect(fetchMock).toHaveBeenCalledTimes(8)
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/v1/upload/init', expect.objectContaining({ method: 'POST' }))
    expect(JSON.parse(fetchMock.mock.calls[0]?.[1].body as string)).toMatchObject({
      project_id: projectId,
      device_id: 'PLATFORM_LOCAL',
      upload_source_type: 'PLATFORM_LOCAL',
      file_count: 1,
    })
    expect(fetchMock).toHaveBeenNthCalledWith(
      4,
      `/api/v1/upload/${batchId}/files/${clientFileId}/parts/1/authorization`,
      expect.objectContaining({ method: 'POST' }),
    )
    expect(fetchMock).toHaveBeenLastCalledWith(
      `/api/v1/upload/${batchId}/capture-session`,
      expect.objectContaining({ method: 'PUT' }),
    )
    expect(FakeXmlHttpRequest.instances).toHaveLength(1)
    expect(FakeXmlHttpRequest.instances[0]).toMatchObject({ method: 'PUT', url: 'http://127.0.0.1:9000/signed-upload-url' })
    expect(progress).toHaveBeenLastCalledWith(expect.objectContaining({ phase: 'COMPLETED', batchId }))
  })

  it('uses the documented one-file manifest canonical form', () => {
    expect(
      calculateManifestSha256({
        clientFileId,
        contentType: 'video/mp4',
        sizeBytes: 12,
        sha256: 'a'.repeat(64),
        partSizeBytes: 8_388_608,
      }),
    ).toBe('a9312a70562c4d83e9af4a14b445fa3d9365c8fbc5012fd5f6319df8340b0daf')
  })
})

function jsonResponse(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}
