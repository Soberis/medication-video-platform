import { afterEach, describe, expect, it, vi } from 'vitest'

import { createProject } from '@/api/projects'

afterEach(() => vi.unstubAllGlobals())

describe('projects API', () => {
  it('creates the first project through the formal project endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        project_id: '30000000-0000-4000-8000-000000000001',
        display_name: '合成测试项目',
        status: 'ACTIVE',
        current_role: 'ADMIN',
        created_at: '2026-07-21T00:00:00Z',
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('project-idempotency-key-0001') })

    await createProject('合成测试项目')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/projects',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ display_name: '合成测试项目' }),
        headers: expect.objectContaining({
          'Idempotency-Key': 'project-idempotency-key-0001',
        }),
      }),
    )
  })
})

function jsonResponse(body: object) {
  return new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
  })
}
