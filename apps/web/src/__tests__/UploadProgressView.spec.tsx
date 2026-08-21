import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import UploadProgressView from '@/views/UploadProgressView'

const batchId = '40000000-0000-4000-8000-000000000001'
const batchProgress = {
  batch_id: batchId,
  project_id: '00000000-0000-4000-8000-000000000001',
  device_id: 'synthetic-device-01',
  file_count: 1,
  registered_file_count: 1,
  uploaded_file_count: 0,
  failed_file_count: 1,
  total_size_bytes: 8_388_608,
  uploaded_size_bytes: 8_388_608,
  tracked_part_count: 1,
  uploaded_part_count: 1,
  status: 'UPLOADING',
  expires_at: '2026-07-16T06:00:00Z',
  created_at: '2026-07-15T06:00:00Z',
}
const fileProgress = {
  batch_id: batchId,
  registered_file_count: 1,
  files: [
    {
      upload_file_id: '50000000-0000-4000-8000-000000000001',
      batch_id: batchId,
      client_file_id: '60000000-0000-4000-8000-000000000001',
      file_kind: 'VIDEO',
      content_type: 'video/mp4',
      size_bytes: 8_388_608,
      declared_sha256: 'a'.repeat(64),
      verified_sha256: 'b'.repeat(64),
      total_parts: 1,
      tracked_parts: 1,
      uploaded_parts: 1,
      uploaded_size_bytes: 8_388_608,
      status: 'FAILED',
      completion_error_code: 'UPLOAD_FILE_SHA256_MISMATCH',
      multipart_created_at: '2026-07-15T06:01:00Z',
      completion_started_at: '2026-07-15T06:02:00Z',
      completed_at: '2026-07-15T06:03:00Z',
      created_at: '2026-07-15T06:00:30Z',
      updated_at: '2026-07-15T06:03:00Z',
    },
  ],
}

afterEach(() => vi.unstubAllGlobals())

describe('UploadProgressView', () => {
  it('validates the batch UUID before calling the API', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    render(<UploadProgressView />)
    await user.type(screen.getByLabelText('上传批次 ID'), 'not-a-uuid')
    await user.click(screen.getByRole('button', { name: '查询进度' }))
    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByText('INVALID_BATCH_ID')).toBeInTheDocument()
    expect(screen.getByText(/请输入有效的上传批次 UUID/)).toBeInTheDocument()
  })

  it('renders batch, file, Part and stable error progress', async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation((input: RequestInfo | URL) =>
        Promise.resolve(
          jsonResponse(String(input).endsWith('/files') ? fileProgress : batchProgress),
        ),
      )
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    render(<UploadProgressView />)
    await user.type(screen.getByLabelText('上传批次 ID'), batchId)
    await user.click(screen.getByRole('button', { name: '查询进度' }))
    expect(await screen.findByText('synthetic-device-01')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(screen.getByText(/已确认 1 \/ 1 个分片/)).toBeInTheDocument()
    expect(screen.getByText('UPLOAD_FILE_SHA256_MISMATCH')).toBeInTheDocument()
    expect(screen.getByText('校验失败')).toBeInTheDocument()
  })

  it('shows a stable backend error without leaking internal details', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(() =>
        Promise.resolve(
          jsonResponse(
            {
              error_code: 'UPLOAD_BATCH_NOT_FOUND',
              error_message: 'The upload batch was not found.',
              retryable: false,
              request_id: 'synthetic-request-id',
            },
            404,
          ),
        ),
      ),
    )
    const user = userEvent.setup()
    render(<UploadProgressView />)
    await user.type(screen.getByLabelText('上传批次 ID'), batchId)
    await user.click(screen.getByRole('button', { name: '查询进度' }))
    expect(await screen.findByText('UPLOAD_BATCH_NOT_FOUND')).toBeInTheDocument()
    expect(screen.getByText('The upload batch was not found.')).toBeInTheDocument()
    expect(screen.queryByText('synthetic-request-id')).not.toBeInTheDocument()
  })
})

function jsonResponse(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}
