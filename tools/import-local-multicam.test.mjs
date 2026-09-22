import assert from 'node:assert/strict'
import { test } from 'node:test'
import { resolveProject } from './import-local-multicam.mjs'

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
