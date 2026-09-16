import { afterEach, expect, it, vi } from 'vitest'
import { dispatchActionCandidateJob } from '@/api/action-candidate'

afterEach(() => vi.unstubAllGlobals())

it('explains missing published schema without requiring a human task', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ error_code: 'AI_ACTION_SCHEMA_REQUIRED' }), {
        status: 409,
      }),
    ),
  )
  await expect(dispatchActionCandidateJob('project', 'group')).rejects.toThrow(
    '暂无可用的动作标签规范',
  )
})

it('handles non-JSON gateway errors', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Bad gateway', { status: 502 })))
  await expect(dispatchActionCandidateJob('project', 'group')).rejects.toThrow('HTTP 502')
})
