import { afterEach, describe, expect, it, vi } from 'vitest'

import { archiveProject, createProject, renameProject } from '@/api/projects'

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

  it('renames and archives a project through explicit project-scoped commands', async () => {
    const projectId = '30000000-0000-4000-8000-000000000001'
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({
        project_id: projectId,
        display_name: '重命名后的项目',
        status: 'ACTIVE',
        current_role: 'ADMIN',
        created_at: '2026-07-21T00:00:00Z',
      }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('project-command-idempotency-key-0001') })

    await expect(renameProject(projectId, '重命名后的项目')).resolves.toMatchObject({
      display_name: '重命名后的项目',
    })
    await expect(archiveProject(projectId)).resolves.toBeUndefined()

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      `/api/v1/projects/${projectId}`,
      expect.objectContaining({ method: 'PUT', body: JSON.stringify({ display_name: '重命名后的项目' }) }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      `/api/v1/projects/${projectId}`,
      expect.objectContaining({ method: 'DELETE' }),
    )
  })
})

function jsonResponse(body: object) {
  return new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
  })
}
