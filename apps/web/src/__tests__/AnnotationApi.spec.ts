import { afterEach, describe, expect, it, vi } from 'vitest'

import { createAnnotationTask, listAnnotationTasks } from '@/api/annotation'

const projectId = '30000000-0000-4000-8000-000000000001'
const assetId = '50000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('annotation task API', () => {
  it('creates an assigned task with an idempotency key and CSRF header', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          {
            annotation_task_id: 'a0000000-0000-4000-8000-000000000001',
            project_id: projectId,
            clip_asset_id: assetId,
            assignee_actor_id: 'local-synthetic-uploader',
            created_by: 'local-synthetic-admin',
            status: 'ASSIGNED',
            created_at: '2026-07-23T01:00:00Z',
            updated_at: '2026-07-23T01:00:00Z',
            version: 0,
          },
          { status: 201 },
        ),
      ),
    )

    const task = await createAnnotationTask(projectId, assetId, 'local-synthetic-uploader')

    expect(task.status).toBe('ASSIGNED')
    expect(fetch).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${assetId}/annotation-tasks`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ assignee_actor_id: 'local-synthetic-uploader' }),
        headers: expect.objectContaining({ 'Idempotency-Key': expect.any(String) }),
      }),
    )
  })

  it('lists the visible task queue and explains a qualified-state rejection', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ items: [] }))
      .mockResolvedValueOnce(jsonResponse({ error_code: 'ANNOTATION_TASK_QUALITY_NOT_QUALIFIED' }, { status: 409 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(listAnnotationTasks(projectId)).resolves.toEqual([])
    await expect(createAnnotationTask(projectId, assetId, 'local-synthetic-uploader')).rejects.toMatchObject({
      errorCode: 'ANNOTATION_TASK_QUALITY_NOT_QUALIFIED',
      message: '只有已判定合格的视频才能创建标注任务。',
    })
  })
})

function jsonResponse(body: object, init?: ResponseInit) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
}
