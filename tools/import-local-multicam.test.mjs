import assert from 'node:assert/strict'
import { test } from 'node:test'
import { requestMediaProbes, resolveProject } from './import-local-multicam.mjs'

test('project resolution respects API authorization instead of falling back to a default project', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => new Response('{}', { status: 403 }))
  await assert.rejects(resolveProject('http://localhost:8080', 'synthetic-test-token', 'requested'), /HTTP 403/)
})

test('an empty authorized project list does not invent project access', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => Response.json({ items: [] }))
  await assert.rejects(resolveProject('http://localhost:8080', 'synthetic-test-token'), /没有可访问的项目/)
})

test('project selection uses authorized results and rejects ambiguous partial names', async (t) => {
  const items = [{ project_id: 'project-a', display_name: '测试 A' }, { project_id: 'project-b', display_name: '测试 B' }]
  t.mock.method(globalThis, 'fetch', async () => Response.json({ items }))
  assert.deepEqual(await resolveProject('http://localhost:8080', 'synthetic-test-token', 'project-b'), items[1])
  await assert.rejects(resolveProject('http://localhost:8080', 'synthetic-test-token', '测试'), /匹配多个结果/)
})

test('project resolution does not mutate project memberships', async (t) => {
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, init })
    return Response.json({ items: [{ project_id: 'project-a', display_name: '测试 A' }] })
  })

  await resolveProject('http://localhost:8080', 'synthetic-test-token', 'project-a')

  assert.equal(calls.length, 1)
  assert.equal(calls[0].init?.method, undefined)
  assert.match(calls[0].init.headers.Authorization, /synthetic-test-token/)
})

test('duplicate exact project names require an ID instead of selecting the first project', async (t) => {
  const items = [{ project_id: 'project-a', display_name: '测试' }, { project_id: 'project-b', display_name: '测试' }]
  t.mock.method(globalThis, 'fetch', async () => Response.json({ items }))
  await assert.rejects(resolveProject('http://localhost:8080', 'synthetic-test-token', '测试'), /指定项目 ID/)
  assert.deepEqual(await resolveProject('http://localhost:8080', 'synthetic-test-token', 'project-b'), items[1])
})

test('media probe requests preserve failures instead of reporting already queued', async (t) => {
  const log = t.mock.method(console, 'log', () => {})
  for (const error of [new Error('HTTP 403'), new Error('HTTP 500'), new TypeError('fetch failed')]) {
    await assert.rejects(requestMediaProbes({ requestMediaProbe: async () => { throw error } }, [
      { cameraNumber: 1, clipAssetId: 'clip-a' },
    ]), (actual) => actual === error)
  }
  assert.equal(log.mock.callCount(), 0)
})

test('media probe requests cover each uploaded camera', async (t) => {
  t.mock.method(console, 'log', () => {})
  const calls = []
  await requestMediaProbes({ requestMediaProbe: async (id) => { calls.push(id) } }, [
    { cameraNumber: 1, clipAssetId: 'clip-a' },
    { cameraNumber: 3, clipAssetId: 'clip-c' },
  ])
  assert.deepEqual(calls, ['clip-a', 'clip-c'])
})
