import { afterEach, expect, it, vi } from 'vitest'
import { dispatchActionCandidateJob } from '@/api/action-candidate'

afterEach(() => vi.unstubAllGlobals())

it('explains missing task schema without blaming the worker', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ error_code: 'AI_ACTION_TASK_SCHEMA_REQUIRED' }), {
          status: 409,
        }),
      ),
  )
  await expect(dispatchActionCandidateJob('project', 'group')).rejects.toThrow(
    '请先由项目管理员分配标注任务',
  )
})

it('handles non-JSON gateway errors', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Bad gateway', { status: 502 })))
  await expect(dispatchActionCandidateJob('project', 'group')).rejects.toThrow('HTTP 502')
})
