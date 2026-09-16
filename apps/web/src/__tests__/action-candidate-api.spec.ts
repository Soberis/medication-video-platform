import { afterEach, describe, expect, it, vi } from 'vitest'
import { dispatchActionCandidateJob, getTargetObjectLabel } from '@/api/action-candidate'

afterEach(() => vi.unstubAllGlobals())

describe('dispatchActionCandidateJob', () => {
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
})

describe('getTargetObjectLabel', () => {
  it('maps known target objects to readable Chinese labels', () => {
    expect(getTargetObjectLabel('vial')).toBe('西林瓶/粉针瓶')
    expect(getTargetObjectLabel('ampoule')).toBe('安瓿瓶')
    expect(getTargetObjectLabel('syringe')).toBe('注射器')
    expect(getTargetObjectLabel('infusion_bottle')).toBe('输液瓶/盐水袋')
  })

  it('falls back to raw string for unrecognized objects', () => {
    expect(getTargetObjectLabel('custom_pipette')).toBe('custom_pipette')
  })

  it('returns null for missing, empty or nullish values', () => {
    expect(getTargetObjectLabel(null)).toBeNull()
    expect(getTargetObjectLabel(undefined)).toBeNull()
    expect(getTargetObjectLabel('')).toBeNull()
  })
})
